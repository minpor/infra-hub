package dev.infrahub

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Path
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class ServerResourcesTest @Autowired constructor(private val servers: ServerService, private val resources: ServerResources) {
    @MockitoBean lateinit var ssh: SshTransport

    @Test fun `failed and invalid probes retain last successful observation with stale evidence`() {
        val id = UUID.randomUUID()
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr"
        `when`(ssh.generate(id)).thenReturn("$key infra-hub:$id")
        servers.create(NewServer(id, "Resources $id", "resources-$id.example", username = "deploy"))
        val server = servers.trust(id, TrustHost(key))
        val value = HostResources("fixture", "Test Linux", "x86_64", 4, 25.0, 8192, 4096, 16384, 2048, 0.5, 3600)
        `when`(ssh.resources(server)).thenReturn(SshResult(0, jacksonObjectMapper().writeValueAsString(value)))
        val success = resources.refresh(id)
        assertThat(success.value).isEqualTo(value)
        assertThat(success.observedAt).isNotNull()
        assertThat(success.error).isNull()
        `when`(ssh.resources(server)).thenReturn(SshResult(255, "connection lost"))
        val failed = resources.refresh(id)
        assertThat(failed.value).isEqualTo(value)
        assertThat(failed.observedAt).isEqualTo(success.observedAt)
        assertThat(failed.attemptedAt).isAfterOrEqualTo(success.attemptedAt)
        assertThat(failed.error).isNotNull()
        `when`(ssh.resources(server)).thenReturn(SshResult(0, jacksonObjectMapper().writeValueAsString(value.copy(memoryUsedBytes = -1))))
        assertThat(resources.refresh(id).value).isEqualTo(value)
        assertThat(resources.read(id).error).isNotNull()
    }
}

class HostResourceProbeTest {
    @TempDir lateinit var directory: Path
    @Test fun `fixed read only probe runs on Linux and yields valid numeric measurements`() {
        val script = javaClass.getResource("/server-resources.py")!!.readText()
        val result = SshTransport(directory.toString()).run(listOf("python3", "-c", script))
        assertThat(result.code).isZero()
        val value = jacksonObjectMapper().readValue(result.output, HostResources::class.java).validate()
        assertThat(value.cpuCount).isPositive()
        assertThat(value.memoryTotalBytes).isPositive()
        assertThat(value.diskTotalBytes).isPositive()
    }
}
