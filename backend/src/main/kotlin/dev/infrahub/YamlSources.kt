package dev.infrahub

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class YamlBinding(val id: UUID, val applicationId: UUID, val ref: String, val path: String, val expectedSha: String, val enabled: Boolean = false)
data class YamlPreviewRequest(val applicationId: UUID, val ref: String, val path: String)
data class YamlPreview(val sha: String, val path: String, val sha256: String, val yaml: String, val definition: DeploymentDefinition)

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
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/deployment-yaml")
class YamlApi(private val sources: YamlSources, private val pipelines: PipelineService) {
    @PostMapping("/preview") fun preview(@RequestBody input: YamlPreviewRequest) = sources.preview(input)
    @PostMapping fun register(@RequestBody input: YamlBinding) = pipelines.registerYaml(input)
}
