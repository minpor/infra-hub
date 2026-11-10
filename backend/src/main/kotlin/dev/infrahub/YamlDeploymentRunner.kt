package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Component
@Profile("deployer")
class YamlDeploymentRunner(private val store: DeploymentStore, private val queue: QueueStore, private val ssh: SshTransport, private val mapper: ObjectMapper) {
    fun execute(d: Deployment, app: HubApplication, server: Server, owner: UUID, imageId: String?, directory: Path, executor: ProcessExecutor) {
        val execution = requireNotNull(store.yamlExecution(d.id))
        val source = requireNotNull(queue.get(d.buildId).source)
        val definition = execution.recipe.definition.deploy
        val payload = directory.resolve("yaml-release.json")
        val environment = execution.environment + mapOf(
            "HUB_APPLICATION_ID" to app.id.toString(), "HUB_DEPLOYMENT_ID" to d.id.toString(), "HUB_BUILD_ID" to d.buildId.toString(),
            "HUB_COMMIT_SHA" to source.sha, "HUB_IMAGE_ID" to (imageId ?: ""), "HUB_IMAGE_REFERENCE" to (if (imageId == null) "" else d.imageReference),
            "HUB_CONFIG_PATH" to execution.recipe.path, "HUB_CONFIG_SHA256" to execution.recipe.sha256
        )
        val body = mapper.writeValueAsBytes(mapOf("applicationId" to app.id, "deploymentId" to d.id, "imageId" to (imageId ?: ""), "definition" to definition, "environment" to environment))
        require(body.size <= 262144) { "Deployment payload exceeds 256 KiB" }
        Files.write(payload, body)
        if (store.heartbeat(d.id, owner) == BuildStatus.CANCEL_REQUESTED) throw ExecutionStopped(true)
        store.log(d.id, owner, "YAML ${execution.recipe.path} · sha256:${execution.recipe.sha256} · commit ${source.sha}\n")
        val script = javaClass.getResource("/deploy/apply-yaml.py")!!.readText()
        val command = "python3 -c '" + script.replace("'", "'\"'\"'") + "'"
        val receipt = directory.resolve("yaml-receipt.json")
        val timeout = (definition.steps + definition.verify + definition.rollback).sumOf { it.timeoutSeconds }.toLong() + 60
        var switching = false
        fun heartbeat(): BuildStatus? {
            val state = store.heartbeat(d.id, owner)
            return if (switching && state == BuildStatus.CANCEL_REQUESTED) BuildStatus.RUNNING else state
        }
        // From here user-defined remote effects may have started. Do not interrupt a
        // bounded script blindly; record the actual result and reconcile receipts.
        switching = true
        ssh.withCommand(server, command) { args ->
            val code = executor.runInput(args, directory, timeout, payload, ::heartbeat, { store.log(d.id, owner, it) }, receipt, 16384)
            if (code != 0) throw RunnerFailure("YAML executor failed; reconcile this deployment ID")
        }
        val result = mapper.readTree(Files.readString(receipt))
        val status = result.path("status").asText()
        require(status in setOf("SUCCEEDED", "FAILED", "INTERRUPTED"))
        require(result.path("deploymentId").asText() == d.id.toString())
        if (status == "SUCCEEDED") require(result.path("imageId").asText() == (imageId ?: ""))
        val message = result.path("message").asText().take(900) + if (store.get(d.id).status == "CANCEL_REQUESTED") " Cancellation arrived after remote execution began; actual outcome recorded." else ""
        store.log(d.id, owner, "$status: $message\n")
        store.finish(d.id, owner, status, message)
    }
}
