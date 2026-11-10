package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class ImagePublisherTest @Autowired constructor(private val store: QueueStore, private val jdbc: JdbcTemplate) {
    @TempDir lateinit var directory: Path
    private val runtime = "eclipse-temurin@sha256:" + "a".repeat(64)

    @BeforeEach fun clear() {
        check(jdbc.queryForObject("SELECT current_database()", String::class.java) == "infra_hub_test")
        jdbc.execute("TRUNCATE build_logs, build_steps, builds, projects CASCADE")
    }

    @Test fun `rejects traversal glob shell input and mutable runtime`() {
        listOf("../app.jar", "/app.jar", "build/*.jar", "build/a.jar;id", "build//a.jar", "build/../a.jar").forEach { path ->
            assertThatThrownBy { ImagePublisher.validateArtifactPath(path) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        ImagePublisher.validateArtifactPath("backend/build/libs/app-1.0.jar")
        assertThatThrownBy { ImagePublisher.validateConfiguration("registry.example/team", "eclipse-temurin:21") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ImagePublisher.validateConfiguration("registry.example/team\nRUN bad", runtime) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun publish(pushExit: Int = 0): Build {
        val source = SourceSnapshot("owner/repo", "main", "b".repeat(40), "build", "backend/build/libs/app.jar")
        val project = store.createProject(NewProject("Images"))
        val build = store.enqueue(project.id, UUID.randomUUID(), source)
        val worker = UUID.randomUUID()
        store.claim(worker)
        val cli = directory.resolve("docker-fixture")
        Files.writeString(cli, """
            #!/usr/bin/python3
            import json, sys
            from pathlib import Path
            args = sys.argv[1:]
            with open('${directory}/calls', 'a') as f: f.write(json.dumps(args) + '\n')
            if args[0] == 'exec': sys.stdout.buffer.write(b'PK\x03\x04jar-bytes')
            elif args[0] == 'push': sys.exit($pushExit)
            elif args[:2] == ['image', 'inspect']:
                print(json.dumps([args[-1].rsplit(':', 1)[0] + '@sha256:' + 'c' * 64]))
        """.trimIndent())
        cli.toFile().setExecutable(true)
        val publisher = ImagePublisher(store, "localhost:5000/infra-hub", runtime, "")
        if (pushExit == 0) publisher.publish(build, worker, "build-container", directory, listOf(cli.toString()), ProcessExecutor())
        else assertThatThrownBy { publisher.publish(build, worker, "build-container", directory, listOf(cli.toString()), ProcessExecutor()) }.isInstanceOf(RunnerFailure::class.java)
        return store.get(build.id)
    }

    @Test fun `publication preserves immutable source and registry digest with artifact checksum`() {
        val result = publish()
        assertThat(result.source?.sha).isEqualTo("b".repeat(40))
        assertThat(result.image?.reference).isEqualTo("localhost:5000/infra-hub/${result.projectId}@sha256:" + "c".repeat(64))
        assertThat(result.image?.artifactSize).isEqualTo(13)
        assertThat(result.image?.artifactSha256).hasSize(64)
        assertThat(store.steps(result.id).drop(2).map { it.status }).containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED")
        assertThat(Files.readString(directory.resolve("image/Dockerfile"))).contains("USER 10001:10001").doesNotContain("RUN ")
        assertThat(Files.readString(directory.resolve("calls"))).contains("image\", \"rm")
        assertThatThrownBy { store.recordImage(result.id, UUID.randomUUID(), result.image!!) }.isInstanceOf(ExecutionStopped::class.java)
    }

    @Test fun `failed push leaves no published digest and cleans local image`() {
        val result = publish(17)
        assertThat(result.image).isNull()
        assertThat(store.steps(result.id).last().exitCode).isEqualTo(17)
        assertThat(Files.readString(directory.resolve("calls"))).contains("image\", \"rm")
    }
}
