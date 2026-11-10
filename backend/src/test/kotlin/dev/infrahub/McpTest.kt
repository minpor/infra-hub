package dev.infrahub

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@ActiveProfiles("production")
@AutoConfigureMockMvc
@SpringBootTest(properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}", "spring.security.oauth2.client.registration.github.client-id=test", "spring.security.oauth2.client.registration.github.client-secret=test", "HUB_PUBLIC_URL=https://infra-hub.minpor.ru", "hub.owner-github-id=7196546"])
class McpTest @Autowired constructor(private val mvc: MockMvc, private val mapper: ObjectMapper, private val jdbc: JdbcTemplate,
    private val access: McpAccess, private val queue: QueueStore, private val apps: DeploymentStore, private val pipelines: PipelineService, private val runner: RunnerSettings) {
    @MockitoBean lateinit var github: GitHub
    private lateinit var project: Project
    private lateinit var other: Project
    private lateinit var app: HubApplication
    private lateinit var pipeline: UUID
    @BeforeEach fun prepare() {
        check(jdbc.queryForObject("SELECT current_database()", String::class.java) == "infra_hub_test")
        jdbc.execute("TRUNCATE projects,servers,mcp_tokens CASCADE")
        project = queue.createProject(NewProject("MCP project", "https://github.com/owner/repo"))
        other = queue.createProject(NewProject("Hidden project", "https://github.com/owner/hidden"))
        val server = UUID.randomUUID()
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr"
        jdbc.update("INSERT INTO servers(id,name,host,port,username,public_key,host_key,status) VALUES (?,'Fixture','fixture.example',22,'deploy',?,?,'READY')", server, key, key)
        app = apps.create(NewApplication(UUID.randomUUID(), project.id, server, "Fixture", 18090, 8080, "/api/health", kind = "COMPOSE", configuration = mapOf("root" to "/opt/fixture", "composeFile" to "compose.yaml", "service" to "app", "webService" to "", "proxy" to "", "site" to "/opt/fixture/site", "url" to "https://fixture.example", "ref" to "dev")))
        pipeline = UUID.randomUUID()
        pipelines.create(NewPipeline(pipeline, app.id, "dev", "build", "app.jar"))
        `when`(github.resolve(project.repositoryUrl!!, "dev", "build")).thenReturn(SourceSnapshot("owner/repo", "dev", "a".repeat(40), "build"))
    }
    private fun token(permissions: Set<String> = setOf("READ"), projects: Set<UUID>? = setOf(project.id)): String = access.create(NewMcpToken("Hermes test", permissions, projects))["token"] as String
    private fun rpc(secret: String, method: String, params: Any = emptyMap<String, Any>()): JsonNode {
        val response = mvc.post("/mcp") {
            header("Authorization", "Bearer $secret"); header("Accept", "application/json, text/event-stream")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        }.andExpect { status { isOk() } }.andReturn().response
        return mapper.readTree(response.contentAsString)
    }
    private fun call(secret: String, name: String, args: Map<String, Any> = emptyMap()): JsonNode = rpc(secret, "tools/call", mapOf("name" to name, "arguments" to args))["result"]
    private fun published(build: Build) { jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='image@sha256:abc',artifact_sha256=repeat('a',64),artifact_size=1 WHERE id=?", build.id) }

    @Test fun `initialize resources prompts project scope and least privilege are interoperable`() {
        val secret = token()
        val init = rpc(secret, "initialize", mapOf("protocolVersion" to "2025-11-25"))["result"]
        assertThat(init["protocolVersion"].asText()).isEqualTo("2025-11-25")
        assertThat(init["instructions"].asText()).contains("40-character SHA")
        val names = rpc(secret, "tools/list")["result"]["tools"].map { it["name"].asText() }
        assertThat(names).contains("hub_catalog", "hub_get_deployment").doesNotContain("hub_configure_runner", "hub_ensure_release")
        val catalog = call(secret, "hub_catalog")["structuredContent"]
        assertThat(catalog["projects"].map { it["id"].asText() }).containsExactly(project.id.toString())
        assertThat(catalog.toString()).doesNotContain("Hidden project", "private_key", secret)
        val hidden = queue.enqueue(other.id, UUID.randomUUID())
        assertThat(call(secret, "hub_get_build", mapOf("build_id" to hidden.id))["structuredContent"]["error"]["code"].asText()).isEqualTo("NOT_FOUND")
        assertThat(call(secret, "hub_cancel_build", mapOf("build_id" to hidden.id, "request_id" to UUID.randomUUID()))["structuredContent"]["error"]["code"].asText()).isEqualTo("FORBIDDEN")
        assertThat(rpc(secret, "resources/read", mapOf("uri" to "infra-hub://release-guide"))["result"]["contents"][0]["text"].asText()).contains("MCP project").doesNotContain("Hidden project")
        assertThat(rpc(secret, "prompts/get", mapOf("name" to "release_workflow"))["result"]["messages"].size()).isEqualTo(1)
        assertThat(access.tokens().toString()).doesNotContain(secret, mcpHash(secret))
        val identity = access.authenticate(secret)!!
        assertThat(jdbc.queryForObject("SELECT token_hash FROM mcp_tokens WHERE id=?", String::class.java, identity.id)).isEqualTo(mcpHash(secret))
        access.revoke(identity.id)
        mvc.post("/mcp") { header("Authorization", "Bearer $secret") }.andExpect { status { isUnauthorized() } }
    }
    @Test fun `machine bearer does not replace owner session and origin CSRF protocol checks remain enforced`() {
        val secret = token()
        mvc.get("/api/mcp-settings").andExpect { status { isUnauthorized() } }
        mvc.post("/api/mcp-settings/tokens") { with(oauth2Login()); contentType = MediaType.APPLICATION_JSON; content = "{}" }.andExpect { status { isForbidden() } }
        mvc.post("/mcp") { with(oauth2Login()) }.andExpect { status { isUnauthorized() } }
        mvc.get("/api/projects") { header("Authorization", "Bearer $secret") }.andExpect { status { isUnauthorized() } }
        mvc.post("/mcp") { header("Authorization", "Bearer $secret"); header("Origin", "https://attacker.example") }.andExpect { status { isForbidden() } }
        mvc.get("/mcp") { header("Authorization", "Bearer $secret") }.andExpect { status { isMethodNotAllowed() } }
        mvc.post("/mcp") {
            header("Authorization", "Bearer $secret"); header("Accept", "application/json, text/event-stream"); contentType = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        }.andExpect { status { isAccepted() } }
        assertThat(rpc(secret, "not-a-method")["error"]["code"].asInt()).isEqualTo(-32601)
    }
    @Test fun `configuration has capacity validation scoped permission and retry idempotency`() {
        val original = runner.resources()
        try {
            val scoped = token(setOf("READ", "RELEASE", "CONFIGURE"))
            assertThat(call(scoped, "hub_configure_runner", mapOf("cpus" to 8, "memory_gib" to 16, "request_id" to UUID.randomUUID()))["isError"].asBoolean()).isTrue()
            val secret = token(setOf("READ", "RELEASE", "CONFIGURE"), null)
            val args = mapOf("cpus" to 8, "memory_gib" to 16, "request_id" to UUID.randomUUID())
            assertThat(call(secret, "hub_configure_runner", args)["isError"].asBoolean()).isFalse()
            runner.save(RunnerResources(6, 12))
            assertThat(call(secret, "hub_configure_runner", args)["structuredContent"]["cpus"].asInt()).isEqualTo(8)
            assertThat(runner.resources()).isEqualTo(RunnerResources(6, 12))
            assertThat(call(secret, "hub_configure_runner", args + ("cpus" to 9))["structuredContent"]["error"]["code"].asText()).isEqualTo("CONFLICT")
            assertThat(call(secret, "hub_configure_runner", mapOf("cpus" to 21, "memory_gib" to 16, "request_id" to UUID.randomUUID()))["isError"].asBoolean()).isTrue()
            assertThat(call(scoped, "hub_configure_pipeline", mapOf("pipeline_id" to pipeline, "enabled" to false, "command" to "new build", "request_id" to UUID.randomUUID()))["isError"].asBoolean()).isFalse()
            assertThat(pipelines.list().single()["command"]).isEqualTo("new build")
        } finally { runner.save(original) }
    }
    @Test fun `ensure release pins SHA reuses automatic work and releases once even when automation is paused`() {
        val secret = token(setOf("READ", "RELEASE"))
        val args = mapOf("pipeline_id" to pipeline, "commit_sha" to "a".repeat(40), "request_id" to UUID.randomUUID())
        val first = call(secret, "hub_ensure_release", args)
        assertThat(first["isError"].asBoolean()).isFalse()
        val buildId = UUID.fromString(first["structuredContent"]["latestBuild"]["id"].asText())
        assertThat(call(secret, "hub_ensure_release", args)["structuredContent"]["latestBuild"]["id"].asText()).isEqualTo(buildId.toString())
        assertThat(call(secret, "hub_ensure_release", args + ("request_id" to UUID.randomUUID()))["isError"].asBoolean()).isFalse()
        assertThat(queue.builds()).hasSize(1)
        assertThat(pipelines.list().single()["enabled"]).isEqualTo(false)
        published(queue.get(buildId))
        pipelines.tick()
        repeat(40) { if (apps.list().isEmpty()) Thread.sleep(50) }
        assertThat(apps.list()).hasSize(1)
        val release = apps.list().single()
        assertThat(call(secret, "hub_cancel_deployment", mapOf("deployment_id" to release.id, "request_id" to UUID.randomUUID()))["structuredContent"]["status"].asText()).isEqualTo("CANCELLED")
        assertThat(apps.application(app.id).currentDeployment).isNull()
        assertThat(call(secret, "hub_ensure_release", args + ("commit_sha" to "b".repeat(40)) + ("request_id" to UUID.randomUUID()))["structuredContent"]["error"]["code"].asText()).isEqualTo("CONFLICT")
    }
    @Test fun `nested configuration retries ignore JSON object key order and never return environment values`() {
        val secret = token(setOf("READ", "RELEASE", "CONFIGURE"))
        val id = UUID.randomUUID()
        val application = linkedMapOf<String, Any>("id" to id, "projectId" to project.id, "serverId" to app.serverId, "name" to "Extra", "hostPort" to 19001, "containerPort" to 8080, "environment" to mapOf("PRIVATE_VALUE" to "fixture-secret"))
        val request = UUID.randomUUID()
        val first = call(secret, "hub_create_application", mapOf("application" to application, "request_id" to request))
        assertThat(first["isError"].asBoolean()).isFalse()
        val reverse = application.entries.reversed().associate { it.key to it.value }
        val replay = call(secret, "hub_create_application", mapOf("request_id" to request, "application" to reverse))
        assertThat(replay["isError"].asBoolean()).isFalse()
        assertThat(replay["structuredContent"]["id"].asText()).isEqualTo(id.toString())
        assertThat(replay.toString()).doesNotContain("fixture-secret")
        assertThat(apps.applications()).hasSize(2)
    }
    @Test fun `build cancellation is scoped durable and does not cancel a different project`() {
        val fixture = queue.createProject(NewProject("Java example"))
        val secret = token(setOf("READ", "RELEASE"), setOf(fixture.id))
        val response = call(secret, "hub_run_build", mapOf("project_id" to fixture.id, "request_id" to UUID.randomUUID()))
        val id = UUID.fromString(response["structuredContent"]["id"].asText())
        assertThat(call(secret, "hub_cancel_build", mapOf("build_id" to id, "request_id" to UUID.randomUUID()))["structuredContent"]["status"].asText()).isEqualTo("CANCELLED")
        assertThat(queue.get(id).status).isEqualTo(BuildStatus.CANCELLED)
    }
    @Test fun `running deployment cancellation keeps lease and actual post-switch outcome`() {
        val b = queue.enqueue(project.id, UUID.randomUUID(), SourceSnapshot("owner/repo", "dev", "a".repeat(40), "build", "app.jar")); published(b)
        val d = apps.enqueue(app.id, NewDeployment(UUID.randomUUID(), b.id)); val owner = UUID.randomUUID()
        apps.claim(owner)
        assertThat(apps.cancel(d.id).status).isEqualTo("CANCEL_REQUESTED")
        assertThat(apps.heartbeat(d.id, owner)).isEqualTo(BuildStatus.CANCEL_REQUESTED)
        apps.finish(d.id, owner, "CANCELLED", "Stopped before switch")
        assertThat(apps.application(app.id).currentDeployment).isNull()
        val next = apps.enqueue(app.id, NewDeployment(UUID.randomUUID(), b.id)); apps.claim(owner); apps.cancel(next.id)
        apps.finish(next.id, owner, "SUCCEEDED", "Already switched; actual result")
        assertThat(apps.application(app.id).currentDeployment).isEqualTo(next.id)
        assertThat(apps.get(next.id).status).isEqualTo("SUCCEEDED")
    }
}
