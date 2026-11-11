package dev.infrahub

import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class NewPipeline(val id: UUID, val applicationId: UUID, val ref: String, val command: String, val artifactPath: String, val enabled: Boolean = false, val configPath: String? = null, val environment: String? = null)
data class PipelineEnvironment(val environment: String? = null)
data class PipelineReleaseRequest(val requestId: UUID, val expectedRef: String? = null, val expectedEnvironment: String? = null)

@Service
@Profile("!worker & !deployer")
class PipelineService(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val queue: QueueStore, private val deployments: DeploymentStore, private val github: GitHub, private val yamlSources: YamlSources, private val parser: DeploymentYaml) {
    private val running = AtomicBoolean(false)
    fun list() = jdbc.queryForList("SELECT * FROM pipelines ORDER BY id")
    fun create(input: NewPipeline): Map<String, Any> = tx.execute {
        require(input.ref.isNotBlank() && input.ref.length <= 200 && !input.ref.any { it.isISOControl() })
        require(input.command.isNotBlank() && input.command.length <= 2000 && '\u0000' !in input.command)
        if (input.configPath == null) ImagePublisher.validateArtifactPath(input.artifactPath) else parser.path(input.configPath)
        val app = deployments.application(input.applicationId)
        val environment = input.environment ?: app.configuration["target"]?.takeIf { it in setOf("dev", "prod") }
        validateEnvironment(app, environment)
        if (input.configPath == null && app.kind != "STANDALONE") require(input.ref == app.configuration["ref"]) { "Ветка pipeline должна совпадать с окружением приложения" }
        jdbc.queryForList("SELECT * FROM pipelines WHERE id=?", input.id).firstOrNull()?.let { prior ->
            if (prior["application_id"] != input.applicationId || prior["ref"] != input.ref || prior["command"] != input.command || prior["artifact_path"] != input.artifactPath || prior["config_path"] != input.configPath || (input.environment != null && prior["environment"] != environment)) throw Conflict("ID pipeline уже использован другой конфигурацией")
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        if (input.enabled) requireNoOtherAutomation(input.applicationId, input.id)
        if (input.configPath != null && jdbc.queryForObject("SELECT count(*) FROM pipelines WHERE application_id=? AND ref=? AND config_path=? AND id<>?", Long::class.java, input.applicationId, input.ref, input.configPath, input.id) != 0L) throw Conflict("This YAML file is already attached to this application and ref")
        jdbc.update("INSERT INTO pipelines(id,application_id,ref,command,artifact_path,enabled,config_path,environment) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING", input.id, input.applicationId, input.ref, input.command, input.artifactPath, input.enabled, input.configPath, environment)
        jdbc.queryForMap("SELECT * FROM pipelines WHERE id = ?", input.id)
    }!!
    private fun validateEnvironment(app: HubApplication, environment: String?) {
        require(environment == null || environment in setOf("dev", "prod")) { "Choose dev or prod" }
        val target = app.configuration["target"]?.takeIf { it in setOf("dev", "prod") }
        require(target == null || environment == target) { "Environment must match the application target" }
    }
    fun environment(id: UUID, environment: String?) = tx.execute {
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=? FOR UPDATE", id)
        validateEnvironment(deployments.application(p["application_id"] as UUID), environment)
        jdbc.update("UPDATE pipelines SET environment=? WHERE id=?", environment, id)
        jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
    }!!
    private fun requireNoOtherAutomation(appId: UUID, id: UUID) {
        if (jdbc.queryForObject("SELECT count(*) FROM pipelines WHERE application_id=? AND id<>? AND enabled", Long::class.java, appId, id) != 0L) throw Conflict("Pause the other automatic pipeline for this application first")
    }
    fun enabled(id: UUID, enabled: Boolean) = tx.executeWithoutResult {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        if (enabled) requireNoOtherAutomation(p["application_id"] as UUID, id)
        jdbc.update("UPDATE pipelines SET enabled=? WHERE id=?", enabled, id)
    }
    fun registerYaml(input: YamlBinding): Map<String, Any> {
        require(input.ref != "HEAD") { "Choose an explicit branch, tag or commit for the YAML binding" }
        list().firstOrNull { it["id"] == input.id }?.let { prior ->
            if (prior["application_id"] != input.applicationId || prior["ref"] != input.ref || prior["config_path"] != input.path || (input.environment != null && prior["environment"] != input.environment)) throw Conflict("Pipeline ID belongs to another YAML binding")
            return prior
        }
        val source = yamlSources.source(input.applicationId, input.ref, input.path, input.id)
        if (source.sha != input.expectedSha) throw Conflict("The branch changed after preview; load the YAML again")
        return create(NewPipeline(input.id, input.applicationId, source.ref, ":", source.artifactPath ?: "", input.enabled, input.path, input.environment))
    }
    private fun source(p: Map<String, Any>): SourceSnapshot {
        val app = deployments.application(p["application_id"] as UUID)
        if (p["config_path"] != null) return yamlSources.source(app.id, p["ref"] as String, p["config_path"] as String, p["id"] as UUID)
        val project = queue.project(app.projectId)
        return github.resolve(requireNotNull(project.repositoryUrl), p["ref"] as String, p["command"] as String).copy(artifactPath = p["artifact_path"] as String)
    }
    private fun requestedRelease(id: UUID, requestId: UUID): Build? = jdbc.queryForList("SELECT pipeline_id,build_id FROM pipeline_release_requests WHERE request_id=?", requestId).firstOrNull()?.let {
        if (it["pipeline_id"] != id) throw Conflict("Request ID belongs to another pipeline")
        queue.get(it["build_id"] as UUID)
    }
    fun release(id: UUID, requestId: UUID, expectedRef: String? = null, expectedEnvironment: String? = null): Build {
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        fun checkSelection(pipeline: Map<String, Any>) {
            if (expectedRef != null && (pipeline["ref"] != expectedRef || pipeline["environment"] != expectedEnvironment)) {
                throw Conflict("Сценарий изменился. Откройте запуск заново и проверьте окружение и ветку.")
            }
        }
        checkSelection(p)
        requestedRelease(id, requestId)?.let { return it }
        val selected = source(p)
        return tx.execute {
            checkSelection(jdbc.queryForMap("SELECT * FROM pipelines WHERE id=? FOR UPDATE", id))
            requestedRelease(id, requestId)?.let { return@execute it }
            val build = enqueue(id, selected, true, requestId, true, retryTerminalDeployment = true)
            jdbc.update("INSERT INTO pipeline_release_requests(request_id,pipeline_id,build_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", requestId, id, build.id)
            requestedRelease(id, requestId)!!
        }!!
    }
    fun start(id: UUID): Build {
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        val app = deployments.application(p["application_id"] as UUID)
        val project = queue.project(app.projectId)
        val source = source(p)
        return enqueue(id, source, true)
    }
    fun ensureRelease(id: UUID, expectedSha: String, requestId: UUID): Build {
        require(expectedSha.matches(Regex("[a-f0-9]{40}"))) { "Укажите полный SHA опубликованного commit" }
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        val app = deployments.application(p["application_id"] as UUID)
        queue.requested(app.projectId, requestId)?.let { prior ->
            require(prior.source?.sha == expectedSha && prior.source.ref == p["ref"] && (p["config_path"] == null || prior.source.yamlRecipe?.pipelineId == id)) { "request_id уже принадлежит другой сборке" }
            return prior
        }
        val project = queue.project(app.projectId)
        val source = source(p)
        if (source.sha != expectedSha) throw Conflict("Ветка уже указывает на ${source.sha}; запрошен $expectedSha. Уточните commit перед выпуском")
        return enqueue(id, source, true, requestId, true)
    }
    fun configure(id: UUID, enabled: Boolean?, command: String?, artifactPath: String?, expectedCommand: String? = null, expectedArtifact: String? = null): Map<String, Any> = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        val p = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=? FOR UPDATE", id)
        val app = deployments.application(p["application_id"] as UUID)
        if (expectedCommand != null && (p["command"] != expectedCommand || p["artifact_path"] != expectedArtifact)) throw Conflict("Настройки сборки изменились. Обновите их перед сохранением; ваши правки остаются в редакторе.")
        if (p["config_path"] != null) require(command == null && artifactPath == null) { "Edit build commands and artifact in the repository YAML" }
        if (enabled == true) requireNoOtherAutomation(app.id, id)
        val cmd = command ?: p["command"] as String
        val artifact = artifactPath ?: p["artifact_path"] as String
        require(cmd.isNotBlank() && cmd.length <= 2000 && '\u0000' !in cmd)
        if (p["config_path"] == null) ImagePublisher.validateArtifactPath(artifact)
        if (p["config_path"] == null && app.kind != "STANDALONE") require(if (app.kind == "ANDROID") artifact == "android-release.jar" else artifact != "android-release.jar")
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
    private fun enqueue(id: UUID, source: SourceSnapshot, manual: Boolean, requestId: UUID = UUID.randomUUID(), releaseRequested: Boolean = false, retryTerminalDeployment: Boolean = false): Build {
        val initial = jdbc.queryForMap("SELECT * FROM pipelines WHERE id=?", id)
        val application = deployments.application(initial["application_id"] as UUID)
        val fingerprint = if (source.yamlRecipe == null && application.kind == "ANDROID") {
            if (application.configuration["target"] == "prod") github.file(source, "mobile/version.properties").decodeToString().lineSequence().first { it.startsWith("versionName=") }.substringAfter('=').trim()
            else github.directorySha(source, "mobile")
        } else source.sha
        var selected = source
        if (source.yamlRecipe == null && application.kind == "ANDROID") {
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
            val sameRecipe = previousCommand == source.command && active?.source?.artifactPath == source.artifactPath && active?.source?.yamlRecipe?.path == source.yamlRecipe?.path
            val completedExplicitRelease = retryTerminalDeployment && deployment?.status == "SUCCEEDED"
            if (!completedExplicitRelease && sameRecipe && active?.source?.sha == source.sha && active.status in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING, BuildStatus.SUCCEEDED) && !((source.yamlRecipe != null || retryTerminalDeployment) && deployment?.status in setOf("FAILED", "CANCELLED"))) {
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
                        if ((p["enabled"] == true || p["release_requested"] == true) && build?.status == BuildStatus.SUCCEEDED && (build.image != null || build.source?.yamlRecipe != null) && p["deployment_id"] == null) {
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
                            val source = source(p)
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
    @PostMapping("/{id}/release") fun release(@PathVariable id: UUID, @RequestBody input: PipelineReleaseRequest) = service.release(id, input.requestId, input.expectedRef, input.expectedEnvironment)
    @PostMapping("/{id}/environment") fun environment(@PathVariable id: UUID, @RequestBody input: PipelineEnvironment) = service.environment(id, input.environment)
    @PostMapping("/{id}/run") fun run(@PathVariable id: UUID) = service.start(id)
    @PostMapping("/{id}/enabled") fun enabled(@PathVariable id: UUID, @RequestBody input: Map<String, Boolean>): Map<String, Boolean> {
        service.enabled(id, requireNotNull(input["enabled"]))
        return input
    }
}
