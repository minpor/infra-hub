package dev.infrahub

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64

class GitHubYamlSaveTest {
    @TempDir lateinit var directory: Path
    private val http = mock(GitHubHttp::class.java)
    private val json = jacksonObjectMapper()
    private val source = SourceSnapshot("owner/project", "feature/build", "a".repeat(40), ":")
    private val path = ".infra-hub/production.yaml"
    private var branchSha = source.sha
    private lateinit var github: GitHub

    private fun anyUri(): URI = any(URI::class.java) ?: URI.create("https://api.github.com/")

    @BeforeEach fun prepare() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
        val keyFile = directory.resolve("app.pem")
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(key)}\n-----END PRIVATE KEY-----")
        github = GitHub(http, json, "123", keyFile.toString(), "42")
        `when`(http.get(anyUri(), anyString(), nullable(String::class.java), anyInt())).thenAnswer { call ->
            val uri = call.getArgument<URI>(0)
            val body = call.getArgument<String?>(2)
            when (uri.rawPath) {
                "/repos/owner/project/installation" -> 200 to """{"id":42}""".toByteArray()
                "/app/installations/42/access_tokens" -> {
                    assertThat(json.readTree(body).path("permissions").path("contents").asText()).isEqualTo("write")
                    201 to """{"token":"test-installation-token"}""".toByteArray()
                }
                "/repos/owner/project/git/ref/heads/feature%2Fbuild" -> 200 to """{"object":{"sha":"$branchSha"}}""".toByteArray()
                "/repos/owner/project/contents/$path" -> {
                    assertThat(uri.query).isEqualTo("ref=${source.sha}")
                    200 to """{"sha":"file-blob-sha"}""".toByteArray()
                }
                else -> error("Unexpected request path")
            }
        }
    }

    @Test fun `save explicitly targets the branch and protects the existing file with its blob SHA`() {
        `when`(http.put(anyUri(), anyString(), anyString())).thenAnswer { call ->
            assertThat(call.getArgument<URI>(0).path).isEqualTo("/repos/owner/project/contents/$path")
            val body = json.readTree(call.getArgument<String>(2))
            assertThat(body.path("branch").asText()).isEqualTo(source.ref)
            assertThat(body.path("sha").asText()).isEqualTo("file-blob-sha")
            assertThat(String(Base64.getDecoder().decode(body.path("content").asText()))).isEqualTo("name: Обновление\n")
            200 to """{"commit":{"sha":"${"c".repeat(40)}"}}""".toByteArray()
        }
        assertThat(github.saveYaml(source, path, "name: Обновление\n")).isEqualTo("c".repeat(40))
    }

    @Test fun `branch drift prevents writes and a concurrent file update returns a conflict`() {
        branchSha = "b".repeat(40)
        assertThatThrownBy { github.saveYaml(source, path, "name: Changed") }.isInstanceOf(Conflict::class.java)
        verify(http, never()).put(anyUri(), anyString(), anyString())
        branchSha = source.sha
        `when`(http.put(anyUri(), anyString(), anyString())).thenReturn(409 to "private upstream details".toByteArray())
        assertThatThrownBy { github.saveYaml(source, path, "name: Changed") }.isInstanceOf(Conflict::class.java).hasMessageNotContaining("private")
    }

    @Test fun `public repository writes still require a configured GitHub App`() {
        val public = GitHub(http, json, "", "", "")
        assertThatThrownBy { public.saveYaml(source, path, "name: Changed") }.isInstanceOf(RepositoryFailure::class.java).hasMessageContaining("Contents: read and write")
        verify(http, never()).put(anyUri(), anyString(), anyString())
    }
}
