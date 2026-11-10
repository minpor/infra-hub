package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

@Component
@Profile("worker")
class ImagePublisher(
    private val store: QueueStore,
    @param:Value("\${hub.image-namespace:}") private val namespace: String,
    @param:Value("\${hub.runtime-image:}") private val runtimeImage: String,
    @param:Value("\${hub.registry-config-directory:}") private val registryConfig: String
) {
    companion object {
        fun validateArtifactPath(path: String) {
            require(path.length <= 300 && path.endsWith(".jar") && path.split('/').all {
                it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) && it != "." && it != ".."
            }) { "Укажите относительный путь к одному JAR без маски и переходов между каталогами" }
        }

        fun validateConfiguration(namespace: String, runtime: String) {
            require(namespace.length <= 240 && namespace.matches(Regex("[a-z0-9][a-z0-9.-]*(?::[0-9]{1,5})?/[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"))) {
                "Настройте HUB_IMAGE_NAMESPACE: адрес registry и пространство образов"
            }
            require(runtime.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[a-f0-9]{64}"))) {
                "Настройте HUB_RUNTIME_IMAGE: образ Java 21 с закреплённым sha256 digest"
            }
        }
    }

    fun configured() {
        try { validateConfiguration(namespace, runtimeImage) }
        catch (error: IllegalArgumentException) { throw RunnerFailure(error.message!!) }
    }

    fun publish(build: Build, worker: UUID, container: String, directory: Path, docker: List<String>, executor: ProcessExecutor) {
        val path = requireNotNull(build.source?.artifactPath)
        validateArtifactPath(path)
        configured()
        fun heartbeat() = store.heartbeat(build.id, worker)
        fun start(position: Int) {
            if (!store.startStep(build.id, worker, position)) throw ExecutionStopped(false)
        }
        val context = Files.createDirectory(directory.resolve("image"))
        val jar = context.resolve("app.jar")
        start(2)
        // The only data crossing the boundary is a bounded byte stream, never an untrusted tar or Dockerfile.
        val script = """
            import os, stat, sys
            p = os.path.realpath('/work/repository/' + sys.argv[1])
            assert p.startswith('/work/repository/')
            fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, 'rb') as f:
                st = os.fstat(f.fileno())
                assert stat.S_ISREG(st.st_mode) and 0 < st.st_size <= 268435456
                while True:
                    chunk = f.read(8192)
                    if not chunk: break
                    sys.stdout.buffer.write(chunk)
        """.trimIndent()
        val code = executor.runInput(docker + listOf("exec", container, "python3", "-c", script, path), directory, 60, null, ::heartbeat, {}, jar)
        store.completeStep(build.id, worker, 2, code)
        if (code != 0 || Files.size(jar) == 0L) throw RunnerFailure("Не удалось сохранить JAR: проверьте путь и размер (до 256 MiB).")
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(jar).use { input -> val bytes = ByteArray(8192); while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
        val sha256 = hash.digest().joinToString("") { "%02x".format(it) }
        store.appendLog(build.id, worker, 2, "JAR: ${Files.size(jar)} байт · sha256:$sha256\n")
        Files.writeString(context.resolve("Dockerfile"), """
            FROM $runtimeImage
            WORKDIR /app
            COPY --chown=10001:10001 app.jar /app/app.jar
            USER 10001:10001
            ENTRYPOINT ["java", "-jar", "/app/app.jar"]
        """.trimIndent())
        val tag = "$namespace/${build.projectId}:${build.id}"
        // Credentials are only used by the trusted packaging CLI, outside repository containers.
        val cli = if (registryConfig.isBlank()) docker else docker + listOf("--config", registryConfig)
        var primaryFailure: Throwable? = null
        try {
            start(3)
            val buildCode = executor.run(cli + listOf("build", "--network=none", "--label", "org.opencontainers.image.revision=${build.source.sha}", "-t", tag, context.toString()), directory, 300, ::heartbeat) { store.appendLog(build.id, worker, 3, it) }
            store.completeStep(build.id, worker, 3, buildCode)
            if (buildCode != 0) throw RunnerFailure("Не удалось упаковать Docker-образ. Подробности в журнале.")
            start(4)
            val pushCode = executor.run(cli + listOf("push", tag), directory, 300, ::heartbeat) { store.appendLog(build.id, worker, 4, it) }
            if (pushCode != 0) { store.completeStep(build.id, worker, 4, pushCode); throw RunnerFailure("Registry не принял образ. Проверьте адрес и доступ worker.") }
            val digestFile = directory.resolve("digest.txt")
            val inspectCode = executor.runInput(docker + listOf("image", "inspect", "--format", "{{json .RepoDigests}}", tag), directory, 20, null, ::heartbeat, {}, digestFile, 16384)
            if (inspectCode != 0) throw RunnerFailure("Не удалось проверить digest опубликованного образа")
            val repository = tag.substringBeforeLast(':')
            val digest = Regex(Regex.escape(repository) + "@sha256:[a-f0-9]{64}").find(Files.readString(digestFile))?.value
                ?: throw RunnerFailure("Registry не вернул digest опубликованного образа")
            store.recordImage(build.id, worker, PublishedImage(digest, sha256, Files.size(jar)))
            store.appendLog(build.id, worker, 4, "$digest\n")
            store.completeStep(build.id, worker, 4, 0)
        } catch (error: Throwable) { primaryFailure = error; throw error }
        finally {
            // Job tags are unique. Keep registry images, remove only the local packaging image.
            try {
                val child = ProcessBuilder(docker + listOf("image", "rm", "-f", tag)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); throw RunnerFailure("Не удалось очистить локальный образ") }
            } catch (error: Exception) { if (primaryFailure == null) throw error }
        }
    }
}
