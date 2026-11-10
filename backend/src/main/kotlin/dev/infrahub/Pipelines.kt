package dev.infrahub

import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class NewPipeline(val id: UUID, val applicationId: UUID, val ref: String, val command: String, val artifactPath: String, val enabled: Boolean = false)

@Service
@Profile("!worker & !deployer")
class PipelineService(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val queue: QueueStore, private val deployments: DeploymentStore, private val github: GitHub) {
    private val running = AtomicBoolean(false)
    fun list() = jdbc.queryForList("SELECT * FROM pipelines ORDER BY id")
    fun create(input: NewPipeline): Map<String, Any> = tx.execute {
        require(input.ref.isNotBlank() && input.ref.length <= 200 && !input.ref.any { it.isISOControl() })
        require(input.command.isNotBlank() && input.command.length <= 2000 && '\u0000' !in input.command)
        ImagePublisher.validateArtifactPath(input.artifactPath)
        val app = deployments.application(input.applicationId)
        if (app.kind != "STANDALONE") require(input.ref == app.configuration["ref"]) { "Ветка pipeline должна совпадать с окружением приложения" }
        jdbc.queryForList("SELECT * FROM pipelines WHERE id=?", input.id).firstOrNull()?.let { prior ->
            if (prior["application_id"] != input.applicationId || prior["ref"] != input.ref || prior["command"] != input.command || prior["artifact_path"] != input.artifactPath) throw Conflict("ID pipeline уже использован другой конфигурацией")
        }
        jdbc.update("INSERT INTO pipelines(id,application_id,ref,command,artifact_path,enabled) VALUES (?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING", input.id, input.applicationId, input.ref, input.command, input.artifactPath, input.enabled)
        jdbc.queryForMap("SELECT * FROM pipelines WHERE id = ?", input.id)
    }!!
    fun enabled(id: UUID, enabled: Boolean) { if (jdbc.update("UPDATE pipelines SET enabled=? WHERE id=?", enabled, id) != 1) throw MissingResource() }
    fun start(id: UUID): Build {
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        val app = deployments.application(p["application_id"] as UUID)
        val project = queue.project(app.projectId)
        val source = github.resolve(requireNotNull(project.repositoryUrl), p["ref"] as String, p["command"] as String).copy(artifactPath = p["artifact_path"] as String)
        return enqueue(id, source, true)
    }
    fun ensureRelease(id: UUID, expectedSha: String, requestId: UUID): Build {
        require(expectedSha.matches(Regex("[a-f0-9]{40}"))) { "Укажите полный SHA опубликованного commit" }
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        val app = deployments.application(p["application_id"] as UUID)
        queue.requested(app.projectId, requestId)?.let { prior ->
            require(prior.source?.sha == expectedSha && prior.source.ref == p["ref"]) { "request_id уже принадлежит другой сборке" }
            return prior
        }
        val project = queue.project(app.projectId)
        val source = github.resolve(requireNotNull(project.repositoryUrl), p["ref"] as String, p["command"] as String).copy(artifactPath = p["artifact_path"] as String)
        if (source.sha != expectedSha) throw Conflict("Ветка уже указывает на ${source.sha}; запрошен $expectedSha. Уточните commit перед выпуском")
        return enqueue(id, source, true, requestId, true)
    }
    fun configure(id: UUID, enabled: Boolean?, command: String?, artifactPath: String?): Map<String, Any> = tx.execute {
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=? FOR UPDATE", id)
        val app = deployments.application(p["application_id"] as UUID)
        val cmd = command ?: p["command"] as String
        val artifact = artifactPath ?: p["artifact_path"] as String
        require(cmd.isNotBlank() && cmd.length <= 2000 && '\u0000' !in cmd)
        ImagePublisher.validateArtifactPath(artifact)
        if (app.kind != "STANDALONE") require(if (app.kind == "ANDROID") artifact == "android-release.jar" else artifact != "android-release.jar")
        val changed = cmd != p["command"] || artifact != p["artifact_path"]
        if (changed) {
            val build = (p["build_id"] as UUID?)?.let(queue::get)
            val deployment = (p["deployment_id"] as UUID?)?.let(deployments::get)
            if (build?.status in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING, BuildStatus.CANCEL_REQUESTED) || deployment?.status in setOf("QUEUED", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED")) throw Conflict("Дождитесь или отмените текущий выпуск перед изменением команды")
        }
        // An enabled flag applies immediately; changed commands are used on the next commit/manual run.
        jdbc.update("UPDATE pipelines SET enabled=?,command=?,artifact_path=? WHERE id=?", enabled ?: p["enabled"], cmd, artifact, id)
        jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
    }!!
    private fun enqueue(id: UUID, source: SourceSnapshot, manual: Boolean, requestId: UUID = UUID.randomUUID(), releaseRequested: Boolean = false): Build {
        val initial = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        val application = deployments.application(initial["application_id"] as UUID)
        val fingerprint = if (application.kind == "ANDROID") {
            if (application.configuration["target"] == "prod") github.file(source, "mobile/version.properties").decodeToString().lineSequence().first { it.startsWith("versionName=") }.substringAfter('=').trim()
            else github.directorySha(source, "mobile")
        } else source.sha
        var selected = source
        if (application.kind == "ANDROID") {
            val url = java.net.URI.create(requireNotNull(application.configuration["url"]) + "/api/app/version")
            val response = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build().send(
                java.net.http.HttpRequest.newBuilder(url).timeout(java.time.Duration.ofSeconds(20)).GET().build(), limitedBody(16384))
            require(response.statusCode() == 200) { "Не удалось прочитать опубликованный versionCode; версия не угадывается" }
            val node = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(response.body())
            val published = node["latestVersionCode"] ?: node["versionCode"]
            require(published != null && published.isIntegralNumber)
            val code = Math.addExact(published.asInt(), 1)
            require(code in 1..2100000000)
            selected = source.copy(command = "export HUB_ANDROID_CODE=$code; " + source.command)
        }
        return tx.execute {
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=? FOR UPDATE", id)
        val active = (p["build_id"] as UUID?)?.let { queue.get(it) }
        if (releaseRequested) {
            val deployment = (p["deployment_id"] as UUID?)?.let(deployments::get)
            val previousCommand = active?.source?.command?.replace(Regex("^export HUB_ANDROID_CODE=[0-9]+; "), "")
            val sameRecipe = previousCommand == source.command && active?.source?.artifactPath == source.artifactPath
            if (sameRecipe && active?.source?.sha == source.sha && active.status in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING, BuildStatus.SUCCEEDED)) {
                if (deployment?.status == "CANCELLED") throw Conflict("Этот выпуск отменён; для повторной выкладки используйте deploy_build с новым request_id")
                jdbc.update("UPDATE pipelines SET release_requested=true WHERE id=?", id)
                return@execute active
            }
            if (active?.status in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING, BuildStatus.CANCEL_REQUESTED) || deployment?.status in setOf("QUEUED", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED")) throw Conflict("Pipeline занят другой сборкой или незавершённым выпуском")
        }
        if (active?.status in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING, BuildStatus.CANCEL_REQUESTED)) return@execute active!!
        if (!manual && p["last_fingerprint"] == fingerprint && active != null) return@execute active
        val app = deployments.application(p["application_id"] as UUID)
        val b = queue.enqueue(app.projectId, requestId, selected)
        jdbc.update("UPDATE pipelines SET last_sha=?,last_fingerprint=?,build_id=?,deployment_id=NULL,release_requested=?,message='Сборка поставлена в очередь',checked_at=clock_timestamp() WHERE id=?", source.sha, fingerprint, b.id, releaseRequested, id)
        b
        }!!
    }
    @Scheduled(fixedDelay = 15000)
    fun tick() {
        if (!running.compareAndSet(false, true)) return
        Thread.ofVirtual().start {
            try {
                list().forEach { p ->
                    val id = p["id"] as UUID
                    try {
                        val build = (p["build_id"] as UUID?)?.let(queue::get)
                        if ((p["enabled"] == true || p["release_requested"] == true) && build?.status == BuildStatus.SUCCEEDED && build.image != null && p["deployment_id"] == null) {
                            val request = UUID.nameUUIDFromBytes("pipeline:$id:${build.id}".toByteArray())
                            val d = deployments.enqueue(p["application_id"] as UUID, NewDeployment(request, build.id))
                            jdbc.update("UPDATE pipelines SET deployment_id=?,message='Выпуск поставлен в очередь',checked_at=clock_timestamp() WHERE id=? AND build_id=?", d.id, id, build.id)
                        }
                        if (p["enabled"] == true && (p["checked_at"] == null || (p["checked_at"] as java.sql.Timestamp).toInstant().isBefore(java.time.Instant.now().minusSeconds(60))) && build?.status !in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING, BuildStatus.CANCEL_REQUESTED)) {
                            // Don't let a newer commit bypass an unresolved release of the previous one.
                            val deployment = (p["deployment_id"] as UUID?)?.let(deployments::get)
                            if (build?.status == BuildStatus.SUCCEEDED && (deployment == null || deployment.status in setOf("QUEUED", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED"))) return@forEach
                            val app = deployments.application(p["application_id"] as UUID)
                            val project = queue.project(app.projectId)
                            val source = github.resolve(requireNotNull(project.repositoryUrl), p["ref"] as String, p["command"] as String).copy(artifactPath = p["artifact_path"] as String)
                            if (source.sha != p["last_sha"]) enqueue(id, source, false)
                            jdbc.update("UPDATE pipelines SET checked_at=clock_timestamp() WHERE id=?", id)
                        }
                    } catch (error: Exception) {
                        val message = if (error is RepositoryFailure || error is Conflict) error.message else "Не удалось проверить pipeline; повторяем через 15 секунд"
                        jdbc.update("UPDATE pipelines SET message=?,checked_at=clock_timestamp() WHERE id=?", message?.take(500), id)
                    }
                }
            } finally { running.set(false) }
        }
    }
}

@RestController
@Profile("!worker & !deployer")
@RequestMapping("/api/pipelines")
class PipelineApi(private val service: PipelineService) {
    @GetMapping fun list() = service.list()
    @PostMapping fun create(@RequestBody input: NewPipeline) = service.create(input)
    @PostMapping("/{id}/run") fun run(@PathVariable id: UUID) = service.start(id)
    @PostMapping("/{id}/enabled") fun enabled(@PathVariable id: UUID, @RequestBody input: Map<String, Boolean>): Map<String, Boolean> {
        service.enabled(id, requireNotNull(input["enabled"]))
        return input
    }
}
