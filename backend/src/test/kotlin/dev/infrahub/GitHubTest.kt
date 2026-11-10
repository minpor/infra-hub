package dev.infrahub

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Flow

class GitHubTest {
    @Test fun `branch listing preserves slash names default branch and pagination`() {
        val http = mock(GitHubHttp::class.java)
        `when`(http.get(URI.create("https://api.github.com/repos/owner/example"), null, null, 2097152))
            .thenReturn(200 to """{"default_branch":"trunk"}""".toByteArray())
        val nodes = (1..100).joinToString(",") { """{"name":"feature/$it","commit":{"sha":"${"a".repeat(40)}"}}""" }
        `when`(http.get(URI.create("https://api.github.com/repos/owner/example/branches?per_page=100&page=2"), null, null, 2097152))
            .thenReturn(200 to "[$nodes]".toByteArray())
        val github = GitHub(http, jacksonObjectMapper(), "", "", "")
        val result = github.branches("git@github.com:owner/example.git", 2)
        assertThat(result.defaultBranch).isEqualTo("trunk")
        assertThat(result.branches).hasSize(100)
        assertThat(result.branches.first()).isEqualTo(RepositoryBranch("feature/1", "a".repeat(40)))
        assertThat(result.hasMore).isTrue()
        assertThatThrownBy { github.branches("owner/example", 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `repository input cannot change host or inject credentials and paths`() {
        assertThat(GitHub.repositoryName("git@github.com:minpor/aukni.git")).isEqualTo("minpor/aukni")
        listOf("https://evil.test/a/b", "https://github.com@evil.test/a/b", "https://github.com/a/..", "a/b?token=x", "a/b/c").forEach { input ->
            assertThatThrownBy { GitHub.repositoryName(input) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `default branch resolves to a full immutable SHA`() {
        val http = mock(GitHubHttp::class.java)
        `when`(http.get(URI.create("https://api.github.com/repos/minpor/example"), null, null, 2097152))
            .thenReturn(200 to """{"default_branch":"dev"}""".toByteArray())
        val sha = "a".repeat(40)
        `when`(http.get(URI.create("https://api.github.com/repos/minpor/example/commits/dev"), null, null, 2097152))
            .thenReturn(200 to """{"sha":"$sha"}""".toByteArray())
        val github = GitHub(http, jacksonObjectMapper(), "", "", "")
        assertThat(github.resolve("https://github.com/minpor/example", "HEAD", "sh gradlew build"))
            .isEqualTo(SourceSnapshot("minpor/example", "dev", sha, "sh gradlew build"))
    }

    @Test fun `missing repository has a useful access error`() {
        val http = mock(GitHubHttp::class.java)
        `when`(http.get(URI.create("https://api.github.com/repos/minpor/example"), null, null, 2097152))
            .thenReturn(404 to "sensitive upstream body".toByteArray())
        assertThatThrownBy { GitHub(http, jacksonObjectMapper(), "", "", "").resolve("minpor/example", "HEAD", "true") }
            .isInstanceOf(RepositoryFailure::class.java).hasMessageContaining("GitHub App").hasMessageNotContaining("sensitive")
    }

    @Test fun `oversized response cancels subscription instead of consuming unbounded memory`() {
        val subscriber = limitedBody(3).apply(null)
        var cancelled = false
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() { cancelled = true }
        })
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
        assertThat(cancelled).isTrue()
        assertThat(subscriber.body.toCompletableFuture()).isCompletedExceptionally()
    }
}
