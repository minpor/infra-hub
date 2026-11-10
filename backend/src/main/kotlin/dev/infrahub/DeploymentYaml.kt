package dev.infrahub

import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.NodeEvent
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID

data class YamlStep(val name: String, val run: String, val timeoutSeconds: Int = 300)
data class YamlBuild(val steps: List<YamlStep>, val artifact: String? = null)
data class YamlDeploy(val directory: String, val steps: List<YamlStep>, val verify: List<YamlStep>, val rollback: List<YamlStep> = emptyList())
data class DeploymentDefinition(val version: Int, val name: String, val build: YamlBuild, val deploy: YamlDeploy)
data class YamlRecipe(val pipelineId: UUID, val applicationId: UUID, val path: String, val sha256: String, val yaml: String, val definition: DeploymentDefinition)
data class YamlExecution(val recipe: YamlRecipe, val environment: Map<String, String>)

@Component
class DeploymentYaml {
    fun path(value: String): String {
        require(value.length in 1..300 && value.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) && value.split('/').none { it in setOf(".", "..") } && (value.endsWith(".yaml") || value.endsWith(".yml"))) { "Use a repository-relative .yaml or .yml path without traversal" }
        return value
    }

    fun parse(text: String): DeploymentDefinition {
        require(text.toByteArray().size <= 65536 && '\u0000' !in text) { "Deployment YAML is limited to 64 KiB" }
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; nestingDepthLimit = 16; codePointLimit = 65536 }
        val yaml = Yaml(SafeConstructor(options))
        try {
            require(yaml.parse(StringReader(text)).none { it is AliasEvent || it is NodeEvent && it.anchor != null }) { "YAML anchors and aliases are not supported" }
            val root = obj(yaml.load<Any>(text), setOf("version", "name", "build", "deploy"))
            require(root["version"] is Int && root["version"] == 1) { "Unsupported deployment YAML version; expected 1" }
            val name = string(root["name"], 80)
            require(name.none(Char::isISOControl))
            val build = obj(root["build"], setOf("steps", "artifact"))
            val artifact = build["artifact"]?.let { string(it, 300).also(ImagePublisher::validateArtifactPath) }
            val buildSteps = steps(build["steps"])
            require(buildSteps.sumOf { it.timeoutSeconds } <= 2400) { "Combined build timeout must not exceed 2400 seconds" }
            val deploy = obj(root["deploy"], setOf("directory", "steps", "verify", "rollback"))
            val directory = string(deploy["directory"], 300)
            require(directory.startsWith('/') && java.nio.file.Path.of(directory).normalize().toString() != "/" && directory.split('/').none { it == ".." } && directory.none(Char::isISOControl)) { "Deploy directory must be an absolute application directory" }
            val result = DeploymentDefinition(1, name, YamlBuild(buildSteps, artifact), YamlDeploy(directory, steps(deploy["steps"]), steps(deploy["verify"]), deploy["rollback"]?.let { steps(it) } ?: emptyList()))
            require((result.deploy.steps + result.deploy.verify + result.deploy.rollback).sumOf { it.timeoutSeconds } <= 3600) { "Combined deploy, verify and rollback timeout must not exceed 3600 seconds" }
            require(command(result).length <= 32000) { "Combined build script is limited to 32000 characters" }
            return result
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("Invalid deployment YAML; check syntax, duplicate keys and field types") }
    }

    private fun obj(value: Any?, keys: Set<String>): Map<*, *> {
        require(value is Map<*, *> && value.keys.all { it is String && it in keys }) { "Unknown YAML field or invalid object" }
        return value
    }
    private fun string(value: Any?, limit: Int): String {
        require(value is String && value.isNotBlank() && value.length <= limit && '\u0000' !in value) { "Expected a nonempty YAML string (up to $limit characters)" }
        return value
    }
    private fun steps(value: Any?): List<YamlStep> {
        require(value is List<*> && value.size in 1..20) { "Each stage requires 1–20 named steps" }
        return value.map { raw ->
            val step = obj(raw, setOf("name", "run", "timeoutSeconds"))
            val name = string(step["name"], 80)
            require(name.none(Char::isISOControl))
            val timeout = step["timeoutSeconds"] ?: 300
            require(timeout is Int && timeout in 1..900) { "Step timeout must be 1–900 seconds" }
            YamlStep(name, string(step["run"], 16000), timeout)
        }.also { require(it.map(YamlStep::name).distinct().size == it.size) { "Step names must be unique within a stage" } }
    }
    fun command(definition: DeploymentDefinition): String = definition.build.steps.joinToString("\n") { step ->
        val title = step.name.replace("'", "'\"'\"'")
        val script = step.run.replace("'", "'\"'\"'")
        "printf '%s\\n' '== $title =='\ntimeout --kill-after=5s ${step.timeoutSeconds}s sh -eu -c '$script'"
    }
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
