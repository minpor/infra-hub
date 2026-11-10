package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class NewServer(val id: UUID, val name: String, val host: String, val port: Int = 22, val username: String)
data class TrustHost(val key: String)
data class HostIdentity(val key: String, val fingerprint: String)
data class Server(
    val id: UUID, val name: String, val host: String, val port: Int, val username: String,
    val publicKey: String, val hostKey: String?, val fingerprint: String?, val status: String,
    val message: String?, val checkedAt: Instant?, val createdAt: Instant
)
data class SshResult(val code: Int, val output: String)

// Only fixed commands are run. Repository jobs never receive these keys or this directory.
@Component
@Profile("!worker")
class SshTransport(@param:Value("\${HUB_SSH_DIRECTORY:.local/ssh}") private val directory: String) {
    private val slots = Semaphore(2)
    private fun folder(id: UUID) = Path.of(directory).toAbsolutePath().resolve(id.toString())
    private fun protectedDirectory(path: Path) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }

    fun generate(id: UUID): String {
        protectedDirectory(Path.of(directory).toAbsolutePath())
        val dir = folder(id)
        protectedDirectory(dir)
        check(!Files.exists(dir.resolve("identity"))) { "SSH key already exists" }
        val result = run(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "infra-hub:$id", "-f", dir.resolve("identity").toString()))
        check(result.code == 0) { "Не удалось создать ключ SSH" }
        return Files.readString(dir.resolve("identity.pub")).trim()
    }

    fun remove(id: UUID) {
        val dir = folder(id)
        listOf("identity", "identity.pub").forEach { Files.deleteIfExists(dir.resolve(it)) }
        Files.deleteIfExists(dir)
    }

    fun scan(server: Server): HostIdentity {
        val result = run(listOf("ssh-keyscan", "-T", "5", "-p", server.port.toString(), "-t", "ed25519", server.host))
        val keys = result.output.lineSequence().filter { !it.startsWith("#") }.mapNotNull {
            val parts = it.trim().split(Regex("\\s+"))
            if (parts.size == 3 && parts[1] == "ssh-ed25519") "${parts[1]} ${parts[2]}" else null
        }.distinct().toList()
        if (result.code != 0 || keys.size != 1) throw Conflict("Не удалось получить единственный ключ ED25519 сервера. Проверьте адрес, порт и доступность SSH из Infra Hub.")
        return identity(keys.single())
    }

    fun resources(server: Server): SshResult {
        val script = javaClass.getResource("/server-resources.py")!!.readText()
        val command = "python3 -c '" + script.replace("'", "'\"'\"'") + "'"
        return withCommand(server, command) { run(it) }
    }

    fun check(server: Server): SshResult = withCommand(server, PROBE) { run(it) }

    fun <T> withCommand(server: Server, command: String, action: (List<String>) -> T): T {
        val key = server.hostKey ?: throw Conflict("Сначала сверьте и подтвердите отпечаток сервера")
        val identity = folder(server.id).resolve("identity")
        if (!Files.isRegularFile(identity)) throw Conflict("Ключ Infra Hub отсутствует. Восстановите каталог SSH из резервной копии.")
        val known = Files.createTempFile("infra-hub-known-", ".hosts")
        try {
            Files.writeString(known, "[${server.host}]:${server.port} $key\n${server.host} $key\n")
            return action(listOf(
                "ssh", "-F", "/dev/null", "-T", "-p", server.port.toString(), "-l", server.username,
                "-i", identity.toString(), "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
                "-o", "IdentityAgent=none", "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=$known", "-o", "GlobalKnownHostsFile=/dev/null",
                "-o", "UpdateHostKeys=no", "-o", "HostKeyAlgorithms=ssh-ed25519", "-o", "ConnectTimeout=5",
                "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=1",
                "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes", "-o", "LogLevel=ERROR",
                server.host, command
            ))
        } finally { Files.deleteIfExists(known) }
    }

    internal fun run(args: List<String>): SshResult {
        if (!slots.tryAcquire()) throw Conflict("Две SSH-проверки уже выполняются. Повторите через несколько секунд.")
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = ByteArrayOutputStream()
            val reader = thread(isDaemon = true) {
                runCatching {
                    process.inputStream.use { stream ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            synchronized(output) { output.write(buffer, 0, minOf(count, (16384 - output.size()).coerceAtLeast(0))) }
                        }
                    }
                }
            }
            val completed = try { process.waitFor(20, TimeUnit.SECONDS) } finally {
                if (process.isAlive) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
            reader.join(1000)
            if (!completed) return SshResult(124, "SSH-проверка превысила 20 секунд. Проверьте сеть и доступность сервера.")
            return SshResult(process.exitValue(), synchronized(output) { output.toString(Charsets.UTF_8) })
        } finally { slots.release() }
    }

    companion object {
        // No user-controlled commands, installs, sudo, container listing or deployment.
        const val PROBE = "printf 'INFRA_HUB_SSH_OK\\n'; hostname; " +
            "if docker version --format '{{.Server.Version}}' 2>/dev/null; then printf 'INFRA_HUB_DOCKER_OK\\n'; fi; " +
            "if docker compose version --short 2>/dev/null; then printf 'INFRA_HUB_COMPOSE_OK\\n'; fi"

        fun identity(key: String): HostIdentity {
            require(key.length <= 200 && Regex("ssh-ed25519 [A-Za-z0-9+/]+={0,2}").matches(key)) { "Нужен публичный ключ ED25519 сервера" }
            val bytes = try { Base64.getDecoder().decode(key.substringAfter(' ')) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Некорректный ключ сервера") }
            require(bytes.size == 51) { "Некорректный ключ ED25519" }
            val wire = ByteBuffer.wrap(bytes)
            require(wire.int == 11 && ByteArray(11).also { wire.get(it) }.decodeToString() == "ssh-ed25519" && wire.int == 32) { "Некорректный ключ ED25519" }
            val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            return HostIdentity(key, "SHA256:$fingerprint")
        }
    }
}

