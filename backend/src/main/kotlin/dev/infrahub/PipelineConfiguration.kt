package dev.infrahub

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.util.UUID

data class ConfigurationFile(val path: String, val origin: String, val stage: String)
data class PipelineConfigurationView(val yaml: String?, val repositoryYaml: String?, val ref: String, val files: List<ConfigurationFile>, val command: String? = null, val artifactPath: String? = null)
data class ConfigurationSource(val path: String, val origin: String, val content: String, val sha256: String, val sha: String?, val references: List<ConfigurationFile>)

@Service
@Profile("!worker & !deployer")
class PipelineConfigurationService(
    private val pipelines: PipelineService, private val apps: DeploymentStore, private val queue: QueueStore,
    private val servers: ServerService, private val runner: RunnerSettings, private val github: GitHub, private val parser: DeploymentYaml
) {
    private val builderFiles = setOf("build-aukni-web.py", "bundle-web.py", "bundle-android.py", "local-nginx-test.py", "run-build")
    private val deployFiles = setOf("apply-compose.py", "apply-android.py", "apply.py", "apply-yaml.py", "verify-import.py")
    private fun pipeline(id: UUID) = pipelines.list().firstOrNull { it["id"] == id } ?: throw MissingResource()

    fun view(id: UUID): PipelineConfigurationView {
        val p = pipeline(id)
        val app = apps.application(p["application_id"] as UUID)
        val configPath = p["config_path"] as String?
        val command = p["command"] as String
        val files = mutableListOf<ConfigurationFile>()
        if (configPath != null) files.add(ConfigurationFile(configPath, "REPOSITORY", "configuration"))
        files.add(ConfigurationFile("/opt/hub/run-build", "BUILDER", "build"))
        builderFiles.filter { command.contains("/opt/hub/$it") }.forEach { files.add(ConfigurationFile("/opt/hub/$it", "BUILDER", "build")) }
        files.addAll(references(command))
        if (command.contains("/opt/hub/build-aukni-web.py")) {
            files.addAll(listOf("bundle-web.py", "local-nginx-test.py").map { ConfigurationFile("/opt/hub/$it", "BUILDER", "build") })
            files.addAll(listOf(".github/workflows/ci.yml", "deploy/check-assets.sh", "deploy/monitoring/apply.sh").map { ConfigurationFile(it, "REPOSITORY", "build") })
        }
        val deploy = if (configPath != null) "apply-yaml.py" else when (app.kind) { "COMPOSE" -> "apply-compose.py"; "ANDROID" -> "apply-android.py"; else -> "apply.py" }
        files.add(ConfigurationFile("deploy/$deploy", "DEPLOYER", "deploy"))
        if (!(p["artifact_path"] as String?).isNullOrBlank()) files.add(ConfigurationFile("deploy/verify-import.py", "DEPLOYER", "deploy"))
        if (configPath != null) return PipelineConfigurationView(null, configPath, p["ref"] as String, files.distinctBy { it.origin to it.path })
        val server = servers.get(app.serverId)
        val resources = runner.resources(app.projectId)
        val settings = linkedMapOf<String, Any?>(
            "name" to app.name, "environment" to p["environment"], "branch" to p["ref"], "automatic" to p["enabled"],
            "build" to linkedMapOf("command" to command, "artifact" to p["artifact_path"], "cpus" to resources.cpus,
                "memory_gib" to resources.memoryGiB, "dependency_cache" to resources.dependencyCacheEnabled),
            "deploy" to linkedMapOf("server" to server.name, "adapter" to app.kind, "script" to "deploy/$deploy",
                "settings" to app.configuration, "container_port" to app.containerPort, "health_path" to app.healthPath,
                "environment_keys" to app.environmentKeys)
        )
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; indent = 2; setSplitLines(false) }
        return PipelineConfigurationView(Yaml(options).dump(settings), null, p["ref"] as String, files.distinctBy { it.origin to it.path }, command, p["artifact_path"] as String?)
    }

    fun source(id: UUID, origin: String, path: String, sha: String?): ConfigurationSource {
        val p = pipeline(id)
        val app = apps.application(p["application_id"] as UUID)
        var selectedSha: String? = null
        val content = when (origin) {
            "BUILDER" -> {
                val name = path.removePrefix("/opt/hub/")
                require(path == "/opt/hub/$name" && name in builderFiles) { "Unknown builder script" }
                javaClass.getResource("/runner-scripts/$name")!!.readText()
            }
            "DEPLOYER" -> {
                val name = path.removePrefix("deploy/")
                require(path == "deploy/$name" && name in deployFiles) { "Unknown deployment script" }
                javaClass.getResource("/deploy/$name")!!.readText()
            }
            "REPOSITORY" -> {
                RepositorySourcePaths.validate(path)
                require(sha == null || sha.matches(Regex("[a-f0-9]{40}"))) { "Invalid source SHA" }
                val project = queue.project(app.projectId)
                val url = requireNotNull(project.repositoryUrl) { "A GitHub repository is required" }
                val source = if (sha == null) github.resolve(url, p["ref"] as String, ":")
                    else SourceSnapshot(GitHub.repositoryName(url), p["ref"] as String, sha, ":")
                selectedSha = source.sha
                val bytes = github.file(source, path)
                require(bytes.size <= 128 * 1024) { "Source preview is limited to 128 KiB" }
                bytes.decodeToString(throwOnInvalidSequence = true).also { require('\u0000' !in it) { "Binary source cannot be previewed" } }
            }
            else -> throw IllegalArgumentException("Unknown configuration source")
        }
        return ConfigurationSource(path, origin, content, parser.hash(content), selectedSha, references(content))
    }

    fun references(text: String): List<ConfigurationFile> = Regex("(?<![A-Za-z0-9_./-])(?:\\./)?[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.(?:sh|py|mjs|cjs|js|ya?ml|kts)(?![A-Za-z0-9_.-])")
        .findAll(text).map { it.value.removePrefix("./") }.filter { path -> runCatching { RepositorySourcePaths.validate(path) }.isSuccess }
        .distinct().take(30).map { ConfigurationFile(it, "REPOSITORY", "source") }.toList()
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/pipelines/{id}")
class PipelineConfigurationApi(private val service: PipelineConfigurationService) {
    @GetMapping("/configuration") fun view(@PathVariable id: UUID) = service.view(id)
    @GetMapping("/source") fun source(@PathVariable id: UUID, @RequestParam origin: String, @RequestParam path: String, @RequestParam(required = false) sha: String?) = service.source(id, origin, path, sha)
}
