package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class PipelinesTest @Autowired constructor(private val pipelines: PipelineService, private val apps: DeploymentStore, private val queue: QueueStore, private val jdbc: JdbcTemplate) {
    @MockitoBean lateinit var github: GitHub
    private lateinit var app: HubApplication
    private lateinit var project: Project
    @BeforeEach fun prepare() {
        check(jdbc.queryForObject("SELECT current_database()", String::class.java) == "infra_hub_test")
        jdbc.execute("TRUNCATE projects,servers CASCADE")
        project = queue.createProject(NewProject("Aukni fixture", "https://github.com/owner/repo"))
        val server = UUID.randomUUID()
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr"
        jdbc.update("INSERT INTO servers(id,name,host,port,username,public_key,host_key,status) VALUES (?,'Test','server.test',22,'deploy',?,?,'READY')", server, key, key)
        app = apps.create(NewApplication(UUID.randomUUID(), project.id, server, "Existing", 18090, 8080, "/api/health", kind = "COMPOSE", configuration = mapOf(
            "root" to "/opt/fixture", "composeFile" to "docker-compose.yml", "service" to "app", "webService" to "", "proxy" to "", "site" to "/opt/fixture/site", "url" to "https://fixture.example", "ref" to "main"
        )))
    }
    // tick() returns before its virtual thread completes. Retry a tick after the
    // preceding pass becomes idle instead of assuming a fixed sleep is sufficient.
    private fun awaitPipeline(assertion: () -> Unit) {
        await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(10)).untilAsserted {
            pipelines.tick()
            assertion()
        }
    }

    @Test fun `managed app keeps environment remote and gates source branch`() {
        assertThat(app.hostPort).isNull()
        val input = NewPipeline(UUID.randomUUID(), app.id, "dev", "build", "app.jar")
        assertThatThrownBy { pipelines.create(input) }.isInstanceOf(IllegalArgumentException::class.java)
        val b = queue.enqueue(project.id, UUID.randomUUID(), SourceSnapshot("owner/repo", "dev", "a".repeat(40), "build"))
        jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='image@sha256:abc',artifact_sha256=repeat('a',64),artifact_size=1 WHERE id=?", b.id)
        assertThatThrownBy { apps.enqueue(app.id, NewDeployment(UUID.randomUUID(), b.id)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `disabled pipeline previews immutable GitHub source without deploying`() {
        val id = UUID.randomUUID()
        pipelines.create(NewPipeline(id, app.id, "main", "build", "app.jar"))
        `when`(github.resolve("https://github.com/owner/repo", "main", "build")).thenReturn(SourceSnapshot("owner/repo", "main", "b".repeat(40), "build"))
        val b = pipelines.start(id)
        assertThat(b.source?.sha).isEqualTo("b".repeat(40))
        assertThat(b.source?.artifactPath).isEqualTo("app.jar")
        jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='image@sha256:abc',artifact_sha256=repeat('a',64),artifact_size=1 WHERE id=?", b.id)
        pipelines.tick()
        Thread.sleep(300)
        assertThat(apps.list()).isEmpty()
        pipelines.enabled(id, true)
        awaitPipeline { assertThat(apps.list()).hasSize(1) }
        assertThat(apps.list().single().buildId).isEqualTo(b.id)
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "main", "master"])
    fun `enabled branch automatically builds a new exact commit after the previous release succeeds`(ref: String) {
        val selected = apps.create(NewApplication(UUID.randomUUID(), project.id, app.serverId, "Auto $ref", 18090, 8080, "/api/health", kind = "COMPOSE", configuration = app.configuration + mapOf("root" to "/opt/auto-$ref", "ref" to ref)))
        val id = UUID.randomUUID()
        pipelines.create(NewPipeline(id, selected.id, ref, "build", "app.jar", enabled = true))
        val source = SourceSnapshot("owner/repo", ref, "c".repeat(40), "build")
        `when`(github.resolve("https://github.com/owner/repo", ref, "build")).thenReturn(source)
        awaitPipeline { assertThat(pipelines.list().single()["build_id"]).isNotNull() }
        val first = pipelines.list().single()["build_id"] as UUID
        assertThat(queue.get(first).source?.sha).isEqualTo(source.sha)
        jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='image@sha256:abc',artifact_sha256=repeat('a',64),artifact_size=1 WHERE id=?", first)
        awaitPipeline { assertThat(apps.list()).hasSize(1) }
        val owner = UUID.randomUUID()
        val release = apps.claim(owner)!!
        assertThat(release.applicationId).isEqualTo(selected.id)
        apps.finish(release.id, owner, "SUCCEEDED", "Ready")
        `when`(github.resolve("https://github.com/owner/repo", ref, "build")).thenReturn(source.copy(sha = "d".repeat(40)))
        jdbc.update("UPDATE pipelines SET checked_at=clock_timestamp()-interval '2 minutes' WHERE id=?", id)
        awaitPipeline { assertThat(pipelines.list().single()["build_id"]).isNotEqualTo(first) }
        val next = queue.get(pipelines.list().single()["build_id"] as UUID)
        assertThat(next.id).isNotEqualTo(first)
        assertThat(next.source?.sha).isEqualTo("d".repeat(40))
        assertThat(next.source?.ref).isEqualTo(ref)
    }
}
