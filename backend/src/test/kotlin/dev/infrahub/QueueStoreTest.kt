package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class QueueStoreTest @Autowired constructor(private val store: QueueStore, private val jdbc: JdbcTemplate) {
    @BeforeEach fun clear() {
        check(jdbc.queryForObject("SELECT current_database()", String::class.java) == "infra_hub_test") {
            "Integration tests require the isolated infra_hub_test database"
        }
        jdbc.execute("TRUNCATE build_logs, build_steps, builds, projects CASCADE")
    }
    private fun project() = store.createProject(NewProject("Test ${UUID.randomUUID()}"))

    @Test fun `actual runner resources and cache state persist and only the lease owner can record them`() {
        val build = store.enqueue(project().id, UUID.randomUUID(), SourceSnapshot("owner/project", "main", "a".repeat(40), "true"))
        val owner = UUID.randomUUID()
        store.claim(owner)
        val execution = RunnerExecution(12, 18, "AVAILABLE", "infra-hub-dependencies-v1-${build.projectId}")
        store.recordRunner(build.id, owner, execution)
        assertThatThrownBy { store.recordRunner(build.id, UUID.randomUUID(), execution.copy(cacheStatus = "DISABLED")) }.isInstanceOf(ExecutionStopped::class.java)
        store.finish(build.id, owner, BuildStatus.SUCCEEDED, "ok")
        assertThat(store.get(build.id).runnerExecution).isEqualTo(execution)
        assertThatThrownBy { store.recordRunner(build.id, owner, execution) }.isInstanceOf(ExecutionStopped::class.java)
    }

    @Test fun `repository snapshot survives retry with a changed branch and command`() {
        val project = project()
        val request = UUID.randomUUID()
        val source = SourceSnapshot("minpor/aukni", "dev", "a".repeat(40), "cd backend && sh gradlew build")
        val first = store.enqueue(project.id, request, source)
        val retry = store.enqueue(project.id, request, source.copy(sha = "b".repeat(40), command = "false"))
        assertThat(retry.id).isEqualTo(first.id)
        assertThat(retry.source).isEqualTo(source)
        assertThat(retry.recipe).isEqualTo("REPOSITORY_V1")
        assertThat(store.steps(first.id)).hasSize(2)
        assertThat(store.requested(project.id, request)?.source).isEqualTo(source)
    }

    @Test fun `parallel retries enqueue exactly once`() {
        val project = project()
        val request = UUID.randomUUID()
        Executors.newFixedThreadPool(6).use { pool ->
            val results = pool.invokeAll((1..12).map { Callable { store.enqueue(project.id, request) } }).map { it.get() }
            assertThat(results.map { it.id }.distinct()).hasSize(1)
            assertThat(store.steps(results.first().id)).hasSize(3)
            assertThat(store.builds()).hasSize(1)
        }
        assertThatThrownBy { store.enqueue(project().id, request) }.isInstanceOf(Conflict::class.java)
    }

    @Test fun `two workers share one execution lane`() {
        val project = project()
        repeat(3) { store.enqueue(project.id, UUID.randomUUID()) }
        Executors.newFixedThreadPool(2).use { pool ->
            val claimed = pool.invokeAll((1..2).map { Callable { store.claim(UUID.randomUUID()) } }).mapNotNull { it.get() }
            assertThat(claimed).hasSize(1)
        }
        assertThat(store.builds().count { it.status == BuildStatus.QUEUED }).isEqualTo(2)
    }

    @Test fun `cancel queued work prevents execution`() {
        val build = store.enqueue(project().id, UUID.randomUUID())
        assertThat(store.cancel(build.id).status).isEqualTo(BuildStatus.CANCELLED)
        assertThat(store.claim(UUID.randomUUID())).isNull()
        assertThat(store.steps(build.id).map { it.status }).containsOnly("CANCELLED")
    }

    @Test fun `cancel request wins over concurrent success`() {
        val build = store.enqueue(project().id, UUID.randomUUID())
        val worker = UUID.randomUUID()
        store.claim(worker)
        store.startStep(build.id, worker, 0)
        store.cancel(build.id)
        assertThat(store.heartbeat(build.id, worker)).isEqualTo(BuildStatus.CANCEL_REQUESTED)
        store.finish(build.id, worker, BuildStatus.SUCCEEDED, "Done")
        assertThat(store.get(build.id).status).isEqualTo(BuildStatus.CANCELLED)
        assertThat(store.steps(build.id).map { it.status }).containsOnly("CANCELLED")
    }

    @Test fun `expired lease is interrupted and stale writes cannot revive it`() {
        val build = store.enqueue(project().id, UUID.randomUUID())
        val worker = UUID.randomUUID()
        store.claim(worker)
        store.startStep(build.id, worker, 0)
        jdbc.update("UPDATE builds SET lease_until = clock_timestamp() - interval '1 second' WHERE id = ?", build.id)
        assertThat(store.heartbeat(build.id, worker)).isNull()
        store.expire()
        store.appendLog(build.id, worker, 0, "Late output")
        store.completeStep(build.id, worker, 0, 0)
        store.finish(build.id, worker, BuildStatus.SUCCEEDED, "Late success")
        assertThat(store.get(build.id).status).isEqualTo(BuildStatus.INTERRUPTED)
        assertThat(store.steps(build.id).map { it.status }).containsOnly("INTERRUPTED")
        assertThat(store.logs(build.id, 0)).isEmpty()
        assertThat(store.claim(UUID.randomUUID())).isNull()
    }

    @Test fun `foreign worker cannot write to active job`() {
        val build = store.enqueue(project().id, UUID.randomUUID())
        store.claim(UUID.randomUUID())
        val other = UUID.randomUUID()
        assertThat(store.startStep(build.id, other, 0)).isFalse()
        store.appendLog(build.id, other, 0, "not mine")
        store.finish(build.id, other, BuildStatus.SUCCEEDED, "not mine")
        assertThat(store.logs(build.id, 0)).isEmpty()
        assertThat(store.get(build.id).status).isEqualTo(BuildStatus.RUNNING)
    }

    @Test fun `failed step is preserved and remaining steps are cancelled`() {
        val build = store.enqueue(project().id, UUID.randomUUID())
        val worker = UUID.randomUUID()
        store.claim(worker)
        store.startStep(build.id, worker, 0)
        store.completeStep(build.id, worker, 0, 23)
        store.finish(build.id, worker, BuildStatus.FAILED, "Compiler failed")
        assertThat(store.get(build.id).status).isEqualTo(BuildStatus.FAILED)
        assertThat(store.steps(build.id).map { it.status }).containsExactly("FAILED", "CANCELLED", "CANCELLED")
        assertThat(store.steps(build.id).first().exitCode).isEqualTo(23)
    }
}
