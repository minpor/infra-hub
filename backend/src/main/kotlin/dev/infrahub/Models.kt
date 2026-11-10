package dev.infrahub

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class Project(val id: UUID, val name: String, val repositoryUrl: String?, val createdAt: Instant)
data class NewProject(
    @field:NotBlank @field:Size(max = 80) val name: String,
    @field:Size(max = 240) val repositoryUrl: String? = null
)
data class NewBuild(
    val requestId: UUID,
    @field:Size(max = 200) val ref: String = "HEAD",
    @field:Size(max = 2000) val command: String = "sh ./gradlew --no-daemon build",
    @field:Size(max = 300) val artifactPath: String? = null
)
data class SourceSnapshot(val repository: String, val ref: String, val sha: String, val command: String, val artifactPath: String? = null)
enum class BuildStatus { QUEUED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
data class Build(
    val id: UUID,
    val projectId: UUID,
    val recipe: String,
    val status: BuildStatus,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val message: String?,
    val source: SourceSnapshot? = null,
    val image: PublishedImage? = null
)
data class Step(
    val position: Int,
    val name: String,
    val status: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val exitCode: Int?
)
data class LogEntry(val id: Long, val stepPosition: Int, val createdAt: Instant, val text: String)
data class BuildDetail(val build: Build, val steps: List<Step>)

class MissingResource : RuntimeException()
class Conflict(val explanation: String) : RuntimeException(explanation)

data class PublishedImage(val reference: String, val artifactSha256: String, val artifactSize: Long)
