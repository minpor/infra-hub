package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

fun mcpHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class McpIdentity(val id: UUID, val name: String, val permissions: Set<String>, val projectIds: Set<UUID>?) {
    fun allows(project: UUID) = projectIds == null || project in projectIds
    fun requirePermission(permission: String) { if (permission !in permissions) throw McpDenied() }
    fun requireProject(project: UUID) { if (!allows(project)) throw MissingResource() }
}
class McpDenied : RuntimeException()
data class NewMcpToken(val name: String, val permissions: Set<String>, val projectIds: Set<UUID>? = null, val expiresInDays: Int = 90)

@Service
@Profile("!worker & !deployer")
class McpAccess(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val mapper: ObjectMapper) {
    fun tokens(): List<Map<String, Any?>> = jdbc.query("SELECT id,name,permissions::text,project_ids::text,created_at,expires_at,last_used_at,revoked_at FROM mcp_tokens ORDER BY created_at DESC") { rs, _ ->
        mapOf("id" to rs.getObject("id", UUID::class.java), "name" to rs.getString("name"), "permissions" to mapper.readTree(rs.getString("permissions")),
            "projectIds" to rs.getString("project_ids")?.let(mapper::readTree), "createdAt" to rs.getTimestamp("created_at").toInstant(),
            "expiresAt" to rs.getTimestamp("expires_at")?.toInstant(), "lastUsedAt" to rs.getTimestamp("last_used_at")?.toInstant(), "revokedAt" to rs.getTimestamp("revoked_at")?.toInstant())
    }
    fun create(input: NewMcpToken): Map<String, Any> {
        require(input.name.trim().length in 1..80 && input.name.none(Char::isISOControl))
        require("READ" in input.permissions && input.permissions.all { it in setOf("READ", "RELEASE", "CONFIGURE") })
        require("CONFIGURE" !in input.permissions || "RELEASE" in input.permissions) { "Настройка pipeline включает управление выпусками" }
        require(input.expiresInDays in 0..365)
        input.projectIds?.let { ids ->
            require(ids.size in 1..100)
            require(ids.all { jdbc.queryForObject("SELECT count(*) FROM projects WHERE id=?", Int::class.java, it) == 1 })
        }
        val id = UUID.randomUUID()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = "ihm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiry = input.expiresInDays.takeIf { it > 0 }?.let { Instant.now().plusSeconds(it * 86400L) }
        jdbc.update("INSERT INTO mcp_tokens(id,name,token_hash,permissions,project_ids,expires_at) VALUES (?,?,?,?::jsonb,?::jsonb,?)", id, input.name.trim(), mcpHash(secret), mapper.writeValueAsString(input.permissions.sorted()), input.projectIds?.let(mapper::writeValueAsString), expiry?.let(java.sql.Timestamp::from))
        return mapOf("id" to id, "token" to secret, "name" to input.name.trim())
    }
    fun revoke(id: UUID) { if (jdbc.update("UPDATE mcp_tokens SET revoked_at=coalesce(revoked_at,clock_timestamp()) WHERE id=?", id) != 1) throw MissingResource() }
    fun authenticate(secret: String): McpIdentity? {
        if (!secret.matches(Regex("ihm_[A-Za-z0-9_-]{43}"))) return null
        val identity = jdbc.query("SELECT id,name,permissions::text,project_ids::text FROM mcp_tokens WHERE token_hash=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>clock_timestamp())", { rs, _ ->
            McpIdentity(rs.getObject("id", UUID::class.java), rs.getString("name"), mapper.readTree(rs.getString("permissions")).map { it.asText() }.toSet(), rs.getString("project_ids")?.let { mapper.readTree(it).map { item -> UUID.fromString(item.asText()) }.toSet() })
        }, mcpHash(secret)).firstOrNull() ?: return null
        jdbc.update("UPDATE mcp_tokens SET last_used_at=clock_timestamp() WHERE id=? AND (last_used_at IS NULL OR last_used_at<clock_timestamp()-interval '1 minute')", identity.id)
        return identity
    }
    fun mutation(identity: McpIdentity, request: UUID, tool: String, arguments: com.fasterxml.jackson.databind.JsonNode, action: () -> Any): Any = tx.execute {
        // Serialize only the same client's operation ID, not unrelated tools or jobs.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL", Boolean::class.java, "mcp:${identity.id}:$request")
        val hash = mcpHash(mapper.writeValueAsString(canonical(arguments)))
        val old = jdbc.queryForList("SELECT tool,arguments_hash,result::text FROM mcp_requests WHERE token_id=? AND request_id=?", identity.id, request).firstOrNull()
        if (old != null) {
            if (old["tool"] != tool || old["arguments_hash"] != hash) throw Conflict("request_id уже использован для другой операции")
            return@execute mapper.readTree(old["result"] as String)
        }
        val result = action()
        jdbc.update("INSERT INTO mcp_requests(token_id,request_id,tool,arguments_hash,result) VALUES (?,?,?,?,?::jsonb)", identity.id, request, tool, hash, mapper.writeValueAsString(result))
        result
    }!!
    private fun canonical(node: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { sorted -> node.properties().sortedBy { it.key }.forEach { sorted.set<com.fasterxml.jackson.databind.JsonNode>(it.key, canonical(it.value)) } }
        node.isArray -> mapper.createArrayNode().also { sorted -> node.forEach { sorted.add(canonical(it)) } }
        else -> node
    }
    fun audit(): List<Map<String, Any>> = jdbc.queryForList("SELECT r.request_id,r.tool,r.created_at,t.name AS client FROM mcp_requests r JOIN mcp_tokens t ON t.id=r.token_id ORDER BY r.created_at DESC LIMIT 50")
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/mcp-settings")
class McpSettingsApi(private val access: McpAccess, @param:Value("\${HUB_PUBLIC_URL:https://infra-hub.minpor.ru}") private val publicUrl: String) {
    @GetMapping fun get() = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("url" to publicUrl.trimEnd('/') + "/mcp", "tokens" to access.tokens(), "audit" to access.audit()))
    @PostMapping("/tokens") fun create(@RequestBody input: NewMcpToken) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.create(input))
    @PostMapping("/tokens/{id}/revoke") fun revoke(@PathVariable id: UUID): Map<String, Boolean> { access.revoke(id); return mapOf("revoked" to true) }
}
