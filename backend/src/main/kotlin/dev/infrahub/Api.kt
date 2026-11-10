package dev.infrahub

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Profile("!worker")
@RestController
@RequestMapping("/api")
class Api(private val store: QueueStore, private val environment: Environment, private val github: GitHub) {
    @GetMapping("/github") fun githubStatus() = mapOf("appConfigured" to github.configured())
    @GetMapping("/health") fun health() = mapOf("status" to "UP", "mode" to if (environment.acceptsProfiles(Profiles.of("production"))) "PRODUCTION" else "LOCAL_DEVELOPMENT")
    @GetMapping("/overview") fun overview() = store.overview()
    @GetMapping("/projects") fun projects() = store.projects()
    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    fun createProject(@Valid @RequestBody input: NewProject) = store.createProject(input)
    @GetMapping("/builds") fun builds() = store.builds()
    @PostMapping("/projects/{id}/builds") @ResponseStatus(HttpStatus.ACCEPTED)
    fun enqueue(@PathVariable id: UUID, @Valid @RequestBody input: NewBuild): Build {
        // Return the original snapshot before resolving a moving branch or contacting GitHub on retry.
        store.requested(id, input.requestId)?.let { return it }
        val project = store.project(id)
        val artifact = input.artifactPath?.trim()?.takeIf { it.isNotEmpty() }
        artifact?.let { ImagePublisher.validateArtifactPath(it) }
        require(artifact == null || project.repositoryUrl != null) { "Для публикации нужен репозиторий" }
        val source = project.repositoryUrl?.let { github.resolve(it, input.ref, input.command).copy(artifactPath = artifact) }
        return store.enqueue(id, input.requestId, source)
    }
    @GetMapping("/builds/{id}") fun detail(@PathVariable id: UUID) = BuildDetail(store.get(id), store.steps(id))
    @GetMapping("/builds/{id}/logs") fun logs(@PathVariable id: UUID, @RequestParam(defaultValue = "0") after: Long): List<LogEntry> {
        require(after >= 0) { "Некорректная позиция журнала" }
        return store.logs(id, after)
    }
    @PostMapping("/builds/{id}/cancel") fun cancel(@PathVariable id: UUID) = store.cancel(id)
    @Scheduled(fixedDelay = 3000) fun expireLeases() = store.expire()
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(RepositoryFailure::class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun repository(error: RepositoryFailure) = mapOf("message" to error.message)
    @ExceptionHandler(MissingResource::class) @ResponseStatus(HttpStatus.NOT_FOUND)
    fun missing() = mapOf("message" to "Запись не найдена")
    @ExceptionHandler(DuplicateKeyException::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun duplicate() = mapOf("message" to "Проект с таким названием уже существует")
    @ExceptionHandler(Conflict::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict(error: Conflict) = mapOf("message" to error.explanation)
    @ExceptionHandler(IllegalArgumentException::class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: IllegalArgumentException) = mapOf("message" to (error.message ?: "Некорректные данные"))
    @ExceptionHandler(MethodArgumentNotValidException::class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun validation() = mapOf("message" to "Проверьте название проекта и длину полей")
}

// Local bootstrap only: no credentials, CORS or public interface until GitHub login is implemented.
@Component
@Profile("!worker & !production")
class LocalOnlyFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.requestURI.startsWith("/internal/") || request.requestURI == "/mcp" || request.requestURI.startsWith("/mcp/")) { chain.doFilter(request, response); return }
        val host = request.serverName
        val origin = request.getHeader("Origin")
        val ownOrigin = "http://${request.getHeader("Host")}" // Vite preserves its Host when proxying /api.
        if (host !in setOf("localhost", "127.0.0.1", "[::1]", "::1") || (origin != null && origin != ownOrigin) ||
            request.getHeader("Sec-Fetch-Site") == "cross-site" ||
            (request.method !in setOf("GET", "HEAD") && request.getHeader("X-Infra-Hub") != "local-ui")) {
            response.sendError(403)
            return
        }
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Cache-Control", "no-store")
        chain.doFilter(request, response)
    }
}
