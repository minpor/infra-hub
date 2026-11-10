package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists

@Component
@Profile("deployer")
class DeploymentRunner(
    private val store: DeploymentStore, private val queue: QueueStore, private val servers: ServerService, private val ssh: SshTransport, private val mapper: ObjectMapper, private val android: AndroidPublisher, private val yaml: YamlDeploymentRunner,
    @param:Value("\${hub.docker-host:unix:///run/build-docker.sock}") private val dockerHost: String,
    @param:Value("\${hub.artifact-directory:/work/artifacts}") private val artifacts: String,
    @param:Value("\${hub.deploy-directory:.local/deployments}") private val root: String
) {
    private val owner = UUID.randomUUID()
    private val closing = AtomicBoolean(false)
    private val executor = ProcessExecutor()
    @PreDestroy fun stop() { closing.set(true); executor.stop() }

    @Scheduled(fixedDelay = 1000)
    fun tick() {
        if (closing.get()) return
        val deployment = store.claim(owner) ?: return
        var directory: Path? = null
        try {
            val base = Files.createDirectories(Path.of(root).toAbsolutePath())
            directory = Files.createTempDirectory(base, "${deployment.id}-")
            execute(deployment, directory)
        } catch (stopped: ExecutionStopped) {
            store.finish(deployment.id, owner, if (stopped.cancelled) "CANCELLED" else "INTERRUPTED", if (stopped.cancelled) "Отменено до переключения приложения" else "Исполнение прервано; нужна сверка")
        } catch (_: Exception) {
            store.finish(deployment.id, owner, "INTERRUPTED", "Исполнение прервано. Проверьте журнал и повторите сверку того же выпуска.")
        } finally {
            directory?.let { path -> Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
        }
    }

    private fun execute(deployment: Deployment, directory: Path) {
        val app = store.application(deployment.applicationId)
        val server = servers.get(app.serverId)
        val execution = store.yamlExecution(deployment.id)
        if (execution != null && execution.recipe.definition.build.artifact == null) {
            yaml.execute(deployment, app, server, owner, null, directory, executor)
            return
        }
        val docker = listOf("/usr/bin/docker", "--host", dockerHost)
        var switching = false
        fun heartbeat(): BuildStatus? {
            val state = store.heartbeat(deployment.id, owner)
            return if (switching && state == BuildStatus.CANCEL_REQUESTED) BuildStatus.RUNNING else state
        }
        fun log(text: String) = store.log(deployment.id, owner, text)
        fun run(args: List<String>, timeout: Long = 300, input: Path? = null, output: Path? = null, limit: Long = 2L * 1024 * 1024 * 1024) {
            val code = executor.runInput(args, directory, timeout, input, ::heartbeat, ::log, output, limit)
            if (code != 0) throw RunnerFailure("Операция завершилась с кодом $code")
        }
        require(deployment.imageReference.matches(Regex("[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}")))
        log("Получение образа по digest: ${deployment.imageReference}\n")
        run(docker + listOf("pull", deployment.imageReference))
        val imageFile = directory.resolve("image.json")
        run(docker + listOf("image", "inspect", deployment.imageReference), 20, output = imageFile, limit = 1024 * 1024)
        val sourceImage = mapper.readTree(Files.readString(imageFile)).first()
        var imageId = sourceImage["Id"].asText()
        require(imageId.matches(Regex("sha256:[a-f0-9]{64}")))
        if (execution == null && app.kind == "ANDROID") { android.publish(deployment, app, server, owner, imageId, directory, docker, executor); return }
        if (execution == null && app.kind == "COMPOSE") {
            val exportName = "infra-hub-report-${deployment.id}"
            val jar = directory.resolve("release.jar")
            run(docker + listOf("create", "--name", exportName, imageId))
            try { run(docker + listOf("cp", "$exportName:/app/app.jar", jar.toString())) }
            finally {
                val child = ProcessBuilder(docker + listOf("rm", "-f", exportName)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly()
            }
            val reports = Files.createDirectories(Path.of(artifacts).resolve(deployment.id.toString()))
            java.util.zip.ZipFile(jar.toFile()).use { zip ->
                zip.getEntry("META-INF/infra-hub/qa.zip")?.let { entry ->
                    require(entry.size in 1..(128L * 1024 * 1024))
                    zip.getInputStream(entry).use { Files.copy(it, reports.resolve("qa.zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            Files.writeString(reports.resolve("metadata.json"), mapper.writeValueAsString(mapOf("buildId" to deployment.buildId, "source" to queue.get(deployment.buildId).source, "imageReference" to deployment.imageReference, "imageId" to imageId)))
        }
        val archive = directory.resolve("image.tar")
        log("Подготовка и передача образа по SSH (до 2 GiB)\n")
        val transferTag = "infra-hub-transfer:${deployment.id}"
        run(docker + listOf("tag", deployment.imageReference, transferTag), 20)
        run(docker + listOf("image", "save", "--platform", sourceImage["Os"].asText() + "/" + sourceImage["Architecture"].asText(), transferTag), output = archive)
        ssh.withCommand(server, "docker image load --quiet") { args -> run(args, input = archive) }
        val verification = directory.resolve("verification.json")
        Files.writeString(verification, mapper.writeValueAsString(mapOf("tag" to transferTag, "layers" to sourceImage["RootFS"]["Layers"], "configuration" to sourceImage["Config"], "architecture" to sourceImage["Architecture"].asText(), "os" to sourceImage["Os"].asText())))
        val importScript = javaClass.getResource("/deploy/verify-import.py")!!.readText()
        val imported = directory.resolve("imported.json")
        ssh.withCommand(server, "python3 -c '" + importScript.replace("'", "'\"'\"'") + "'") { args -> run(args, 60, verification, imported, 16384) }
        imageId = mapper.readTree(Files.readString(imported))["imageId"].asText()
        require(imageId.matches(Regex("sha256:[a-f0-9]{64}")))
        log("Слои и конфигурация образа сверены; image ID целевого Docker: $imageId\n")
        archive.deleteIfExists()
        if (execution != null) {
            yaml.execute(deployment, app, server, owner, imageId, directory, executor)
            return
        }
        val payload = directory.resolve("release.json")
        Files.writeString(payload, mapper.writeValueAsString(mapOf(
            "applicationId" to app.id, "deploymentId" to deployment.id, "imageReference" to deployment.imageReference,
            "imageId" to imageId, "hostPort" to app.hostPort, "containerPort" to app.containerPort,
            "healthPath" to app.healthPath, "environment" to store.environment(app.id), "configuration" to app.configuration
        )))
        val scriptName = when (app.kind) { "COMPOSE" -> "apply-compose.py"; "ANDROID" -> "apply-android.py"; else -> "apply.py" }
        val script = javaClass.getResource("/deploy/$scriptName")!!.readText()
        val command = "python3 -c '" + script.replace("'", "'\"'\"'") + "'"
        val receipt = directory.resolve("receipt.json")
        log("Запуск выпуска на ${server.name}; проверка HTTP ${app.healthPath} и фактической версии\n")
        if (store.heartbeat(deployment.id, owner) == BuildStatus.CANCEL_REQUESTED) throw ExecutionStopped(true)
        switching = true // Let the bounded remote transaction finish and report actual state after the switch starts.
        ssh.withCommand(server, command) { args -> run(args, if (app.kind == "STANDALONE") 240 else 900, payload, receipt, 16384) }
        val result = mapper.readTree(Files.readString(receipt))
        val status = result.path("status").asText()
        require(status in setOf("SUCCEEDED", "FAILED", "INTERRUPTED"))
        if (status == "SUCCEEDED") require(result.path("deploymentId").asText() == deployment.id.toString() && result.path("imageId").asText() == imageId)
        val cancelledAfterSwitch = store.get(deployment.id).status == "CANCEL_REQUESTED"
        val message = (result.path("message").asText() + if (cancelledAfterSwitch) " Отмена поступила после начала переключения; сохранён фактический результат." else "").take(1000)
        log("$status: $message\n")
        store.finish(deployment.id, owner, status, message)
    }
}
