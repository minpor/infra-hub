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
class PipelinesTest @Autowired constructor(private val pipelines: PipelineService, private val apps: DeploymentStore, private val queue: QueueStore, private val jdbc: JdbcTemplate, private val configuration: PipelineConfigurationService, private val editing: RepositorySourceEditing) {
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
    @Test fun `configuration inspection shows actual commands and platform scripts without starting work or exposing environment values`() {
        val selected = apps.create(NewApplication(UUID.randomUUID(), project.id, app.serverId, "Inspect", 18091, 8080, environment = mapOf("TOKEN" to "private-value")))
        val id = UUID.randomUUID()
        val command = "python3 /opt/hub/build-aukni-web.py"
        pipelines.create(NewPipeline(id, selected.id, "main", command, "app.jar", environment = "prod"))
        val view = configuration.view(id)
        assertThat(view.repositoryYaml).isNull()
        assertThat(view.yaml).contains(command, "environment: prod", "TOKEN").doesNotContain("private-value")
        assertThat(view.files).contains(ConfigurationFile(".github/workflows/ci.yml", "REPOSITORY", "build"))
        val script = configuration.source(id, "BUILDER", "/opt/hub/build-aukni-web.py", null)
        assertThat(script.content).contains("workflow['jobs']['landing']['steps']", "build','shadowJar")
        assertThat(script.sha256).hasSize(64)
        assertThat(configuration.source(id, "DEPLOYER", "deploy/apply.py", null).content).contains("import")
        assertThat(queue.builds()).isEmpty()
        assertThat(apps.list()).isEmpty()
    }

