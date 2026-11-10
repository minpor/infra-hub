package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class NewApplication(
    val id: UUID, val projectId: UUID, val serverId: UUID, val name: String,
    val hostPort: Int, val containerPort: Int, val healthPath: String = "/",
    val environment: Map<String, String> = emptyMap(),
    val kind: String = "STANDALONE", val configuration: Map<String, String> = emptyMap()
)
data class HubApplication(
    val id: UUID, val projectId: UUID, val serverId: UUID, val name: String,
    val hostPort: Int?, val containerPort: Int, val healthPath: String, val environmentKeys: List<String>,
    val currentDeployment: UUID?, val kind: String = "STANDALONE", val configuration: Map<String, String> = emptyMap()
)
data class NewDeployment(val requestId: UUID, val buildId: UUID)
data class Deployment(
    val id: UUID, val applicationId: UUID, val buildId: UUID, val imageReference: String,
    val status: String, val message: String?, val createdAt: Instant, val finishedAt: Instant?,
    val startedAt: Instant? = null, val executionMillis: Long? = null
)

@Repository
class DeploymentStore(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val mapper: ObjectMapper, private val queue: QueueStore) {
    private fun row(rs: ResultSet) = Deployment(rs.getObject("id", UUID::class.java), rs.getObject("application_id", UUID::class.java),
        rs.getObject("build_id", UUID::class.java), rs.getString("image_reference"), rs.getString("status"), rs.getString("message"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("finished_at")?.toInstant(), rs.getTimestamp("started_at")?.toInstant(),
        (rs.getObject("execution_millis") as? Number)?.toLong()?.let { elapsed ->
            elapsed + (rs.getTimestamp("attempt_started_at")?.toInstant()?.let { start ->
                val end = minOf(Instant.now(), rs.getTimestamp("lease_until")?.toInstant() ?: start)
                java.time.Duration.between(start, end).toMillis().coerceAtLeast(0)
            } ?: 0)
        })
    fun applications(): List<HubApplication> = jdbc.query("SELECT * FROM applications ORDER BY created_at DESC") { rs, _ ->
        HubApplication(rs.getObject("id", UUID::class.java), rs.getObject("project_id", UUID::class.java), rs.getObject("server_id", UUID::class.java),
            rs.getString("name"), (rs.getObject("host_port") as Int?), rs.getInt("container_port"), rs.getString("health_path"),
            mapper.readTree(rs.getString("environment")).fieldNames().asSequence().toList().sorted(), rs.getObject("current_deployment", UUID::class.java), rs.getString("kind"), mapper.readValue(rs.getString("configuration"), object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}))
    }
    fun application(id: UUID) = applications().firstOrNull { it.id == id } ?: throw MissingResource()
    fun environment(id: UUID): Map<String, String> = mapper.readTree(jdbc.queryForObject("SELECT environment::text FROM applications WHERE id = ?", String::class.java, id))
        .fields().asSequence().associate { it.key to it.value.asText() }
    fun create(input: NewApplication): HubApplication = tx.execute {
        require(input.name.trim().length in 1..80 && input.name.none { it.isISOControl() }) { "Укажите название приложения" }
        require(input.kind in setOf("STANDALONE", "COMPOSE", "ANDROID", "SCRIPT")) { "Неизвестный тип приложения" }
        if (input.kind in setOf("COMPOSE", "ANDROID")) {
            require(input.environment.isEmpty()) { "Секреты существующего приложения остаются на целевом сервере" }
            require(input.configuration.size <= 15 && input.configuration.values.all { it.length <= 300 && !it.any(Char::isISOControl) })
            if (input.kind == "ANDROID") {
                val c = input.configuration
                require(c.keys == setOf("target", "ref", "directory", "url", "certificate", "package"))
                require(c["certificate"]!!.matches(Regex("[a-f0-9]{64}")))
                if (c["target"] == "prod") require(c["ref"] == "main" && c["directory"] == "/root/docker/static/aukni-download" && c["url"] == "https://aukni.ru" && c["package"] == "ru.aukni.app")
                else require(c["target"] == "dev" && c["ref"] == "dev" && c["directory"] == "/opt/aukni-dev-download" && c["url"] == "https://aukni.minpor-dev.ru" && c["package"] == "ru.aukni.app.dev")
            }
            if (input.kind == "COMPOSE") {
                require(input.configuration.keys == setOf("root", "composeFile", "service", "site", "webService", "proxy", "url", "ref"))
                listOf("root", "site").forEach { key -> require(input.configuration[key]!!.matches(Regex("/opt/[A-Za-z0-9_./-]+|/root/docker/static/[A-Za-z0-9_./-]+")) && ".." !in input.configuration[key]!!) }
                require(input.configuration["composeFile"]!!.matches(Regex("[A-Za-z0-9_./-]+\\.ya?ml")) && ".." !in input.configuration["composeFile"]!!)
                listOf("service", "webService", "proxy").forEach { require(input.configuration[it]!!.matches(Regex("[a-zA-Z0-9_-]*"))) }
                require(input.configuration["service"]!!.isNotEmpty())
                require(input.configuration["url"]!!.matches(Regex("https://[a-zA-Z0-9.-]+")))
            }
        }
        require((input.kind == "SCRIPT" || input.hostPort in 1024..65535) && input.containerPort in 1..65535) { "Проверьте порты приложения" }
        require(input.healthPath.length <= 200 && input.healthPath.matches(Regex("/[A-Za-z0-9/_.~,-]*"))) { "Укажите HTTP-путь проверки здоровья без адреса сервера" }
        require(input.environment.size <= 50 && input.environment.all { (key, value) ->
            key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,79}")) && value.length <= 4096 && '\n' !in value && '\r' !in value && '\u0000' !in value
        } && input.environment.values.sumOf { it.length } <= 32000) { "До 50 переменных окружения, без переносов строк; общий размер до 32 KiB" }
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        applications().firstOrNull { it.id == input.id }?.let { existing ->
            require(existing.projectId == input.projectId && existing.serverId == input.serverId && existing.name == input.name.trim() &&
                existing.hostPort == (if (input.kind == "STANDALONE") input.hostPort else null) && existing.containerPort == input.containerPort && existing.healthPath == input.healthPath && existing.kind == input.kind && existing.configuration == input.configuration && environment(existing.id) == input.environment) { "Идентификатор уже использован другим приложением" }
            return@execute existing
        }
        if (jdbc.queryForObject("SELECT count(*) FROM projects WHERE id = ?", Long::class.java, input.projectId) != 1L) throw MissingResource()
        if (jdbc.queryForObject("SELECT count(*) FROM servers WHERE id = ? AND host_key IS NOT NULL AND (status = 'READY' OR (? AND status = 'SSH_ONLY'))", Long::class.java, input.serverId, input.kind == "SCRIPT") != 1L) throw Conflict("Выберите сервер с подтверждённым SSH и Docker")
        if (input.kind == "STANDALONE" && applications().any { it.serverId == input.serverId && it.hostPort == input.hostPort }) throw Conflict("Порт уже занят другим приложением Infra Hub")
        jdbc.update("INSERT INTO applications(id, project_id, server_id, name, host_port, container_port, health_path, environment, kind, configuration) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?::jsonb)",
            input.id, input.projectId, input.serverId, input.name.trim(), if (input.kind == "STANDALONE") input.hostPort else null, input.containerPort, input.healthPath, mapper.writeValueAsString(input.environment), input.kind, mapper.writeValueAsString(input.configuration))
        application(input.id)
    }!!
    fun list(): List<Deployment> = jdbc.query("SELECT * FROM deployments ORDER BY created_at DESC LIMIT 200") { rs, _ -> row(rs) }
    fun get(id: UUID): Deployment = jdbc.query("SELECT * FROM deployments WHERE id = ?", { rs, _ -> row(rs) }, id).firstOrNull() ?: throw MissingResource()
    fun enqueue(appId: UUID, input: NewDeployment): Deployment = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        jdbc.query("SELECT * FROM deployments WHERE id = ?", { rs, _ -> row(rs) }, input.requestId).firstOrNull()?.let {
            if (it.applicationId != appId || it.buildId != input.buildId) throw Conflict("Идентификатор уже использован другим деплоем")
            return@execute it
        }
        val app = application(appId)
        if (jdbc.queryForObject("SELECT count(*) FROM deployments WHERE application_id = ? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','INTERRUPTED')", Long::class.java, appId) != 0L) throw Conflict("Предыдущий деплой ещё не завершён. Для прерванного запуска нажмите «Сверить и повторить»")
        if (jdbc.queryForObject("SELECT count(*) FROM builds WHERE id=? AND project_id=? AND status='SUCCEEDED'", Long::class.java, input.buildId, app.projectId) != 1L) throw Conflict("Выберите успешную сборку этого проекта с опубликованным образом")
        val build = queue.get(input.buildId)
        if (build.projectId != app.projectId || build.status != BuildStatus.SUCCEEDED) throw Conflict("Выберите успешную сборку этого проекта с опубликованным образом")
        val yaml = build.source?.yamlRecipe
        if (yaml != null) require(yaml.applicationId == appId) { "YAML build belongs to a different application" }
        if (app.kind == "SCRIPT") require(yaml != null) { "Script applications require a YAML build" }
        if (yaml == null && app.kind != "STANDALONE") {
            val sourceRef = jdbc.queryForObject("SELECT source_ref FROM builds WHERE id=?", String::class.java, input.buildId)
            val artifact = jdbc.queryForObject("SELECT artifact_path FROM builds WHERE id=?", String::class.java, input.buildId)
            require(if (app.kind == "ANDROID") artifact == "android-release.jar" else artifact != "android-release.jar") { "Тип артефакта не соответствует приложению" }
            require(sourceRef == app.configuration["ref"]) { "Ветка сборки не соответствует окружению приложения" }
        }
        val reference = build.image?.reference ?: if (yaml != null && yaml.definition.build.artifact == null) "source:${build.source.sha}" else throw Conflict("Выберите успешную сборку этого проекта с опубликованным образом")
        val execution = yaml?.let { YamlExecution(it, environment(appId)) }
        jdbc.update("INSERT INTO deployments(id,application_id,build_id,image_reference,yaml_execution) VALUES (?,?,?,?,?::jsonb)", input.requestId, appId, input.buildId, reference, execution?.let(mapper::writeValueAsString))
        get(input.requestId)
    }!!
    fun yamlExecution(id: UUID): YamlExecution? = jdbc.queryForObject("SELECT yaml_execution::text FROM deployments WHERE id=?", String::class.java, id)?.let { mapper.readValue(it, YamlExecution::class.java) }
    fun rollback(appId: UUID, requestId: UUID): Deployment = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        jdbc.query("SELECT * FROM deployments WHERE id = ?", { rs, _ -> row(rs) }, requestId).firstOrNull()?.let {
            if (it.applicationId != appId) throw Conflict("Идентификатор уже использован другим приложением")
            return@execute it
        }
        val app = application(appId)
        require(app.kind != "ANDROID") { "Android требует нового большего versionCode; откат кода APK не поддерживается" }
        val current = app.currentDeployment?.let(::get) ?: throw Conflict("У приложения ещё нет работающего выпуска")
        val previous = jdbc.query("SELECT * FROM deployments WHERE application_id = ? AND status = 'SUCCEEDED' AND build_id <> ? ORDER BY created_at DESC LIMIT 1", { rs, _ -> row(rs) }, appId, current.buildId).firstOrNull()
            ?: throw Conflict("Предыдущего успешного выпуска пока нет")
        enqueue(appId, NewDeployment(requestId, previous.buildId))
    }!!
    fun retry(id: UUID): Deployment = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        get(id)
        jdbc.update("UPDATE deployments SET status = 'QUEUED', message = 'Повторная сверка того же выпуска', lease_owner = NULL, lease_until = NULL, finished_at = NULL WHERE id = ? AND status = 'INTERRUPTED'", id)
        get(id)
    }!!
    fun claim(owner: UUID): Deployment? = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        jdbc.update("UPDATE deployments SET execution_millis=execution_millis + GREATEST(0, (EXTRACT(EPOCH FROM (LEAST(clock_timestamp(),lease_until)-attempt_started_at))*1000)::bigint), attempt_started_at=NULL, status = 'INTERRUPTED', message = 'Потеряна связь с исполнителем. Сверьте и повторите тот же выпуск.', finished_at = clock_timestamp(), lease_until = NULL WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND lease_until < clock_timestamp()")
        if (jdbc.queryForObject("SELECT count(*) FROM deployments WHERE status IN ('RUNNING','CANCEL_REQUESTED')", Long::class.java) != 0L) return@execute null
        val next = jdbc.query("SELECT * FROM deployments WHERE status = 'QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED") { rs, _ -> row(rs) }.firstOrNull() ?: return@execute null
        jdbc.update("UPDATE deployments SET started_at=COALESCE(started_at,clock_timestamp()),attempt_started_at=clock_timestamp(),execution_millis=COALESCE(execution_millis,0),status = 'RUNNING', lease_owner = ?, lease_until = clock_timestamp() + interval '15 seconds' WHERE id = ?", owner, next.id)
        get(next.id)
    }
    fun cancel(id: UUID): Deployment = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821902)")
        get(id)
        jdbc.update("UPDATE deployments SET status='CANCELLED',message='Выпуск отменён до запуска',finished_at=clock_timestamp() WHERE id=? AND status='QUEUED'", id)
        jdbc.update("UPDATE deployments SET status='CANCEL_REQUESTED',message='Запрошена отмена; идёт сверка безопасной точки переключения' WHERE id=? AND status='RUNNING'", id)
        get(id)
    }!!
    fun heartbeat(id: UUID, owner: UUID): BuildStatus? {
        val status = jdbc.query("UPDATE deployments SET lease_until=clock_timestamp()+interval '15 seconds' WHERE id=? AND lease_owner=? AND status IN ('RUNNING','CANCEL_REQUESTED') AND lease_until>clock_timestamp() RETURNING status", { rs, _ -> rs.getString(1) }, id, owner).firstOrNull()
        return when (status) { "RUNNING" -> BuildStatus.RUNNING; "CANCEL_REQUESTED" -> BuildStatus.CANCEL_REQUESTED; else -> null }
    }
    fun log(id: UUID, owner: UUID, text: String) {
        jdbc.update("INSERT INTO deployment_logs(deployment_id,text) SELECT id,? FROM deployments WHERE id = ? AND lease_owner = ? AND status IN ('RUNNING','CANCEL_REQUESTED') AND lease_until > clock_timestamp()", text.take(8192), id, owner)
    }
    fun logs(id: UUID, after: Long = 0): List<Map<String, Any>> { require(after >= 0); get(id); return jdbc.queryForList("SELECT id,created_at,text FROM deployment_logs WHERE deployment_id = ? AND id > ? ORDER BY id LIMIT 300", id, after) }
    fun finish(id: UUID, owner: UUID, status: String, message: String) = tx.executeWithoutResult {
        require(status in setOf("SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED"))
        val changed = jdbc.update("UPDATE deployments SET execution_millis=execution_millis + GREATEST(0, (EXTRACT(EPOCH FROM (LEAST(clock_timestamp(),lease_until)-attempt_started_at))*1000)::bigint), attempt_started_at=NULL, status = ?, message = ?, finished_at = clock_timestamp(), lease_until = NULL WHERE id = ? AND lease_owner = ? AND status IN ('RUNNING','CANCEL_REQUESTED') AND lease_until > clock_timestamp()", status, message.take(1000), id, owner)
        if (changed == 1 && status == "SUCCEEDED") jdbc.update("UPDATE applications SET current_deployment = ? WHERE id = ?", id, get(id).applicationId)
    }
}

@RestController
@Profile("!worker")
@RequestMapping("/api")
class DeploymentApi(private val store: DeploymentStore) {
    @GetMapping("/applications") fun applications() = store.applications()
    @PostMapping("/applications") fun create(@RequestBody input: NewApplication) = store.create(input)
    @GetMapping("/deployments") fun list() = store.list()
    @GetMapping("/deployments/{id}/logs") fun logs(@PathVariable id: UUID) = store.logs(id)
    @PostMapping("/applications/{id}/deployments") fun deploy(@PathVariable id: UUID, @RequestBody input: NewDeployment) = store.enqueue(id, input)
    @PostMapping("/applications/{id}/rollback") fun rollback(@PathVariable id: UUID, @RequestBody input: Map<String, UUID>) = store.rollback(id, requireNotNull(input["requestId"]))
    @PostMapping("/deployments/{id}/cancel") fun cancel(@PathVariable id: UUID) = store.cancel(id)
    @PostMapping("/deployments/{id}/retry") fun retry(@PathVariable id: UUID) = store.retry(id)
}
