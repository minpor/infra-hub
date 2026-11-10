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

data class RunnerResources(val cpus: Int, val memoryGiB: Int) {
    val memoryBytes: Long get() = memoryGiB * 1024L * 1024 * 1024
}

@Service
class RunnerSettings(
    private val jdbc: JdbcTemplate,
    @param:Value("\${hub.runner-max-cpus:20}") private val maxCpus: Int,
    @param:Value("\${hub.runner-max-memory-gib:18}") private val maxMemoryGiB: Int
) {
    fun resources(): RunnerResources = jdbc.queryForObject("SELECT cpus,memory_gib FROM runner_settings WHERE id=true") { rs, _ ->
        RunnerResources(rs.getInt("cpus"), rs.getInt("memory_gib"))
    }!!.also(::validate)

    private fun validate(value: RunnerResources) {
        require(value.cpus in 1..maxCpus && value.memoryGiB in 2..maxMemoryGiB) {
            "Допустимо 1–$maxCpus CPU и 2–$maxMemoryGiB GiB памяти"
        }
    }

    fun view(): Map<String, Any> {
        val current = resources()
        return mapOf("cpus" to current.cpus, "memoryGiB" to current.memoryGiB, "maxCpus" to maxCpus, "maxMemoryGiB" to maxMemoryGiB)
    }

    fun save(value: RunnerResources): Map<String, Any> {
        validate(value)
        jdbc.update("UPDATE runner_settings SET cpus=?,memory_gib=?,updated_at=clock_timestamp() WHERE id=true", value.cpus, value.memoryGiB)
        return view()
    }
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/runner-settings")
class RunnerSettingsApi(private val settings: RunnerSettings) {
    @GetMapping fun get() = settings.view()
    @PostMapping fun save(@RequestBody input: RunnerResources) = settings.save(input)
}
