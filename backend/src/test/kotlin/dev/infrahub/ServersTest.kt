package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class ServersTest @Autowired constructor(private val service: ServerService) {
    @MockitoBean lateinit var ssh: SshTransport
    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr"

    @Test fun `retry keeps key and pinned identity cannot be overwritten`() {
        val id = UUID.randomUUID()
        `when`(ssh.generate(id)).thenReturn("$key infra-hub:$id")
        val input = NewServer(id, "Test $id", "test-$id.example.com", 22, "deploy")
        val first = service.create(input)
        assertThat(service.create(input)).isEqualTo(first)
        val trusted = service.trust(id, TrustHost(key))
        assertThat(trusted.fingerprint).startsWith("SHA256:")
        val other = key.dropLast(1) + "s"
        assertThatThrownBy { service.trust(id, TrustHost(other)) }.isInstanceOf(Conflict::class.java)
        assertThat(service.get(id).hostKey).isEqualTo(key)
        `when`(ssh.check(trusted)).thenReturn(SshResult(0, "INFRA_HUB_SSH_OK\nINFRA_HUB_DOCKER_OK\nINFRA_HUB_COMPOSE_OK\n"))
        assertThat(service.check(id).status).isEqualTo("READY")
        val ready = service.get(id)
        `when`(ssh.check(ready)).thenReturn(SshResult(255, "Host key verification failed"))
        assertThat(service.check(id).status).isEqualTo("FAILED")
        assertThat(service.get(id).message).contains("не совпадает")
        assertThat(service.get(id).checkedAt).isNotNull()
    }

    @Test fun `unsafe addresses and usernames are rejected before execution`() {
        listOf("-oProxyCommand=evil", "host;touch /tmp/evil", "host\nother", "root@host", "host,other", "host/24").forEach { host ->
            assertThatThrownBy { service.create(NewServer(UUID.randomUUID(), "bad", host, 22, "deploy")) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { service.create(NewServer(UUID.randomUUID(), "bad", "example.com", 22, "-root")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.create(NewServer(UUID.randomUUID(), "bad", "example.com", 0, "root")) }.isInstanceOf(IllegalArgumentException::class.java)
        verifyNoInteractions(ssh)
    }
}

class SshTransportTest {
    @TempDir lateinit var dir: Path

    @Test fun `dedicated private key is protected and scan fingerprint matches OpenSSH`() {
        val transport = SshTransport(dir.toString())
        val id = UUID.randomUUID()
        val publicKey = transport.generate(id)
        assertThat(publicKey).startsWith("ssh-ed25519 ").endsWith("infra-hub:$id")
        assertThat(Files.getPosixFilePermissions(dir.resolve("$id/identity"))).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(Files.getPosixFilePermissions(dir.resolve(id.toString()))).isEqualTo(PosixFilePermissions.fromString("rwx------"))
        val fingerprint = SshTransport.identity(publicKey.split(' ').take(2).joinToString(" ")).fingerprint
        assertThat(transport.run(listOf("ssh-keygen", "-lf", dir.resolve("$id/identity.pub").toString(), "-E", "sha256")).output).contains(fingerprint)
        transport.remove(id)
        assertThat(Files.exists(dir.resolve(id.toString()))).isFalse()
    }

    @Test fun `check requires host pin before using key`() {
        val server = Server(UUID.randomUUID(), "test", "127.0.0.1", 22, "deploy", "", null, null, "PENDING", null, null, Instant.now())
        assertThatThrownBy { SshTransport(dir.toString()).check(server) }.isInstanceOf(Conflict::class.java)
        assertThatThrownBy { SshTransport.identity("ssh-ed25519 AAAA\nmalicious") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
