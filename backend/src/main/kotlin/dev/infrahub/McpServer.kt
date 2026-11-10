package dev.infrahub

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@Profile("!worker & !deployer")
class McpServer(private val tools: McpTools, private val mapper: ObjectMapper, private val artifacts: ReleaseArtifacts) {
    private val versions = setOf("2025-11-25", "2025-06-18", "2025-03-26")
    private fun identity(authentication: Authentication) = authentication.principal as McpIdentity
    private fun error(id: JsonNode?, code: Int, message: String) = mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))
    private fun toolError(code: String, message: String) = mapOf("content" to listOf(mapOf("type" to "text", "text" to message)), "structuredContent" to mapOf("error" to mapOf("code" to code, "message" to message)), "isError" to true)
    @RequestMapping("/mcp", method = [RequestMethod.GET, RequestMethod.DELETE])
    fun noStream(): ResponseEntity<Void> = ResponseEntity.status(405).header("Allow", "POST").build()

    @PostMapping("/mcp", produces = ["application/json"])
    fun rpc(request: HttpServletRequest, authentication: Authentication): ResponseEntity<Any> {
        fun reply(body: Any, status: Int = 200) = ResponseEntity.status(status).header("Cache-Control", "no-store").body(body)
        if (!request.contentType.orEmpty().substringBefore(';').equals("application/json", true)) return reply(error(null, -32600, "Content-Type must be application/json"), 415)
        val accept = request.getHeader("Accept").orEmpty()
        if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return reply(error(null, -32600, "Accept must include application/json and text/event-stream"), 406)
        val bytes = request.inputStream.readNBytes(65537)
        if (bytes.size > 65536) return reply(error(null, -32600, "Request exceeds 64 KiB"), 413)
        val input = try { mapper.readTree(bytes) } catch (_: Exception) { return reply(error(null, -32700, "Parse error"), 400) }
        if (input == null || !input.isObject || input["jsonrpc"]?.asText() != "2.0" || !input.path("method").isTextual) return reply(error(input?.get("id"), -32600, "Invalid Request"), 400)
        val id = input["id"]
        if (id != null && !id.isNull && !id.isIntegralNumber && !id.isTextual) return reply(error(null, -32600, "Invalid request id"), 400)
        val method = input["method"].asText()
        val protocol = request.getHeader("MCP-Protocol-Version")
        if (protocol != null && protocol !in versions) return reply(error(id, -32600, "Unsupported protocol version"), 400)
        if (id == null) {
            if (method.startsWith("notifications/")) return ResponseEntity.accepted().build()
            return reply(error(null, -32600, "A request requires id"), 400)
        }
        val params = input["params"] ?: mapper.createObjectNode()
        val client = identity(authentication)
        val result: Any = when (method) {
            "initialize" -> mapOf("protocolVersion" to (params["protocolVersion"]?.asText()?.takeIf { it in versions } ?: "2025-11-25"),
                "serverInfo" to mapOf("name" to "infra-hub", "version" to "0.2.0"), "instructions" to tools.guide,
                "capabilities" to mapOf("tools" to mapOf("listChanged" to false), "resources" to mapOf("subscribe" to false, "listChanged" to false), "prompts" to mapOf("listChanged" to false)))
            "ping" -> emptyMap<String, Any>()
            "tools/list" -> mapOf("tools" to tools.definitions(client))
            "tools/call" -> {
                if (!params.path("name").isTextual || !params.path("arguments").isObject) return reply(error(id, -32602, "Tool name and object arguments are required"))
                try {
                    val output = tools.call(client, params["name"].asText(), params["arguments"])
                    val structured: JsonNode = mapper.valueToTree(output)
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(output))), "structuredContent" to if (structured.isObject) structured else mapper.createObjectNode().set<JsonNode>("items", structured), "isError" to false)
                } catch (_: McpDenied) { toolError("FORBIDDEN", "Ключ не разрешает эту операцию") }
                catch (_: MissingResource) { toolError("NOT_FOUND", "Ресурс не найден в доступных проектах") }
                catch (e: Conflict) { toolError("CONFLICT", e.explanation) }
                catch (e: RepositoryFailure) { toolError("SOURCE_UNAVAILABLE", e.message ?: "Источник недоступен") }
                catch (e: IllegalArgumentException) { toolError("INVALID_ARGUMENT", e.message?.take(300) ?: "Неверные параметры") }
                catch (_: Exception) { toolError("OPERATION_FAILED", "Операция не выполнена; проверьте состояние и повторите с тем же request_id") }
            }
            "resources/list" -> mapOf("resources" to listOf(mapOf("uri" to "infra-hub://release-guide", "name" to "Release workflow", "mimeType" to "text/plain", "description" to "Rules, permissions and live deployment catalog")))
            "resources/templates/list" -> mapOf("resourceTemplates" to emptyList<Any>())
            "resources/read" -> {
                if (params["uri"]?.asText() != "infra-hub://release-guide") return reply(error(id, -32602, "Unknown resource"))
                mapOf("contents" to listOf(mapOf("uri" to "infra-hub://release-guide", "mimeType" to "application/json", "text" to mapper.writeValueAsString(tools.catalog(client)))))
            }
            "prompts/list" -> mapOf("prompts" to listOf(mapOf("name" to "release_workflow", "description" to "How to code, push, follow and manage a release through Infra Hub")))
            "prompts/get" -> {
                if (params["name"]?.asText() != "release_workflow") return reply(error(id, -32602, "Unknown prompt"))
                mapOf("description" to "Infra Hub release workflow", "messages" to listOf(mapOf("role" to "user", "content" to mapOf("type" to "text", "text" to tools.guide))))
            }
            else -> return reply(error(id, -32601, "Method not found"))
        }
        return reply(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }
    @GetMapping("/mcp/artifacts/{id}/{name}")
    fun download(@PathVariable id: UUID, @PathVariable name: String, authentication: Authentication): ResponseEntity<FileSystemResource> {
        val client = identity(authentication)
        client.requirePermission("READ")
        tools.deployment(client, id)
        return artifacts.download(id, name)
    }
}
