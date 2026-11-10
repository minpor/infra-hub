package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

data class RunnerResources(val cpus: Int, val memoryGiB: Int, val dependencyCacheEnabled: Boolean = true) {
    val memoryBytes: Long get() = memoryGiB * 1024L * 1024 * 1024
}

@Service
class RunnerSettings(
    private val jdbc: JdbcTemplate,
    @param:Value("\${hub.runner-max-cpus:20}") private val maxCpus: Int,
    @param:Value("\${hub.runner-max-memory-gib:18}") private val maxMemoryGiB: Int
) {
    fun resources(projectId: UUID? = null): RunnerResources {
        if (projectId != null) {
            requireProject(projectId)
            jdbc.query("SELECT * FROM project_runner_settings WHERE project_id=?", { rs, _ ->
                RunnerResources(rs.getInt("cpus"), rs.getInt("memory_gib"), rs.getBoolean("dependency_cache_enabled"))
            }, projectId).firstOrNull()?.let { return it.also(::validate) }
        }
        return jdbc.queryForObject("SELECT * FROM runner_settings WHERE id=true") { rs, _ ->
            RunnerResources(rs.getInt("cpus"), rs.getInt("memory_gib"), rs.getBoolean("dependency_cache_enabled"))
        }!!.also(::validate)
    }

    private fun requireProject(id: UUID) {
        if (jdbc.queryForObject("SELECT count(*) FROM projects WHERE id=?", Long::class.java, id) != 1L) throw MissingResource()
    }

    private fun validate(value: RunnerResources) {
        require(value.cpus in 1..maxCpus && value.memoryGiB in 2..maxMemoryGiB) {
            "Допустимо 1–$maxCpus CPU и 2–$maxMemoryGiB GiB памяти"
        }
    }

    fun view(projectId: UUID? = null): Map<String, Any> {
        val current = resources(projectId)
        val inherited = projectId != null && jdbc.queryForObject("SELECT count(*) FROM project_runner_settings WHERE project_id=?", Long::class.java, projectId) == 0L
        return mapOf("cpus" to current.cpus, "memoryGiB" to current.memoryGiB, "dependencyCacheEnabled" to current.dependencyCacheEnabled,
            "maxCpus" to maxCpus, "maxMemoryGiB" to maxMemoryGiB, "inherited" to inherited)
    }

    fun save(value: RunnerResources, projectId: UUID? = null): Map<String, Any> {
        validate(value)
        if (projectId == null) {
            jdbc.update("UPDATE runner_settings SET cpus=?,memory_gib=?,dependency_cache_enabled=?,updated_at=clock_timestamp() WHERE id=true", value.cpus, value.memoryGiB, value.dependencyCacheEnabled)
        } else {
            requireProject(projectId)
            jdbc.update("""
                INSERT INTO project_runner_settings(project_id,cpus,memory_gib,dependency_cache_enabled) VALUES (?,?,?,?)
                ON CONFLICT (project_id) DO UPDATE SET cpus=EXCLUDED.cpus,memory_gib=EXCLUDED.memory_gib,
                    dependency_cache_enabled=EXCLUDED.dependency_cache_enabled,updated_at=clock_timestamp()
            """.trimIndent(), projectId, value.cpus, value.memoryGiB, value.dependencyCacheEnabled)
        }
        return view(projectId)
    }

    fun reset(projectId: UUID): Map<String, Any> {
        requireProject(projectId)
        jdbc.update("DELETE FROM project_runner_settings WHERE project_id=?", projectId)
        return view(projectId)
    }
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/projects/{id}/runner-settings")
class ProjectRunnerSettingsApi(private val settings: RunnerSettings) {
    @GetMapping fun get(@PathVariable id: UUID) = settings.view(id)
    @PostMapping fun save(@PathVariable id: UUID, @RequestBody input: RunnerResources) = settings.save(input, id)
    @PostMapping("/reset") fun reset(@PathVariable id: UUID) = settings.reset(id)
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/runner-settings")
class RunnerSettingsApi(private val settings: RunnerSettings) {
    @GetMapping fun get() = settings.view()
    @PostMapping fun save(@RequestBody input: RunnerResources) = settings.save(input)
}
