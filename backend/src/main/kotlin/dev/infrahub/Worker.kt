package dev.infrahub

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists

class ExecutionStopped(val cancelled: Boolean) : RuntimeException()

/** Only bundled commands run in this bootstrap executor; it is not an isolation boundary for repository code. */
class ProcessExecutor {
    @Volatile private var process: Process? = null
    private val closing = AtomicBoolean(false)

    fun stop() {
        closing.set(true)
        process?.let(::terminate)
    }

    private fun terminate(child: Process) {
        val descendants = child.descendants().toList()
        descendants.asReversed().forEach { it.destroyForcibly() }
        child.destroyForcibly()
        child.waitFor(5, TimeUnit.SECONDS)
    }

    fun run(command: List<String>, directory: Path, timeoutSeconds: Long, heartbeat: () -> BuildStatus?, log: (String) -> Unit): Int =
        runInput(command, directory, timeoutSeconds, null, heartbeat, log)

    fun runInput(command: List<String>, directory: Path, timeoutSeconds: Long, input: Path?, heartbeat: () -> BuildStatus?, log: (String) -> Unit, output: Path? = null, maxOutput: Long = 256L * 1024 * 1024, environment: Map<String, String> = emptyMap()): Int {
        if (closing.get()) throw ExecutionStopped(false)
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(output == null)
        // Never pass the centre's database credentials or environment to build processes.
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to directory.toString(), "LANG" to "C.UTF-8"))
        builder.environment().putAll(environment)
        val child = builder.start()
        process = child
        val inputError = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val writer = Thread.ofVirtual().start {
            try { child.outputStream.use { output -> if (input != null) Files.newInputStream(input).use { it.copyTo(output) } } }
            catch (error: Exception) { inputError.set(error) }
        }
        val chunks = ArrayBlockingQueue<String>(256)
        val overflow = AtomicBoolean(false)
        val outputError = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val stderr = if (output != null) Thread.ofVirtual().start {
            child.errorStream.reader().use { stream ->
                val buffer = CharArray(4096)
                var remaining = 16384
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val accepted = count.coerceAtMost(remaining)
                    if (accepted > 0) chunks.offer(String(buffer, 0, accepted))
                    remaining -= accepted
                }
            }
        } else null
        val reader = Thread.ofVirtual().start {
            if (output != null) {
                try {
                    child.inputStream.use { inputStream -> Files.newOutputStream(output).use { sink ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            val count = inputStream.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > maxOutput) throw RunnerFailure("Бинарный вывод превышает допустимый размер")
                            sink.write(buffer, 0, count)
                        }
                    } }
                } catch (error: Exception) { outputError.set(error); child.destroyForcibly() }
                return@start
            }
            child.inputStream.reader().use { input ->
                val buffer = CharArray(4096)
                var remaining = 256 * 1024
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val accepted = count.coerceAtMost(remaining)
                    if (accepted > 0 && !chunks.offer(String(buffer, 0, accepted))) overflow.set(true)
                    remaining -= accepted
                    if (accepted < count) overflow.set(true)
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (true) {
                if (closing.get()) throw ExecutionStopped(false)
                when (heartbeat()) {
                    BuildStatus.RUNNING -> Unit
                    BuildStatus.CANCEL_REQUESTED -> throw ExecutionStopped(true)
                    else -> throw ExecutionStopped(false)
                }
                while (true) log(chunks.poll() ?: break)
                if (!child.isAlive) break
                if (System.nanoTime() > deadline) throw IllegalStateException("Шаг превысил лимит времени")
                child.waitFor(250, TimeUnit.MILLISECONDS)
            }
            reader.join(3000)
            stderr?.join(3000)
            outputError.get()?.let { throw it }
            while (true) log(chunks.poll() ?: break)
            if (overflow.get()) log("\n[Журнал сокращён: превышен лимит вывода]\n")
            writer.join(3000)
            if (inputError.get() != null && child.exitValue() == 0) throw RunnerFailure("Не удалось передать входные данные процессу.")
            return child.exitValue()
        } finally {
            if (child.isAlive) terminate(child)
            reader.join(3000)
            writer.join(3000)
            stderr?.join(3000)
            process = null
        }
    }
}

@Component
@Profile("worker")
class Worker(private val store: QueueStore, private val repositories: RepositoryRunner, @param:Value("\${hub.work-directory}") private val root: String) {
    private val id = UUID.randomUUID()
    private val executor = ProcessExecutor()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stopping = AtomicBoolean(false)

    @PreDestroy fun stop() {
        stopping.set(true)
        executor.stop()
    }

    @Scheduled(fixedDelay = 1000)
    fun tick() {
        if (stopping.get()) return
        repositories.recover()
        val build = store.claim(id) ?: return
        var directory: Path? = null
        try {
            val base = Files.createDirectories(Path.of(root).toAbsolutePath())
            directory = Files.createTempDirectory(base, "${build.id}-")
            if (build.source != null) { repositories.run(build, id, directory, executor); return }
            javaClass.getResourceAsStream("/example/Hello.java")!!.use { Files.copy(it, directory.resolve("Hello.java")) }
            val javaBin = Path.of(System.getProperty("java.home"), "bin")
            val commands = listOf(
                listOf(javaBin.resolve("javac").toString(), "Hello.java"),
                listOf(javaBin.resolve("java").toString(), "-Xmx64m", "-cp", ".", "Hello", "--test"),
                listOf(javaBin.resolve("jar").toString(), "--create", "--file", "hello.jar", "--main-class", "Hello", "Hello.class")
            )
            commands.forEachIndexed { position, command ->
                if (!store.startStep(build.id, id, position)) throw ExecutionStopped(store.get(build.id).status == BuildStatus.CANCEL_REQUESTED)
                store.appendLog(build.id, id, position, "$ ${command.joinToString(" ") { Path.of(it).fileName.toString() }}\n")
                val code = executor.run(command, directory, 60, { store.heartbeat(build.id, id) }) {
                    store.appendLog(build.id, id, position, it)
                }
                store.completeStep(build.id, id, position, code)
                if (code != 0) {
                    store.finish(build.id, id, BuildStatus.FAILED, "Шаг завершился с кодом $code")
                    return
                }
            }
            store.finish(build.id, id, BuildStatus.SUCCEEDED, "Пример скомпилирован, тесты пройдены, JAR создан.")
        } catch (stop: ExecutionStopped) {
            store.finish(build.id, id, if (stop.cancelled) BuildStatus.CANCELLED else BuildStatus.INTERRUPTED,
                if (stop.cancelled) "Отменена владельцем" else "Выполнение остановлено; автоматический повтор отключён")
        } catch (error: Exception) {
            logger.warn("Build {} failed ({})", build.id, error.javaClass.simpleName)
            val message = when {
                error is RunnerFailure -> error.message!!
                error is IllegalStateException && error.message == "Шаг превысил лимит времени" -> error.message!!
                else -> "Ошибка исполнителя. Проверьте доступность БД, JDK и рабочего каталога."
            }
            store.failRunningStep(build.id, id, message)
            store.finish(build.id, id, BuildStatus.FAILED, message)
        } finally {
            directory?.let { path -> Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
        }
    }
}
