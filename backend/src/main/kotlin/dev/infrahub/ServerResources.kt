package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class HostResources(
    val hostname: String, val os: String, val architecture: String,
    val cpuCount: Int, val cpuUsagePercent: Double?, val memoryTotalBytes: Long,
    val memoryUsedBytes: Long, val diskTotalBytes: Long, val diskUsedBytes: Long,
    val loadAverage: Double, val uptimeSeconds: Long
) {
    fun validate(): HostResources {
        require(hostname.length <= 253 && os.length <= 300 && architecture.length <= 80)
        require(cpuCount in 1..65536 && (cpuUsagePercent == null || cpuUsagePercent.isFinite() && cpuUsagePercent in 0.0..100.0))
        require(memoryTotalBytes > 0 && memoryUsedBytes in 0..memoryTotalBytes)
        require(diskTotalBytes > 0 && diskUsedBytes in 0..diskTotalBytes)
        require(loadAverage.isFinite() && loadAverage >= 0 && uptimeSeconds >= 0)
        return this
    }
}
data class ResourceObservation(
    val value: HostResources?, val observedAt: Instant?, val attemptedAt: Instant?, val error: String?
)

@Service
@Profile("!worker")
class ServerResources(private val servers: ServerService, private val ssh: SshTransport, private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun read(id: UUID): ResourceObservation {
        servers.get(id)
        return jdbc.queryForObject("SELECT resources::text, resources_observed_at, resources_attempted_at, resources_error FROM servers WHERE id=?", { rs, _ ->
            ResourceObservation(rs.getString(1)?.let { mapper.readValue(it, HostResources::class.java) }, rs.getTimestamp(2)?.toInstant(), rs.getTimestamp(3)?.toInstant(), rs.getString(4))
        }, id)!!
    }

    fun refresh(id: UUID): ResourceObservation {
        val server = servers.get(id)
        require(server.hostKey != null) { "Сначала сверьте и подтвердите отпечаток сервера" }
        try {
            val result = ssh.resources(server)
            check(result.code == 0)
            val value = mapper.readValue(result.output, HostResources::class.java).validate()
            jdbc.update("UPDATE servers SET resources=?::jsonb,resources_observed_at=clock_timestamp(),resources_attempted_at=clock_timestamp(),resources_error=NULL WHERE id=?", mapper.writeValueAsString(value), id)
        } catch (_: Exception) {
            // Keep the last successful measurement; never turn an outage into zero utilization.
            jdbc.update("UPDATE servers SET resources_attempted_at=clock_timestamp(),resources_error='Resource probe failed. Check pinned SSH access and Python 3 on the Linux host.' WHERE id=?", id)
        }
        return read(id)
    }
}
