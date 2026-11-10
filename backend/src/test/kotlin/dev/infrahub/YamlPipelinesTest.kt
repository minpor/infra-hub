package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class YamlPipelinesTest @Autowired constructor(private val pipelines: PipelineService, private val apps: DeploymentStore, private val queue: QueueStore, private val jdbc: JdbcTemplate, private val sources: YamlSources) {
    @MockitoBean lateinit var github: GitHub
    private lateinit var app: HubApplication
    private lateinit var project: Project
    private val source = SourceSnapshot("owner/yaml", "main", "a".repeat(40), ":")
    private val yaml = """
        version: 1
        name: Custom release
        build:
          steps:
            - name: Test
              run: printf ok
        deploy:
          directory: /srv/custom
          steps:
            - name: Deploy
              run: ./deploy.sh
          verify:
            - name: Check
              run: ./check.sh
    """.trimIndent()
    @BeforeEach fun prepare() {
        check(jdbc.queryForObject("SELECT current_database()", String::class.java) == "infra_hub_test")
        jdbc.execute("TRUNCATE projects,servers CASCADE")
        project = queue.createProject(NewProject("YAML", "https://github.com/owner/yaml"))
        val server = UUID.randomUUID()
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr"
        jdbc.update("INSERT INTO servers(id,name,host,port,username,public_key,host_key,status) VALUES (?,'YAML','yaml.test',22,'deploy',?,?,'SSH_ONLY')", server, key, key)
        app = apps.create(NewApplication(UUID.randomUUID(), project.id, server, "Generic target", 0, 8080, environment = mapOf("TOKEN" to "private-runtime-only"), kind = "SCRIPT"))
        `when`(github.resolve("https://github.com/owner/yaml", "main", ":")).thenReturn(source)
    }
    private fun register(path: String): UUID {
        `when`(github.file(source, path)).thenReturn(yaml.toByteArray())
        val id = UUID.randomUUID()
        pipelines.registerYaml(YamlBinding(id, app.id, "main", path, source.sha))
        return id
    }

    @Test fun `editor loads invalid YAML for repair and validates before any repository write`() {
        val path = ".infra-hub/production.yaml"
        `when`(github.file(source, path)).thenReturn("build: [broken".toByteArray())
        assertThat(sources.document(YamlPreviewRequest(app.id, "main", path)).yaml).isEqualTo("build: [broken")
        assertThatThrownBy { sources.save(YamlSaveRequest(app.id, "main", path, source.sha, "build: [broken")) }.isInstanceOf(IllegalArgumentException::class.java)
        verify(github, never()).saveYaml(source, path, "build: [broken")
        assertThatThrownBy { sources.save(YamlSaveRequest(app.id, "main", path, "b".repeat(40), yaml)) }.isInstanceOf(Conflict::class.java)
        verify(github, never()).saveYaml(source, path, yaml)
        `when`(github.saveYaml(source, path, yaml)).thenReturn("c".repeat(40))
        val saved = sources.save(YamlSaveRequest(app.id, "main", path, source.sha, yaml))
        assertThat(saved.sha).isEqualTo("c".repeat(40))
        assertThat(saved.yaml).isEqualTo(yaml)
        assertThat(queue.builds()).isEmpty()
        assertThat(pipelines.list()).isEmpty()
    }
    @Test fun `ten independent YAML files bind one application and only one may automate it`() {
        val ids = (1..10).map { register(".infra-hub/release-$it.yaml") }
        assertThat(pipelines.list()).hasSize(10)
        pipelines.enabled(ids.first(), true)
        assertThatThrownBy { pipelines.enabled(ids.last(), true) }.isInstanceOf(Conflict::class.java)
        pipelines.enabled(ids.first(), false)
        pipelines.enabled(ids.last(), true)
        assertThat(pipelines.list().count { it["enabled"] == true }).isEqualTo(1)
    }
    @Test fun `exact YAML is frozen with build and deploy retries retain original runtime environment`() {
        val id = register(".infra-hub/production.yaml")
        val request = UUID.randomUUID()
        val build = pipelines.release(id, request)
        `when`(github.file(source, ".infra-hub/production.yaml")).thenReturn(yaml.replace("./deploy.sh", "./changed.sh").toByteArray())
        assertThat(pipelines.release(id, request).id).isEqualTo(build.id)
        assertThat(queue.get(build.id).source!!.yamlRecipe!!.yaml).contains("./deploy.sh").doesNotContain("./changed.sh")
        jdbc.update("UPDATE builds SET status='SUCCEEDED' WHERE id=?", build.id)
        val deployment = apps.enqueue(app.id, NewDeployment(UUID.randomUUID(), build.id))
        assertThat(deployment.imageReference).isEqualTo("source:${source.sha}")
        assertThat(apps.yamlExecution(deployment.id)!!.environment["TOKEN"]).isEqualTo("private-runtime-only")
        jdbc.update("UPDATE applications SET environment='{}'::jsonb WHERE id=?", app.id)
        assertThat(apps.yamlExecution(deployment.id)!!.environment["TOKEN"]).isEqualTo("private-runtime-only")
        assertThat(deployment.toString()).doesNotContain("private-runtime-only")
    }
    @Test fun `preview drift and attaching a build to another target are rejected`() {
        val path = ".infra-hub/dev.yaml"
        `when`(github.file(source, path)).thenReturn(yaml.toByteArray())
        assertThatThrownBy { pipelines.registerYaml(YamlBinding(UUID.randomUUID(), app.id, "main", path, "b".repeat(40))) }.isInstanceOf(Conflict::class.java)
        val id = register(path)
        val build = pipelines.start(id)
        jdbc.update("UPDATE builds SET status='SUCCEEDED' WHERE id=?", build.id)
        val other = apps.create(NewApplication(UUID.randomUUID(), project.id, app.serverId, "Other", 0, 8080, kind = "SCRIPT"))
        assertThatThrownBy { apps.enqueue(other.id, NewDeployment(UUID.randomUUID(), build.id)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `release request stays idempotent across failures while a new request can rerun`() {
        val id = register(".infra-hub/retry.yaml")
        val request = UUID.randomUUID()
        val first = pipelines.release(id, request)
        jdbc.update("UPDATE builds SET status='FAILED' WHERE id=?", first.id)
        assertThat(pipelines.release(id, request).id).isEqualTo(first.id)
        val next = pipelines.release(id, UUID.randomUUID())
        assertThat(next.id).isNotEqualTo(first.id)
        jdbc.update("UPDATE builds SET status='SUCCEEDED' WHERE id=?", next.id)
        val deployment = apps.enqueue(app.id, NewDeployment(UUID.randomUUID(), next.id))
        jdbc.update("UPDATE deployments SET status='FAILED' WHERE id=?", deployment.id)
        jdbc.update("UPDATE pipelines SET deployment_id=? WHERE id=?", deployment.id, id)
        val rerun = pipelines.release(id, UUID.randomUUID())
        assertThat(rerun.id).isNotEqualTo(next.id)
    }

}
