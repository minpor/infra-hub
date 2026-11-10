package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID

@RestController
@Profile("!worker")
class WorkerSources(
    private val store: QueueStore,
    private val github: GitHub,
    @param:Value("\${hub.worker-secret:}") private val secret: String
) {
    @GetMapping("/internal/builds/{id}/source")
    fun source(@PathVariable id: UUID, @RequestHeader("X-Worker-Id") worker: UUID,
               @RequestHeader("X-Worker-Secret", defaultValue = "") supplied: String): ResponseEntity<ByteArray> {
        if (secret.length < 32 || !MessageDigest.isEqual(secret.toByteArray(), supplied.toByteArray())) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        if (!store.ownsLease(id, worker)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val source = store.get(id).source ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val archive = github.archive(source)
        if (!store.ownsLease(id, worker)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_OCTET_STREAM).body(archive)
    }
}
