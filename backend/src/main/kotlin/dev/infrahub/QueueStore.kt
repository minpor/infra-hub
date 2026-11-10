package dev.infrahub

import com.fasterxml.jackson.databind.ObjectMapper

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.util.UUID

@Repository
class QueueStore(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val mapper: ObjectMapper) {
    fun overview(): Map<String, Long> = mapOf(
        "projects" to (jdbc.queryForObject("SELECT count(*) FROM projects", Long::class.java) ?: 0L),
        "running" to (jdbc.queryForObject("SELECT count(*) FROM builds WHERE status IN ('RUNNING', 'CANCEL_REQUESTED')", Long::class.java) ?: 0L),
        "queued" to (jdbc.queryForObject("SELECT count(*) FROM builds WHERE status = 'QUEUED'", Long::class.java) ?: 0L),
        "buildMillis" to (jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(0,EXTRACT(EPOCH FROM (COALESCE(finished_at,LEAST(clock_timestamp(),lease_until))-started_at))*1000)),0)::bigint FROM builds WHERE started_at IS NOT NULL", Long::class.java) ?: 0L),
        "deploymentMillis" to (jdbc.queryForObject("SELECT COALESCE(SUM(execution_millis + COALESCE(GREATEST(0,EXTRACT(EPOCH FROM (LEAST(clock_timestamp(),lease_until)-attempt_started_at))*1000),0)),0)::bigint FROM deployments", Long::class.java) ?: 0L),
        "unknownDeploymentTimes" to (jdbc.queryForObject("SELECT count(*) FROM deployments WHERE execution_millis IS NULL", Long::class.java) ?: 0L)
    )

    private fun build(rs: ResultSet) = Build(
        rs.getObject("id", UUID::class.java), rs.getObject("project_id", UUID::class.java),
        rs.getString("recipe"), BuildStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("started_at")?.toInstant(),
        rs.getTimestamp("finished_at")?.toInstant(), rs.getString("message"),
        rs.getString("repository")?.let { SourceSnapshot(it, rs.getString("source_ref"), rs.getString("commit_sha"), rs.getString("build_command"), rs.getString("artifact_path"), rs.getString("yaml_recipe")?.let { yaml -> mapper.readValue(yaml, YamlRecipe::class.java) }) },
        rs.getString("image_reference")?.let { PublishedImage(it, rs.getString("artifact_sha256"), rs.getLong("artifact_size")) },
        rs.getString("runner_execution")?.let { mapper.readValue(it, RunnerExecution::class.java) }
    )

    fun projects(): List<Project> = jdbc.query("SELECT * FROM projects ORDER BY created_at DESC, id") { rs, _ ->
        Project(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("repository_url"), rs.getTimestamp("created_at").toInstant())
    }

    fun createProject(input: NewProject): Project {
        val name = input.name.trim()
        require(name.isNotEmpty()) { "Укажите название проекта" }
        val url = input.repositoryUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://github.com/${GitHub.repositoryName(it)}" }
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO projects(id, name, repository_url) VALUES (?, ?, ?)", id, name, url)
        return projects().first { it.id == id }
    }

    fun builds(): List<Build> = jdbc.query("SELECT * FROM builds ORDER BY created_at DESC, id LIMIT 100") { rs, _ -> build(rs) }
    fun project(id: UUID): Project = projects().firstOrNull { it.id == id } ?: throw MissingResource()
    fun requested(project: UUID, request: UUID): Build? = jdbc.query("SELECT * FROM builds WHERE request_id = ?", { rs, _ -> build(rs) }, request).firstOrNull()?.also {
        if (it.projectId != project) throw Conflict("Этот идентификатор запуска уже использован другим проектом")
    }
    fun ownsLease(build: UUID, worker: UUID): Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM builds WHERE id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > clock_timestamp()",
        Long::class.java, build, worker
    ) == 1L
    fun hasLease(build: UUID): Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM builds WHERE id = ? AND status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until > clock_timestamp()",
        Long::class.java, build
    ) == 1L
    fun get(id: UUID): Build = jdbc.query("SELECT * FROM builds WHERE id = ?", { rs, _ -> build(rs) }, id).firstOrNull() ?: throw MissingResource()
    fun steps(id: UUID): List<Step> = jdbc.query("SELECT * FROM build_steps WHERE build_id = ? ORDER BY position", { rs, _ ->
        Step(rs.getInt("position"), rs.getString("name"), rs.getString("status"), rs.getTimestamp("started_at")?.toInstant(),
            rs.getTimestamp("finished_at")?.toInstant(), rs.getObject("exit_code") as Int?)
    }, id)
    fun logs(id: UUID, after: Long): List<LogEntry> {
        get(id)
        return jdbc.query("SELECT * FROM build_logs WHERE build_id = ? AND id > ? ORDER BY id LIMIT 200", { rs, _ ->
            LogEntry(rs.getLong("id"), rs.getInt("step_position"), rs.getTimestamp("created_at").toInstant(), rs.getString("text"))
        }, id, after)
    }

    fun enqueue(project: UUID, request: UUID, source: SourceSnapshot? = null): Build = tx.execute {
        val id = UUID.randomUUID()
        if (jdbc.queryForObject("SELECT count(*) FROM projects WHERE id = ?", Long::class.java, project) == 0L) throw MissingResource()
        val inserted = jdbc.update("""
            INSERT INTO builds(id, project_id, request_id, recipe, repository, source_ref, commit_sha, build_command, artifact_path, yaml_recipe) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (request_id) DO NOTHING
        """.trimIndent(), id, project, request, if (source == null) "JAVA_EXAMPLE_V1" else "REPOSITORY_V1", source?.repository, source?.ref, source?.sha, source?.command, source?.artifactPath, source?.yamlRecipe?.let(mapper::writeValueAsString))
        val result = jdbc.query("SELECT * FROM builds WHERE request_id = ?", { rs, _ -> build(rs) }, request).single()
        if (result.projectId != project) throw Conflict("Этот идентификатор запуска уже использован другим проектом")
        if (inserted == 1) (if (source == null) listOf("Компиляция", "Тесты", "Упаковка JAR") else listOf("Исходники GitHub", "Сборка и тесты") + (if (source.artifactPath != null) listOf("Сохранение JAR", "Docker-образ", "Публикация образа") else emptyList())).forEachIndexed { index, name ->
            jdbc.update("INSERT INTO build_steps(build_id, position, name) VALUES (?, ?, ?)", id, index, name)
        }
        result
    }!!

    fun recordImage(id: UUID, worker: UUID, image: PublishedImage) {
        if (jdbc.update("""
            UPDATE builds SET image_reference = ?, artifact_sha256 = ?, artifact_size = ?
            WHERE id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > clock_timestamp()
        """.trimIndent(), image.reference, image.artifactSha256, image.artifactSize, id, worker) != 1) {
            throw ExecutionStopped(false)
        }
    }

    fun recordRunner(id: UUID, worker: UUID, execution: RunnerExecution) {
        if (jdbc.update("""
            UPDATE builds SET runner_execution=?::jsonb
            WHERE id=? AND worker_id=? AND status='RUNNING' AND lease_until > clock_timestamp()
        """.trimIndent(), mapper.writeValueAsString(execution), id, worker) != 1) throw ExecutionStopped(false)
    }

    fun cancel(id: UUID): Build = tx.execute {
        val current = jdbc.query("SELECT * FROM builds WHERE id = ? FOR UPDATE", { rs, _ -> build(rs) }, id).firstOrNull() ?: throw MissingResource()
        when (current.status) {
            BuildStatus.QUEUED -> {
                jdbc.update("UPDATE builds SET status = 'CANCELLED', finished_at = clock_timestamp(), message = 'Отменена до запуска' WHERE id = ?", id)
                jdbc.update("UPDATE build_steps SET status = 'CANCELLED', finished_at = clock_timestamp() WHERE build_id = ?", id)
            }
            BuildStatus.RUNNING -> jdbc.update("UPDATE builds SET status = 'CANCEL_REQUESTED' WHERE id = ?", id)
            else -> Unit
        }
        get(id)
    }!!

    // Serialize claims across worker processes. A partial unique index enforces the single execution lane too.
    fun claim(worker: UUID): Build? = tx.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821901)")
        expireLocked()
        if (jdbc.queryForObject("SELECT count(*) FROM builds WHERE status IN ('RUNNING', 'CANCEL_REQUESTED')", Long::class.java) != 0L) return@execute null
        val next = jdbc.query("SELECT * FROM builds WHERE status = 'QUEUED' ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED") { rs, _ -> build(rs) }.firstOrNull() ?: return@execute null
        jdbc.update("UPDATE builds SET status = 'RUNNING', worker_id = ?, started_at = clock_timestamp(), lease_until = clock_timestamp() + interval '15 seconds' WHERE id = ?", worker, next.id)
        get(next.id)
    }

    fun expire() = tx.executeWithoutResult {
        jdbc.execute("SELECT pg_advisory_xact_lock(4821901)")
        expireLocked()
    }

    private fun expireLocked() {
        val expired = jdbc.query("""
            UPDATE builds SET status = 'INTERRUPTED', finished_at = clock_timestamp(), lease_until = NULL,
                message = 'Потеряна связь с worker. Автоматический повтор отключён.'
            WHERE status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until < clock_timestamp() RETURNING id
        """.trimIndent()) { rs, _ -> rs.getObject("id", UUID::class.java) }
        expired.forEach { id -> jdbc.update("UPDATE build_steps SET status = 'INTERRUPTED', finished_at = clock_timestamp() WHERE build_id = ? AND status IN ('QUEUED', 'RUNNING')", id) }
    }

    fun heartbeat(id: UUID, worker: UUID): BuildStatus? = jdbc.query("""
        UPDATE builds SET lease_until = clock_timestamp() + interval '15 seconds'
        WHERE id = ? AND worker_id = ? AND status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until > clock_timestamp()
        RETURNING status
    """.trimIndent(), { rs, _ -> BuildStatus.valueOf(rs.getString(1)) }, id, worker).firstOrNull()

    fun startStep(id: UUID, worker: UUID, position: Int): Boolean = jdbc.update("""
        UPDATE build_steps SET status = 'RUNNING', started_at = clock_timestamp()
        WHERE build_id = ? AND position = ? AND status = 'QUEUED'
          AND EXISTS (SELECT 1 FROM builds WHERE id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > clock_timestamp())
    """.trimIndent(), id, position, id, worker) == 1

    fun completeStep(id: UUID, worker: UUID, position: Int, code: Int) {
        jdbc.update("""
            UPDATE build_steps SET status = ?, exit_code = ?, finished_at = clock_timestamp()
            WHERE build_id = ? AND position = ? AND status = 'RUNNING'
              AND EXISTS (SELECT 1 FROM builds WHERE id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > clock_timestamp())
        """.trimIndent(), if (code == 0) "SUCCEEDED" else "FAILED", code, id, position, id, worker)
    }

    fun appendLog(id: UUID, worker: UUID, position: Int, text: String) {
        jdbc.update("""
            INSERT INTO build_logs(build_id, step_position, text)
            SELECT id, ?, ? FROM builds WHERE id = ? AND worker_id = ?
                AND status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until > clock_timestamp()
        """.trimIndent(), position, text.take(8192), id, worker)
    }

    fun failRunningStep(id: UUID, worker: UUID, message: String) {
        val positions = jdbc.query("""
            UPDATE build_steps SET status = 'FAILED', finished_at = clock_timestamp()
            WHERE build_id = ? AND status = 'RUNNING'
              AND EXISTS (SELECT 1 FROM builds WHERE id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > clock_timestamp())
            RETURNING position
        """.trimIndent(), { rs, _ -> rs.getInt(1) }, id, id, worker)
        positions.forEach { appendLog(id, worker, it, "\n$message\n") }
    }

    fun finish(id: UUID, worker: UUID, status: BuildStatus, message: String) = tx.executeWithoutResult {
        require(status in setOf(BuildStatus.SUCCEEDED, BuildStatus.FAILED, BuildStatus.CANCELLED, BuildStatus.INTERRUPTED))
        val changed = jdbc.update("""
            UPDATE builds SET status = CASE WHEN status = 'CANCEL_REQUESTED' THEN 'CANCELLED' ELSE ? END,
                message = CASE WHEN status = 'CANCEL_REQUESTED' THEN 'Отменена владельцем' ELSE ? END,
                finished_at = clock_timestamp(), lease_until = NULL
            WHERE id = ? AND worker_id = ? AND status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until > clock_timestamp()
        """.trimIndent(), status.name, message.take(500), id, worker)
        if (changed == 1) {
            val end = get(id).status
            jdbc.update("UPDATE build_steps SET status = ?, finished_at = clock_timestamp() WHERE build_id = ? AND status IN ('QUEUED', 'RUNNING')", if (end == BuildStatus.FAILED) "CANCELLED" else end.name, id)
        }
    }
}
