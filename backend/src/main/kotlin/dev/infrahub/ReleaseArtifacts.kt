package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@RestController
@Profile("!worker & !deployer")
class ReleaseArtifacts(private val store: DeploymentStore, @param:Value("\${hub.artifact-directory:.local/artifacts}") private val root: String) {
    private val names = setOf("aukni.apk", "aukni.aab", "mapping.txt", "metadata.json", "certificate.txt", "qa.zip")
    @GetMapping("/api/deployments/{id}/artifacts")
    fun list(@PathVariable id: UUID): List<Map<String, Any>> {
        store.get(id)
        val folder = Path.of(root).resolve(id.toString())
        return names.filter { Files.isRegularFile(folder.resolve(it)) }.map { mapOf("name" to it, "size" to Files.size(folder.resolve(it))) }
    }
    @GetMapping("/api/deployments/{id}/artifacts/{name}")
    fun download(@PathVariable id: UUID, @PathVariable name: String): ResponseEntity<FileSystemResource> {
        store.get(id)
        if (name !in names) throw MissingResource()
        val path = Path.of(root).resolve(id.toString()).resolve(name)
        if (!Files.isRegularFile(path)) throw MissingResource()
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Content-Disposition", "attachment; filename=\"$name\"")
            .contentLength(Files.size(path)).body(FileSystemResource(path))
    }
}
