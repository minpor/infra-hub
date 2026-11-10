package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

class WorkerSourcesTest {
    private val store = mock(QueueStore::class.java)
    private val github = mock(GitHub::class.java)
    private val secret = "test-worker-secret-at-least-32-characters"
    private val controller = WorkerSources(store, github, secret)
    private val id = UUID.randomUUID()
    private val worker = UUID.randomUUID()
    private val source = SourceSnapshot("minpor/aukni", "dev", "a".repeat(40), "true")

    @Test fun `invalid credentials or foreign lease never reach GitHub`() {
        assertThatThrownBy { controller.source(id, worker, "wrong") }.isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { controller.source(id, worker, secret) }.isInstanceOf(ResponseStatusException::class.java)
        verifyNoInteractions(github)
    }

    @Test fun `source is delivered only while the same worker still owns the lease`() {
        `when`(store.ownsLease(id, worker)).thenReturn(true)
        `when`(store.get(id)).thenReturn(Build(id, UUID.randomUUID(), "REPOSITORY_V1", BuildStatus.RUNNING, Instant.now(), null, null, null, source))
        `when`(github.archive(source)).thenReturn(byteArrayOf(1, 2, 3))
        assertThat(controller.source(id, worker, secret).body).containsExactly(1, 2, 3)
        `when`(store.ownsLease(id, worker)).thenReturn(true, false)
        assertThatThrownBy { controller.source(id, worker, secret) }.isInstanceOf(ResponseStatusException::class.java)
    }
}
