package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID

object RepositorySourcePaths {
    fun validate(path: String) {
        require(path.length in 1..300 && path.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) && path.split('/').none { it in setOf(".", "..") }) { "Use a repository-relative source path without traversal" }
        require(path.endsWith(".sh") || path.endsWith(".py") || path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs") || path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".kts") || path.substringAfterLast('/') in setOf("gradlew", "package.json", "version.properties", "Dockerfile")) { "Choose a YAML or script file" }
    }
}

data class SourceFileWrite(val path: String, val content: String, val expectedSha: String? = null, val create: Boolean = false)
data class RepositoryFileList(val sha: String, val files: List<ConfigurationFile>, val truncated: Boolean)
data class NativeBuildSettings(val command: String, val artifactPath: String, val expectedCommand: String, val expectedArtifactPath: String)

@Service
@Profile("!worker & !deployer")
class RepositorySourceEditing(
    private val pipelines: PipelineService, private val apps: DeploymentStore, private val queue: QueueStore,
    private val github: GitHub, private val parser: DeploymentYaml, private val configuration: PipelineConfigurationService, private val mapper: ObjectMapper
) {
    private fun pipeline(id: UUID) = pipelines.list().firstOrNull { it["id"] == id } ?: throw MissingResource()
    private fun snapshot(id: UUID): SourceSnapshot {
        val p = pipeline(id)
        val project = queue.project(apps.application(p["application_id"] as UUID).projectId)
        return github.resolve(requireNotNull(project.repositoryUrl) { "A GitHub repository is required" }, p["ref"] as String, ":")
    }
    fun base(id: UUID) = mapOf("sha" to snapshot(id).sha)
    fun access(id: UUID): RepositoryWriteAccess {
        val p = pipeline(id)
        val project = queue.project(apps.application(p["application_id"] as UUID).projectId)
        return github.writeAccess(requireNotNull(project.repositoryUrl))
    }
    fun files(id: UUID): RepositoryFileList {
        val source = snapshot(id)
        val (paths, truncated) = github.sourceFiles(source)
        return RepositoryFileList(source.sha, paths.map { ConfigurationFile(it, "REPOSITORY", "source") }, truncated)
    }
    fun validate(id: UUID, input: SourceFileWrite): Map<String, Boolean> {
        val p = pipeline(id)
        RepositorySourcePaths.validate(input.path)
        require(input.content.isNotBlank() && input.content.toByteArray().size <= 128 * 1024 && '\u0000' !in input.content) { "Source file must contain text, up to 128 KiB" }
        if (input.path == p["config_path"]) parser.parse(input.content)
        else if (input.path.endsWith(".yaml") || input.path.endsWith(".yml")) {
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 20; nestingDepthLimit = 64; codePointLimit = 128 * 1024 }
            try { Yaml(SafeConstructor(options)).load<Any>(input.content) }
            catch (_: Exception) { throw IllegalArgumentException("Некорректный YAML. Проверьте синтаксис и повторяющиеся ключи.") }
        } else if (input.path.endsWith(".json")) {
            try { require(mapper.readTree(input.content) != null) }
            catch (_: Exception) { throw IllegalArgumentException("Некорректный JSON.") }
        }
        return mapOf("valid" to true)
    }
    fun save(id: UUID, input: SourceFileWrite): ConfigurationSource {
        validate(id, input)
        require(input.expectedSha?.matches(Regex("[a-f0-9]{40}")) == true) { "A full expected commit SHA is required" }
        val source = snapshot(id)
        if (source.sha != input.expectedSha) throw Conflict("Ветка изменилась. Обновите файл перед сохранением; ваш черновик остаётся в редакторе.")
        val commit = github.saveFile(source, input.path, input.content, input.create)
        return ConfigurationSource(input.path, "REPOSITORY", input.content, parser.hash(input.content), commit, configuration.references(input.content))
    }
    fun buildSettings(id: UUID, input: NativeBuildSettings) = pipelines.configure(id, null, input.command, input.artifactPath, input.expectedCommand, input.expectedArtifactPath)
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/pipelines/{id}")
class RepositorySourceEditingApi(private val service: RepositorySourceEditing) {
    @GetMapping("/source-base") fun base(@PathVariable id: UUID) = service.base(id)
    @GetMapping("/source-access") fun access(@PathVariable id: UUID) = service.access(id)
    @GetMapping("/repository-files") fun files(@PathVariable id: UUID) = service.files(id)
    @PostMapping("/source/validate") fun validate(@PathVariable id: UUID, @RequestBody input: SourceFileWrite) = service.validate(id, input)
    @PostMapping("/source/save") fun save(@PathVariable id: UUID, @RequestBody input: SourceFileWrite) = service.save(id, input)
    @PostMapping("/build-settings") fun buildSettings(@PathVariable id: UUID, @RequestBody input: NativeBuildSettings) = service.buildSettings(id, input)
}