    @Test fun `repository source preview stays on the selected snapshot and rejects host paths and non source files`() {
        val id = UUID.randomUUID()
        pipelines.create(NewPipeline(id, app.id, "main", "build", "app.jar"))
        val source = SourceSnapshot("owner/repo", "main", "a".repeat(40), ":")
        `when`(github.resolve("https://github.com/owner/repo", "main", ":")).thenReturn(source)
        `when`(github.file(source, ".github/workflows/ci.yml")).thenReturn("run: bash deploy/check.sh\n".toByteArray())
        val first = configuration.source(id, "REPOSITORY", ".github/workflows/ci.yml", null)
        assertThat(first.sha).isEqualTo(source.sha)
        assertThat(first.references).contains(ConfigurationFile("deploy/check.sh", "REPOSITORY", "source"))
        `when`(github.resolve("https://github.com/owner/repo", "main", ":")).thenThrow(RepositoryFailure("branch moved"))
        `when`(github.file(source, "deploy/check.sh")).thenReturn("printf verified\n".toByteArray())
        assertThat(configuration.source(id, "REPOSITORY", "deploy/check.sh", first.sha).content).isEqualTo("printf verified\n")
        listOf("../secret.py", "/etc/secret.py", ".env", "folder/../script.sh").forEach { path ->
            assertThatThrownBy { configuration.source(id, "REPOSITORY", path, null) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { configuration.source(id, "BUILDER", "/opt/hub/../private-key.pem", null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { configuration.source(id, "DEPLOYER", "deploy/../../application-production.yml", null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `source edits validate YAML and snapshot before writes and preserve native settings on conflicts`() {
        val id = UUID.randomUUID()
        pipelines.create(NewPipeline(id, app.id, "main", "build", "app.jar"))
        val source = SourceSnapshot("owner/repo", "main", "b".repeat(40), ":")
        `when`(github.resolve("https://github.com/owner/repo", "main", ":")).thenReturn(source)
        assertThatThrownBy { editing.save(id, SourceFileWrite(".infra-hub/custom.yaml", "broken: [", source.sha, true)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { editing.save(id, SourceFileWrite(".infra-hub/custom.sh", "printf ok", "a".repeat(40), true)) }.isInstanceOf(Conflict::class.java)
        org.mockito.Mockito.verify(github, org.mockito.Mockito.never()).saveFile(source, ".infra-hub/custom.yaml", "broken: [", true)
        org.mockito.Mockito.verify(github, org.mockito.Mockito.never()).saveFile(source, ".infra-hub/custom.sh", "printf ok", true)
        `when`(github.saveFile(source, ".infra-hub/custom.sh", "printf ok", true)).thenReturn("c".repeat(40))
        val saved = editing.save(id, SourceFileWrite(".infra-hub/custom.sh", "printf ok", source.sha, true))
        assertThat(saved.sha).isEqualTo("c".repeat(40))
        assertThat(saved.content).isEqualTo("printf ok")
        assertThat(queue.builds()).isEmpty()
        editing.buildSettings(id, NativeBuildSettings("bash .infra-hub/custom.sh", "app.jar", "build", "app.jar"))
        assertThat(configuration.view(id).command).isEqualTo("bash .infra-hub/custom.sh")
        assertThatThrownBy { editing.buildSettings(id, NativeBuildSettings("overwrite", "app.jar", "build", "app.jar")) }.isInstanceOf(Conflict::class.java)
        assertThat(configuration.view(id).command).isEqualTo("bash .infra-hub/custom.sh")
        assertThat(pipelines.list().single()["enabled"]).isEqualTo(false)
    }
    @Test fun `environment is explicit and a paused native pipeline supports an idempotent one time release`() {
        val id = UUID.randomUUID()
        pipelines.create(NewPipeline(id, app.id, "main", "build", "app.jar"))
        assertThat(pipelines.list().single()["environment"]).isNull()
        pipelines.environment(id, "prod")
        assertThat(pipelines.list().single()["environment"]).isEqualTo("prod")
        assertThat(pipelines.create(NewPipeline(id, app.id, "main", "build", "app.jar"))["environment"]).isEqualTo("prod")
        assertThatThrownBy { pipelines.environment(id, "staging") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { pipelines.release(id, UUID.randomUUID(), "main", "dev") }.isInstanceOf(Conflict::class.java)
        assertThatThrownBy { pipelines.release(id, UUID.randomUUID(), "dev", "prod") }.isInstanceOf(Conflict::class.java)
        assertThat(queue.builds()).isEmpty()
        val source = SourceSnapshot("owner/repo", "main", "e".repeat(40), "build")
        `when`(github.resolve("https://github.com/owner/repo", "main", "build")).thenReturn(source)
        val request = UUID.randomUUID()
        val build = pipelines.release(id, request, "main", "prod")
        `when`(github.resolve("https://github.com/owner/repo", "main", "build")).thenThrow(RepositoryFailure("offline"))
        assertThat(pipelines.release(id, request, "main", "prod").id).isEqualTo(build.id)
        assertThat(queue.builds()).hasSize(1)
        assertThat(build.source?.artifactPath).isEqualTo("app.jar")
        assertThat(pipelines.list().single()["enabled"]).isEqualTo(false)
        jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='image@sha256:abc',artifact_sha256=repeat('a',64),artifact_size=1 WHERE id=?", build.id)
        awaitPipeline { assertThat(apps.list()).hasSize(1) }
        assertThat(apps.list().single().buildId).isEqualTo(build.id)
        pipelines.environment(id, null)
        assertThat(pipelines.list().single()["environment"]).isNull()
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
    @ValueSource(strings = ["FAILED", "CANCELLED"])
    fun `new explicit native release retries a terminal deployment while the old request remains idempotent`(status: String) {
        val id = UUID.randomUUID()
        pipelines.create(NewPipeline(id, app.id, "main", "build", "app.jar", environment = "prod"))
        `when`(github.resolve("https://github.com/owner/repo", "main", "build")).thenReturn(SourceSnapshot("owner/repo", "main", "f".repeat(40), "build"))
        val request = UUID.randomUUID()
        val first = pipelines.release(id, request, "main", "prod")
        jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='image@sha256:abc',artifact_sha256=repeat('a',64),artifact_size=1 WHERE id=?", first.id)
        val deployment = apps.enqueue(app.id, NewDeployment(UUID.randomUUID(), first.id))
        jdbc.update("UPDATE deployments SET status=? WHERE id=?", status, deployment.id)
        jdbc.update("UPDATE pipelines SET deployment_id=? WHERE id=?", deployment.id, id)
        assertThat(pipelines.release(id, request, "main", "prod").id).isEqualTo(first.id)
        val retry = pipelines.release(id, UUID.randomUUID(), "main", "prod")
        assertThat(retry.id).isNotEqualTo(first.id)
        assertThat(retry.source).isEqualTo(first.source)
        assertThat(pipelines.list().single()["deployment_id"]).isNull()
        assertThat(pipelines.list().single()["enabled"]).isEqualTo(false)
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
