package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.system.exitProcess

/** Local owner console: requires the installation's database and GitHub credentials; never starts HTTP. */
@Component
@Profile("admin")
class HubAdmin(private val apps: DeploymentStore, private val pipelines: PipelineService, private val mapper: ObjectMapper, private val mcp: McpAccess) : CommandLineRunner {
    override fun run(vararg args: String) {
        val input = mapper.readTree(System.`in`)
        require(input != null && input.isArray && input.size() in 1..20) { "Owner plan must be a nonempty JSON array" }
        val results = input.map { action ->
            val value = action["input"]
            when (action["action"].asText()) {
                "mcp-token" -> mcp.create(mapper.treeToValue(value, NewMcpToken::class.java))
                "mcp-revoke" -> { mcp.revoke(UUID.fromString(value["id"].asText())); mapOf("revoked" to true) }
                "application" -> apps.create(mapper.treeToValue(value, NewApplication::class.java))
                "pipeline" -> pipelines.create(mapper.treeToValue(value, NewPipeline::class.java))
                "run" -> pipelines.start(UUID.fromString(value["id"].asText()))
                "enabled" -> { pipelines.enabled(UUID.fromString(value["id"].asText()), value["enabled"].asBoolean()); mapOf("updated" to true) }
                else -> throw IllegalArgumentException("Unknown owner-console action")
            }
        }
        println("INFRA_HUB_ADMIN=" + mapper.writeValueAsString(results))
        exitProcess(0)
    }
}
