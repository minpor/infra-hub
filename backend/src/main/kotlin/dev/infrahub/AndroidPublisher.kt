package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

@Component
@Profile("deployer")
class AndroidPublisher(
    private val store: DeploymentStore, private val queue: QueueStore, private val ssh: SshTransport, private val mapper: ObjectMapper,
    @param:Value("\${hub.android-signing-directory:/run/android-signing}") private val keys: String,
    @param:Value("\${hub.android-tools:/opt/android-sdk/build-tools/35.0.0}") private val tools: String,
    @param:Value("\${hub.artifact-directory:/work/artifacts}") private val artifacts: String
) {
    fun publish(d: Deployment, app: HubApplication, server: Server, owner: UUID, imageId: String, work: Path, docker: List<String>, executor: ProcessExecutor) {
        var publishing = false
        fun heartbeat(): BuildStatus? {
            val state = store.heartbeat(d.id, owner)
            return if (publishing && state == BuildStatus.CANCEL_REQUESTED) BuildStatus.RUNNING else state
        }
        fun log(text: String) = store.log(d.id, owner, text)
        fun run(args: List<String>, env: Map<String, String> = emptyMap(), input: Path? = null, output: Path? = null) {
            val code = executor.runInput(args, work, 180, input, ::heartbeat, ::log, output, 16384, env)
            if (code != 0) throw RunnerFailure("Android: операция завершилась с кодом $code")
        }
        val jar = work.resolve("release.jar")
        val name = "infra-hub-android-export-${d.id}"
        run(docker + listOf("create", "--name", name, imageId))
        try { run(docker + listOf("cp", "$name:/app/app.jar", jar.toString())) }
        finally {
            val cleanup = ProcessBuilder(docker + listOf("rm", "-f", name)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!cleanup.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) cleanup.destroyForcibly()
        }
        ZipFile(jar.toFile()).use { release ->
            val entry = release.getEntry("META-INF/infra-hub/android.zip") ?: throw RunnerFailure("Образ не содержит Android-релиз")
            require(entry.size in 1..(128L * 1024 * 1024))
            ZipInputStream(release.getInputStream(entry)).use { zip ->
                var size = 0L
                while (true) {
                    val file = zip.nextEntry ?: break
                    require(file.name in setOf("app-unsigned.apk", "app-unsigned.aab", "mapping.txt", "metadata.json", "qa.zip"))
                    Files.newOutputStream(work.resolve(file.name)).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) { val count = zip.read(buffer); if (count < 0) break; size += count; require(size <= 256L * 1024 * 1024); output.write(buffer, 0, count) }
                    }
                }
            }
        }
        val metadata = mapper.readTree(Files.readString(work.resolve("metadata.json")))
        require(metadata["target"].asText() == app.configuration["target"])
        require(metadata["sha"].asText() == queue.get(d.buildId).source?.sha)
        val version = metadata["versionName"].asText()
        val code = metadata["versionCode"].asInt()
        require(version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}(?:-dev)?")) && code in 1..2100000000)
        val credentials = mapper.readTree(Files.readString(Path.of(keys, "credentials.json")))
        val env = listOf("AUKNI_KEYSTORE_PASSWORD", "AUKNI_KEY_PASSWORD").associateWith { credentials[it].asText() }
        val alias = credentials["AUKNI_KEY_ALIAS"].asText()
        val keystore = Path.of(keys, "aukni-upload.jks").toString()
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val directory = Files.createDirectories(Path.of(artifacts).resolve(d.id.toString()))
        val cached = listOf("aukni.apk", "aukni.aab", "metadata.json", "certificate.txt").all { Files.isRegularFile(directory.resolve(it)) }
        if (cached) {
            require(mapper.readTree(Files.readString(directory.resolve("metadata.json"))) == metadata)
            Files.copy(directory.resolve("aukni.apk"), work.resolve("aukni.apk"))
            Files.copy(directory.resolve("aukni.aab"), work.resolve("aukni.aab"))
            log("Повтор использует уже сохранённые подписанные bytes того же выпуска\n")
        } else {
        log("Выравнивание APK и подпись прежним ключом; ключ не передаётся сборочному контейнеру\n")
        run(listOf("$tools/zipalign", "-P", "16", "-f", "4", work.resolve("app-unsigned.apk").toString(), work.resolve("aligned.apk").toString()))
        run(listOf(javaBin, "-jar", "$tools/lib/apksigner.jar", "sign", "--ks", keystore, "--ks-key-alias", alias,
            "--ks-pass", "env:AUKNI_KEYSTORE_PASSWORD", "--key-pass", "env:AUKNI_KEY_PASSWORD", "--out", work.resolve("aukni.apk").toString(), work.resolve("aligned.apk").toString()), env)
        }
        run(listOf("$tools/zipalign", "-c", "-P", "16", "4", work.resolve("aukni.apk").toString()))
        val certificate = work.resolve("certificate.txt")
        run(listOf(javaBin, "-jar", "$tools/lib/apksigner.jar", "verify", "--print-certs", work.resolve("aukni.apk").toString()), output = certificate)
        require(Files.readString(certificate).contains("certificate SHA-256 digest: ${app.configuration["certificate"]}")) { "Сертификат APK отличается от принятого ключа" }
        val manifest = work.resolve("manifest.txt")
        run(listOf("$tools/aapt", "dump", "badging", work.resolve("aukni.apk").toString()), output = manifest)
        val header = Files.readString(manifest).lineSequence().first()
        require(header.contains("name='${app.configuration["package"]}'") && header.contains("versionCode='$code'") && header.contains("versionName='$version'"))
        if (!cached) {
        Files.copy(work.resolve("app-unsigned.aab"), work.resolve("aukni.aab"))
        run(listOf(Path.of(System.getProperty("java.home"), "bin", "jarsigner").toString(), "-keystore", keystore,
            "-storepass:env", "AUKNI_KEYSTORE_PASSWORD", "-keypass:env", "AUKNI_KEY_PASSWORD", work.resolve("aukni.aab").toString(), alias), env)
        }
        listOf("aukni.apk", "aukni.aab", "mapping.txt", "metadata.json", "certificate.txt", "qa.zip").forEach { file ->
            val source = work.resolve(file)
            if (Files.exists(source)) Files.copy(source, directory.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val apk = work.resolve("aukni.apk")
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(apk).use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        val sha = hash.digest().joinToString("") { "%02x".format(it) }
        val destination = "/opt/infra-hub/android-incoming/${d.id}"
        ssh.withCommand(server, "umask 077; mkdir -p $destination; cat > $destination/app.apk") { args -> run(args, input = apk) }
        val payload = work.resolve("publication.json")
        Files.writeString(payload, mapper.writeValueAsString(mapOf("deploymentId" to d.id, "versionCode" to code, "versionName" to version, "sha256" to sha, "size" to Files.size(apk), "configuration" to app.configuration)))
        val script = javaClass.getResource("/deploy/apply-android.py")!!.readText()
        val receipt = work.resolve("receipt.json")
        if (store.heartbeat(d.id, owner) == BuildStatus.CANCEL_REQUESTED) throw ExecutionStopped(true)
        publishing = true
        ssh.withCommand(server, "python3 -c '" + script.replace("'", "'\"'\"'") + "'") { args -> run(args, input = payload, output = receipt) }
        val result = mapper.readTree(Files.readString(receipt))
        require(result["status"].asText() in setOf("SUCCEEDED", "FAILED", "INTERRUPTED"))
        if (result["status"].asText() == "SUCCEEDED") {
            val http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()
            var confirmed = false
            repeat(12) {
                if (!confirmed) {
                    if (heartbeat() != BuildStatus.RUNNING) throw ExecutionStopped(false)
                    try {
                        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(app.configuration["url"] + "/api/app/version")).timeout(java.time.Duration.ofSeconds(5)).GET().build()
                        val response = http.send(request, limitedBody(16384))
                        val node = mapper.readTree(response.body())
                        confirmed = response.statusCode() == 200 && (node["latestVersionCode"] ?: node["versionCode"]).asInt() == code && node["sha256"].asText() == sha
                    } catch (_: Exception) { }
                    if (!confirmed) Thread.sleep(1000)
                }
            }
            if (!confirmed) throw RunnerFailure("APK записан, но внешний version API ещё не подтвердил checksum и versionCode; повторите сверку")
        }
        log("APK $version ($code): ${result["message"].asText()} · sha256:$sha\n")
        val lateCancel = store.get(d.id).status == "CANCEL_REQUESTED"
        store.finish(d.id, owner, result["status"].asText(), result["message"].asText() + if (lateCancel) " Отмена поступила после начала публикации; сохранён фактический результат." else "")
    }
}
