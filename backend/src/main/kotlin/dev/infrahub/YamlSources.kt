package dev.infrahub

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class YamlBinding(val id: UUID, val applicationId: UUID, val ref: String, val path: String, val expectedSha: String, val enabled: Boolean = false)
data class YamlPreviewRequest(val applicationId: UUID, val ref: String, val path: String)
data class YamlPreview(val sha: String, val path: String, val sha256: String, val yaml: String, val definition: DeploymentDefinition)
data class YamlDocument(val sha: String, val path: String, val sha256: String, val yaml: String)
data class YamlValidationRequest(val yaml: String)
data class YamlSaveRequest(val applicationId: UUID, val ref: String, val path: String, val expectedSha: String, val yaml: String)

@Service
@Profile("!worker & !deployer")
class YamlSources(private val github: GitHub, private val queue: QueueStore, private val apps: DeploymentStore, private val parser: DeploymentYaml) {
    fun source(applicationId: UUID, ref: String, path: String, pipelineId: UUID): SourceSnapshot {
        parser.path(path)
        val app = apps.application(applicationId)
        val project = queue.project(app.projectId)
        val source = github.resolve(requireNotNull(project.repositoryUrl) { "A GitHub repository is required" }, ref, ":")
        val bytes = github.file(source, path)
        require(bytes.size <= 65536) { "Deployment YAML is limited to 64 KiB" }
        val yaml = bytes.decodeToString(throwOnInvalidSequence = true)
        val definition = parser.parse(yaml)
        return source.copy(command = parser.command(definition), artifactPath = definition.build.artifact,
            yamlRecipe = YamlRecipe(pipelineId, applicationId, path, parser.hash(yaml), yaml, definition))
    }
    fun preview(input: YamlPreviewRequest): YamlPreview {
        val source = source(input.applicationId, input.ref, input.path, UUID(0, 0))
        val recipe = source.yamlRecipe!!
        return YamlPreview(source.sha, recipe.path, recipe.sha256, recipe.yaml, recipe.definition)
    }

    fun document(input: YamlPreviewRequest): YamlDocument {
        parser.path(input.path)
        val project = queue.project(apps.application(input.applicationId).projectId)
        val source = github.resolve(requireNotNull(project.repositoryUrl), input.ref, ":")
        val bytes = github.file(source, input.path)
        require(bytes.size <= 65536) { "Deployment YAML is limited to 64 KiB" }
        val yaml = bytes.decodeToString(throwOnInvalidSequence = true)
        // Allow the editor to open and repair invalid YAML as well.
        return YamlDocument(source.sha, input.path, parser.hash(yaml), yaml)
    }

    fun validate(yaml: String) = parser.parse(yaml)

    fun save(input: YamlSaveRequest): YamlDocument {
        parser.path(input.path)
        parser.parse(input.yaml)
        require(input.ref != "HEAD" && !input.ref.matches(Regex("[0-9a-fA-F]{40}"))) { "Для сохранения выберите имя ветки, а не тег или SHA." }
        require(input.expectedSha.matches(Regex("[0-9a-f]{40}")))
        val project = queue.project(apps.application(input.applicationId).projectId)
        val source = github.resolve(requireNotNull(project.repositoryUrl), input.ref, ":")
        if (source.sha != input.expectedSha) throw Conflict("Ветка изменилась. Загрузите YAML заново; ваши правки остаются в редакторе.")
        val sha = github.saveYaml(source, input.path, input.yaml)
        return YamlDocument(sha, input.path, parser.hash(input.yaml), input.yaml)
    }
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/deployment-yaml")
class YamlApi(private val sources: YamlSources, private val pipelines: PipelineService) {
    @PostMapping("/preview") fun preview(@RequestBody input: YamlPreviewRequest) = sources.preview(input)
    @PostMapping("/document") fun document(@RequestBody input: YamlPreviewRequest) = sources.document(input)
    @PostMapping("/validate") fun validate(@RequestBody input: YamlValidationRequest) = sources.validate(input.yaml)
    @PostMapping("/save") fun save(@RequestBody input: YamlSaveRequest) = sources.save(input)
    @PostMapping fun register(@RequestBody input: YamlBinding) = pipelines.registerYaml(input)
}
