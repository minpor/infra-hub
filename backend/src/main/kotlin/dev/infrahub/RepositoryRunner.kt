package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class RunnerFailure(message: String) : RuntimeException(message)

@Component
@Profile("worker")
class RepositoryRunner(
    private val settings: RunnerSettings,
    private val store: QueueStore,
    private val publisher: ImagePublisher,
    @param:Value("\${hub.runner-enabled:false}") private val enabled: Boolean,
    @param:Value("\${hub.runner-job-label:dev.infrahub.job}") private val jobLabel: String,
    @param:Value("\${hub.docker-host:unix:///run/user/1001/docker.sock}") private val dockerHost: String,
    @param:Value("\${hub.builder-image:infra-hub-builder:java21}") private val builderImage: String,
    @param:Value("\${hub.worker-api-url:http://127.0.0.1:8080}") private val apiUrl: String,
    @param:Value("\${hub.worker-secret:}") private val secret: String
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val docker get() = listOf("/usr/bin/docker", "--host", dockerHost)

    private fun control(vararg args: String): String {
        val child = ProcessBuilder(docker + args).redirectErrorStream(true).start()
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread.ofVirtual().start { child.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (output.size() < 65536) output.write(buffer, 0, count) }
        } }
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) throw RunnerFailure("Docker не отвечает: превышено время ожидания.")
            reader.join(1000)
            if (child.exitValue() != 0) throw RunnerFailure("Команда Docker не выполнена. Проверьте rootless daemon и образ исполнителя.")
            return output.toString(Charsets.UTF_8).trim()
        } finally { if (child.isAlive) child.destroyForcibly() }
    }

    /** Called before claiming a new job. A failed cleanup prevents another repository process from starting. */
    fun recover() {
        if (!enabled) return
        val security = control("info", "--format", "{{json .SecurityOptions}} {{.CgroupVersion}}")
        if (!security.contains("rootless") || !security.endsWith("2")) throw RunnerFailure("Для сборок требуется отдельный rootless Docker с cgroup v2.")
        control("ps", "-aq", "--filter", "label=$jobLabel").lines().filter { it.isNotBlank() }.forEach { container ->
            val build = UUID.fromString(control("inspect", "--format", "{{index .Config.Labels \"$jobLabel\"}}", container))
            if (!store.hasLease(build)) control("rm", "-f", container)
        }
    }

    fun run(build: Build, worker: UUID, directory: Path, executor: ProcessExecutor) {
        if (!enabled || secret.length < 32) throw RunnerFailure("Исполнитель репозиториев не настроен. Требуются rootless Docker и канал получения исходников.")
        val source = requireNotNull(build.source)
        if (source.artifactPath != null) publisher.configured()
        val name = "infra-hub-job-${build.id}"
        val resources = settings.resources(build.projectId)
        val cacheEnabled = source.yamlRecipe?.definition?.build?.cache ?: resources.dependencyCacheEnabled
        val cacheVolume = if (cacheEnabled) "infra-hub-dependencies-v1-${build.projectId}" else null
        fun heartbeat(): BuildStatus? = store.heartbeat(build.id, worker)
        fun checkLease() {
            when (heartbeat()) {
                BuildStatus.RUNNING -> Unit
                BuildStatus.CANCEL_REQUESTED -> throw ExecutionStopped(true)
                else -> throw ExecutionStopped(false)
            }
        }
        fun log(position: Int, text: String) = store.appendLog(build.id, worker, position, text)
        fun start(position: Int) {
            if (!store.startStep(build.id, worker, position)) throw ExecutionStopped(store.get(build.id).status == BuildStatus.CANCEL_REQUESTED)
        }
        start(0)
        store.recordRunner(build.id, worker, RunnerExecution(resources.cpus, resources.memoryGiB, if (cacheEnabled) "PENDING" else "DISABLED", cacheVolume))
        log(0, "${source.repository} @ ${source.sha}\n")
        val request = HttpRequest.newBuilder(URI.create("${apiUrl.trimEnd('/')}/internal/builds/${build.id}/source"))
            .timeout(Duration.ofSeconds(100)).header("X-Worker-Id", worker.toString()).header("X-Worker-Secret", secret).GET().build()
        val download = client.sendAsync(request, limitedBody(32 * 1024 * 1024))
        try {
            while (!download.isDone) { checkLease(); Thread.sleep(250) }
            val response = download.get()
            if (response.statusCode() != 200) throw RunnerFailure("Не удалось получить исходники (HTTP ${response.statusCode()}). Проверьте доступ GitHub App и связь worker с API.")
            checkLease()
            Files.write(directory.resolve("source.tar.gz"), response.body())
        } finally { download.cancel(true) }
        // Only a project-scoped dependency volume persists. No host paths, secrets, Docker socket or direct network.
        val create = docker + listOf("create", "--name", name, "--label", "$jobLabel=${build.id}",
            "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--user", "10001:10001", "--cpus", resources.cpus.toString(), "--memory", "${resources.memoryGiB}g", "--memory-swap", "${resources.memoryGiB}g", "--pids-limit", "512",
            "--ulimit", "nofile=4096:4096", "--log-driver", "local", "--log-opt", "max-size=10m", "--log-opt", "max-file=2",
            "--tmpfs", "/work:rw,exec,nosuid,nodev,size=4g,uid=10001,gid=10001,mode=700",
            "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=512m,mode=1777",
            "--mount", "type=volume,src=infra-hub-dependency-proxy,dst=/proxy,readonly") +
            (if (cacheVolume != null) listOf("--mount", "type=volume,src=$cacheVolume,dst=/cache") else emptyList()) + listOf(
            "--workdir", "/work", "--env", "HOME=/work/home", "--env", "HUB_DEPENDENCY_CACHE=${if (cacheEnabled) "1" else "0"}",
            "--env", "HUB_EXPECTED_MEMORY_BYTES=${resources.memoryBytes}", "--env", "HUB_EXPECTED_CPUS=${resources.cpus}", builderImage,
            "timeout", "--kill-after=5s", "2760s", "sleep", "infinity")
        try {
            checkLease()
            if (executor.run(create, directory, 30, ::heartbeat) { log(0, it) } != 0) throw RunnerFailure("Не удалось создать сборочный контейнер.")
            if (executor.run(docker + listOf("start", name), directory, 30, ::heartbeat) { log(0, it) } != 0) throw RunnerFailure("Не удалось запустить сборочный контейнер.")
            val cacheStatus = if (cacheVolume == null) "DISABLED" else {
                // Only inspect presence, never log filenames or contents from a previous build.
                val existing = control("exec", name, "sh", "-eu", "-c", "test -w /cache; find /cache -type f -print -quit")
                if (existing.isBlank()) "EMPTY" else "AVAILABLE"
            }
            store.recordRunner(build.id, worker, RunnerExecution(resources.cpus, resources.memoryGiB, cacheStatus, cacheVolume))
            log(0, when (cacheStatus) {
                "DISABLED" -> "Кэш зависимостей выключен: чистая сборка, хранилище проекта не подключено.\n"
                "EMPTY" -> "Кэш зависимостей: пустое хранилище проекта. Загрузки сохранятся для следующих сборок.\n"
                else -> "Кэш зависимостей: подключено хранилище проекта с сохранёнными файлами. Gradle и npm переиспользуют подходящие зависимости.\n"
            })
            if (cacheVolume != null) log(0, "Хранилище: $cacheVolume → /cache (Gradle distributions, Gradle dependencies, npm)\n")
            if (executor.runInput(docker + listOf("exec", "-i", name, "sh", "-c", "cat > /work/source.tar.gz"), directory, 30, directory.resolve("source.tar.gz"), ::heartbeat, { log(0, it) }) != 0) throw RunnerFailure("Не удалось передать исходники в контейнер.")
            store.completeStep(build.id, worker, 0, 0)
            start(1)
            log(1, "Лимиты: ${resources.cpus} CPU · ${resources.memoryGiB} GiB RAM · 512 процессов · 40 минут\n$ ${source.command}\n")
            val code = executor.run(docker + listOf("exec", "--env", "HUB_COMMIT_SHA=${source.sha}", "--env", "CHROME_BIN=/usr/bin/chromium", name, "/opt/hub/run-build", source.command), directory, 2400, ::heartbeat) { log(1, it) }
            store.completeStep(build.id, worker, 1, code)
            if (code == 0 && source.artifactPath != null) publisher.publish(build, worker, name, directory, docker, executor)
            // Cleanup is part of success: don't publish a terminal result while a container can still execute.
            control("rm", "-f", name)
            store.finish(build.id, worker, if (code == 0) BuildStatus.SUCCEEDED else BuildStatus.FAILED,
                if (code == 0) (if (source.artifactPath != null) "Образ опубликован для ${source.sha.take(12)}." else "Сборка и тесты завершены для ${source.sha.take(12)}.") else "Сборка завершилась с кодом $code. Подробности в журнале.")
        } finally {
            // Also handles cancellation, lost database connection and a killed Docker CLI.
            val existing = control("ps", "-aq", "--filter", "name=^/$name$")
            if (existing.isNotBlank()) control("rm", "-f", name)
        }
    }
}