@Service
@Profile("!worker")
class ServerService(private val jdbc: JdbcTemplate, private val ssh: SshTransport) {
    private fun row(rs: ResultSet) = Server(
        rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("host"), rs.getInt("port"),
        rs.getString("username"), rs.getString("public_key"), rs.getString("host_key"),
        rs.getString("host_key")?.let { SshTransport.identity(it).fingerprint }, rs.getString("status"),
        rs.getString("message"), rs.getTimestamp("checked_at")?.toInstant(), rs.getTimestamp("created_at").toInstant()
    )
    fun list(): List<Server> = jdbc.query("SELECT * FROM servers ORDER BY created_at DESC, id") { rs, _ -> row(rs) }
    fun get(id: UUID): Server = jdbc.query("SELECT * FROM servers WHERE id = ?", { rs, _ -> row(rs) }, id).firstOrNull() ?: throw MissingResource()

    @Synchronized
    fun create(input: NewServer): Server {
        val name = input.name.trim()
        val host = input.host.trim().lowercase()
        val user = input.username.trim()
        require(name.length in 1..80 && name.none { it.isISOControl() }) { "Укажите название сервера, до 80 символов" }
        require(host.length in 1..253 && host.split('.').all { Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) }) { "Укажите IP или DNS-имя без ssh, пользователя, порта и пробелов" }
        require(input.port in 1..65535) { "Порт должен быть от 1 до 65535" }
        require(Regex("[a-z_][a-z0-9_-]{0,63}").matches(user)) { "Укажите имя SSH-пользователя" }
        list().firstOrNull { it.id == input.id }?.let {
            if (it.name != name || it.host != host || it.port != input.port || it.username != user) throw Conflict("Идентификатор уже использован другим подключением")
            return it
        }
        if (list().any { it.name == name || (it.host == host && it.port == input.port && it.username == user) }) throw Conflict("Такое имя или SSH-подключение уже добавлено")
        val publicKey = ssh.generate(input.id)
        try {
            jdbc.update("INSERT INTO servers(id, name, host, port, username, public_key) VALUES (?, ?, ?, ?, ?, ?)", input.id, name, host, input.port, user, publicKey)
        } catch (error: Exception) { ssh.remove(input.id); throw error }
        return get(input.id)
    }

    fun scan(id: UUID) = ssh.scan(get(id))
    fun trust(id: UUID, input: TrustHost): Server {
        SshTransport.identity(input.key)
        get(id)
        val changed = jdbc.update("UPDATE servers SET host_key = ? WHERE id = ? AND (host_key IS NULL OR host_key = ?)", input.key, id, input.key)
        if (changed != 1) throw Conflict("Ключ сервера уже закреплён и отличается. Проверьте причину смены ключа; для нового сервера создайте новое подключение.")
        return get(id)
    }

    fun check(id: UUID): Server {
        val result = ssh.check(get(id))
        val lines = result.output.lines().map { it.trim() }
        val connected = result.code == 0 && "INFRA_HUB_SSH_OK" in lines
        val ready = connected && "INFRA_HUB_DOCKER_OK" in lines && "INFRA_HUB_COMPOSE_OK" in lines
        val status = if (ready) "READY" else if (connected) "SSH_ONLY" else "FAILED"
        val message = when {
            ready -> "SSH, Docker и Compose доступны.\n${result.output}"
            connected -> "SSH доступен. Docker или Compose отсутствует либо недоступен этому пользователю.\n${result.output}"
            "REMOTE HOST IDENTIFICATION HAS CHANGED" in result.output || "Host key verification failed" in result.output -> "Ключ сервера не совпадает с подтверждённым. Подключение остановлено; проверьте сервер по доверенному каналу."
            "Permission denied" in result.output -> "Сервер отклонил ключ. Добавьте публичный ключ Infra Hub в authorized_keys выбранного пользователя и проверьте права каталога .ssh."
            else -> "Не удалось подключиться по SSH. Проверьте адрес, порт и доступ с сервера Infra Hub.\n${result.output}"
        }
        jdbc.update("UPDATE servers SET status = ?, message = ?, checked_at = clock_timestamp() WHERE id = ?", status, message.take(4000), id)
        return get(id)
    }
}

@RestController
@Profile("!worker")
@RequestMapping("/api/servers")
class ServerApi(private val service: ServerService, private val resources: ServerResources) {
    @GetMapping fun list() = service.list()
    @GetMapping("/{id}/resources") fun resources(@PathVariable id: UUID) = resources.read(id)
    @PostMapping("/{id}/resources") fun refreshResources(@PathVariable id: UUID) = resources.refresh(id)
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody input: NewServer) = service.create(input)
    @PostMapping("/{id}/host-key") fun scan(@PathVariable id: UUID) = service.scan(id)
    @PostMapping("/{id}/trust") fun trust(@PathVariable id: UUID, @RequestBody input: TrustHost) = service.trust(id, input)
    @PostMapping("/{id}/check") fun check(@PathVariable id: UUID) = service.check(id)
}
