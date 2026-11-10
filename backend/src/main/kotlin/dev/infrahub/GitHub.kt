package dev.infrahub

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

fun limitedBody(limit: Int): HttpResponse.BodyHandler<ByteArray> = HttpResponse.BodyHandler {
    val delegate = HttpResponse.BodySubscribers.ofByteArray()
    object : HttpResponse.BodySubscriber<ByteArray> {
        private var size = 0L
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = delegate.body
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; delegate.onSubscribe(value) }
        override fun onNext(items: List<ByteBuffer>) {
            size += items.sumOf { it.remaining().toLong() }
            if (size > limit) { subscription.cancel(); delegate.onError(RepositoryFailure("Архив или ответ GitHub превышает допустимый размер.")) }
            else delegate.onNext(items)
        }
        override fun onError(error: Throwable) = delegate.onError(error)
        override fun onComplete() = delegate.onComplete()
    }
}

class RepositoryFailure(message: String) : RuntimeException(message)
data class RepositoryBranch(val name: String, val sha: String)
data class RepositoryBranches(val defaultBranch: String, val branches: List<RepositoryBranch>, val hasMore: Boolean)
data class RepositoryWriteAccess(val contentsWrite: Boolean, val workflowsWrite: Boolean, val settingsUrl: String, val appName: String)

/** Fixed GitHub hosts, no automatic redirects and no response bodies in errors/logs. */
@Component
class GitHubHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun get(uri: URI, token: String? = null, body: String? = null, limit: Int = 2 * 1024 * 1024): Pair<Int, ByteArray> =
        exchange(uri, token, body, limit, if (body == null) "GET" else "POST")

    fun put(uri: URI, token: String, body: String): Pair<Int, ByteArray> = exchange(uri, token, body, 2 * 1024 * 1024, "PUT")

    private fun exchange(uri: URI, token: String?, body: String?, limit: Int, method: String): Pair<Int, ByteArray> {
        require(uri.scheme == "https" && uri.host in setOf("api.github.com", "codeload.github.com") && uri.port == -1 && uri.userInfo == null)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Infra-Hub")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        // ofByteArray honours the request timeout through the entire response; the subscriber also bounds memory.
        val response = try { client.send(builder.build(), limitedBody(limit)) }
        catch (error: Exception) { throw RepositoryFailure("Не удалось связаться с GitHub или получить ответ допустимого размера. Повторите запрос позже.") }
        if (response.statusCode() == 302) {
            require(uri.host == "api.github.com" && method == "GET")
            val target = URI.create(response.headers().firstValue("Location").orElseThrow())
            require(target.scheme == "https" && target.host == "codeload.github.com" && target.port == -1 && target.userInfo == null)
            // Signed archive redirect is a credential: never log it or forward the Authorization header.
            return get(target, limit = limit)
        }
        return response.statusCode() to response.body()
    }
}

@Component
@Profile("!worker")
class GitHub(
    private val http: GitHubHttp,
    private val json: ObjectMapper,
    @param:Value("\${hub.github-app-id:}") private val appId: String,
    @param:Value("\${hub.github-app-key-file:}") private val keyFile: String,
    @param:Value("\${hub.github-installation-id:}") private val installationId: String
) {
    fun configured() = appId.isNotBlank() && keyFile.isNotBlank() && installationId.isNotBlank()

    private fun jwt(): String {
        val pem = Files.readString(Path.of(keyFile))
        require(pem.contains("-----BEGIN PRIVATE KEY-----")) { "GitHub App requires a PKCS8 private key" }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(
            pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
        )))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val now = Instant.now().epochSecond
        val header = encoder.encodeToString(json.writeValueAsBytes(mapOf("alg" to "RS256", "typ" to "JWT")))
        val claims = encoder.encodeToString(json.writeValueAsBytes(mapOf("iat" to now - 60, "exp" to now + 300, "iss" to appId)))
        val unsigned = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(unsigned.toByteArray()) }.sign()
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    private fun request(path: String, token: String? = null, body: String? = null): JsonNode {
        val (status, bytes) = http.get(URI.create("https://api.github.com$path"), token, body)
        when (status) {
            200, 201 -> return json.readTree(bytes)
            404 -> throw RepositoryFailure("Репозиторий или ветка не найдены. Для приватного репозитория предоставьте GitHub App доступ к нему.")
            409 -> throw RepositoryFailure("Репозиторий пуст: сначала нужен хотя бы один commit.")
            401, 403, 422 -> throw RepositoryFailure("GitHub не разрешил доступ. Проверьте установку приложения, выбранные репозитории и лимит запросов.")
            else -> throw RepositoryFailure("GitHub временно недоступен (HTTP $status). Повторите запрос позже.")
        }
    }

    private fun token(repository: String, write: Boolean = false, workflows: Boolean = false): String? {
        if (!configured()) {
            if (write) throw RepositoryFailure("Для сохранения YAML настройте GitHub App с правом Contents: read and write.")
            return null
        }
        // Public repositories don't require installation membership or private credentials.
        if (!write && http.get(URI.create("https://api.github.com/repos/$repository")).first == 200) return null
        require(installationId.matches(Regex("[0-9]+")))
        // Check the app's installation belongs to this repository before issuing a narrowly scoped token.
        val jwt = jwt()
        val actual = request("/repos/$repository/installation", jwt)["id"].asText()
        if (actual != installationId) throw RepositoryFailure("Репозиторий не входит в настроенную установку GitHub App.")
        val permissions = mutableMapOf("contents" to if (write) "write" else "read")
        if (workflows) permissions["workflows"] = "write"
        return request("/app/installations/$installationId/access_tokens", jwt,
            json.writeValueAsString(mapOf("repositories" to listOf(repository.substringAfter('/')), "permissions" to permissions)))["token"].asText()
    }

    fun branches(url: String, page: Int): RepositoryBranches {
        require(page in 1..1000) { "Invalid branch page" }
        val repository = repositoryName(url)
        val access = token(repository)
        val metadata = request("/repos/$repository", access)
        val nodes = request("/repos/$repository/branches?per_page=100&page=$page", access)
        require(nodes.isArray) { "Invalid GitHub branches response" }
        val branches = nodes.map { node ->
            val name = node.path("name").asText()
            val sha = node.path("commit").path("sha").asText()
            require(name.isNotBlank() && sha.matches(Regex("[0-9a-f]{40}"))) { "Invalid GitHub branch" }
            RepositoryBranch(name, sha)
        }
        return RepositoryBranches(metadata.path("default_branch").asText(), branches, branches.size == 100)
    }

    fun writeAccess(url: String): RepositoryWriteAccess {
        if (!configured()) return RepositoryWriteAccess(false, false, "https://github.com/settings/apps", "GitHub App")
        val repository = repositoryName(url)
        val access = jwt()
        val installation = request("/repos/$repository/installation", access)
        require(installation.path("id").asText() == installationId)
        val app = request("/app", access)
        val slug = app.path("slug").asText()
        require(slug.matches(Regex("[A-Za-z0-9_-]+")))
        return RepositoryWriteAccess(installation.path("permissions").path("contents").asText() == "write",
            installation.path("permissions").path("workflows").asText() == "write", "https://github.com/settings/apps/$slug/permissions", app.path("name").asText())
    }

    fun resolve(url: String, ref: String, command: String): SourceSnapshot {
        val repository = repositoryName(url)
        require(ref.isNotBlank() && ref.length <= 200 && !ref.any { it.isISOControl() }) { "Укажите ветку, тег или SHA" }
        require(command.isNotBlank() && command.length <= 2000 && !command.contains('\u0000')) { "Укажите команду сборки (до 2000 символов)" }
        val token = token(repository)
        val metadata = request("/repos/$repository", token)
        val selected = if (ref == "HEAD") metadata["default_branch"].asText() else ref
        val sha = request("/repos/$repository/commits/${URLEncoder.encode(selected, Charsets.UTF_8).replace("+", "%20")}", token)["sha"].asText()
        check(sha.matches(Regex("[0-9a-f]{40}")))
        return SourceSnapshot(repository, selected, sha, command)
    }

    fun file(source: SourceSnapshot, path: String): ByteArray {
        require(path.matches(Regex("[A-Za-z0-9_./-]+")) && ".." !in path)
        val node = request("/repos/${source.repository}/contents/$path?ref=${source.sha}", token(source.repository))
        require(node["encoding"].asText() == "base64")
        return Base64.getMimeDecoder().decode(node["content"].asText())
    }

    fun saveYaml(source: SourceSnapshot, path: String, yaml: String): String {
        DeploymentYaml().path(path)
        require(yaml.toByteArray().size <= 65536)
        return saveFile(source, path, yaml)
    }

    fun sourceFiles(source: SourceSnapshot): Pair<List<String>, Boolean> {
        val node = request("/repos/${source.repository}/git/trees/${source.sha}?recursive=1", token(source.repository))
        val paths = node.path("tree").filter { it.path("type").asText() == "blob" && it.path("mode").asText() != "120000" }
            .map { it.path("path").asText() }.filter { runCatching { RepositorySourcePaths.validate(it) }.isSuccess }.sorted()
        return paths.take(1000) to (node.path("truncated").asBoolean() || paths.size > 1000)
    }

    fun saveFile(source: SourceSnapshot, path: String, content: String, create: Boolean = false): String {
        RepositorySourcePaths.validate(path)
        require(content.toByteArray().size <= 128 * 1024 && '\u0000' !in content) { "Source file is limited to 128 KiB" }
        require(source.ref != "HEAD" && !source.ref.matches(Regex("[a-fA-F0-9]{40}"))) { "Choose a branch name to save files" }
        val encodedRef = URLEncoder.encode(source.ref, Charsets.UTF_8).replace("+", "%20")
        val access = token(source.repository, write = true, workflows = path.startsWith(".github/workflows/"))!!
        val branch = request("/repos/${source.repository}/git/ref/heads/$encodedRef", access)
        if (branch.path("object").path("sha").asText() != source.sha) throw Conflict("Ветка изменилась. Загрузите YAML заново; ваши правки остаются в редакторе.")
        val (fileStatus, fileBytes) = http.get(URI.create("https://api.github.com/repos/${source.repository}/contents/$path?ref=${source.sha}"), access)
        if (create && fileStatus == 200) throw Conflict("Файл уже существует. Откройте его для редактирования.")
        if (!create && fileStatus == 404) throw Conflict("Файл удалён. Обновите список файлов; ваши правки остаются в редакторе.")
        if (!(fileStatus == 200 || create && fileStatus == 404)) throw RepositoryFailure("Не удалось проверить файл. Проверьте доступ GitHub App к репозиторию.")
        val body = mutableMapOf<String, Any>("message" to "${if (create) "Create" else "Update"} $path via Infra Hub", "branch" to source.ref,
            "content" to Base64.getEncoder().encodeToString(content.toByteArray()))
        if (!create) {
            val file = json.readTree(fileBytes)
            require(file.path("type").asText("file") == "file" && !file.has("target") && file.path("sha").asText().isNotBlank()) { "Only regular source files can be edited" }
            body["sha"] = file.path("sha").asText()
        }
        val (status, bytes) = http.put(URI.create("https://api.github.com/repos/${source.repository}/contents/$path"), access, json.writeValueAsString(body))
        when (status) {
            200, 201 -> return json.readTree(bytes).path("commit").path("sha").asText().also { require(it.matches(Regex("[0-9a-f]{40}"))) }
            409 -> throw Conflict("Файл изменился. Загрузите YAML заново; ваши правки остаются в редакторе.")
            422 -> if (create) throw Conflict("Не удалось создать файл: он мог появиться в ветке. Обновите список; черновик сохранён в редакторе.") else throw RepositoryFailure("Не удалось сохранить файл. Проверьте права GitHub App Contents / Workflows и защиту ветки.")
            401, 403, 404 -> throw RepositoryFailure("Не удалось сохранить файл. Проверьте права GitHub App Contents / Workflows и защиту ветки.")
            else -> throw RepositoryFailure("Не удалось сохранить файл (HTTP $status). Проверьте ветку перед повтором.")
        }
    }

    fun directorySha(source: SourceSnapshot, path: String): String {
        require(path.matches(Regex("[A-Za-z0-9_-]+")))
        val tree = request("/repos/${source.repository}/git/trees/${source.sha}", token(source.repository))["tree"]
        return tree.first { it["path"].asText() == path && it["type"].asText() == "tree" }["sha"].asText()
    }

    fun archive(source: SourceSnapshot): ByteArray {
        val token = token(source.repository) // Revalidate access at execution time, including revocation.
        val (status, bytes) = http.get(URI.create("https://api.github.com/repos/${source.repository}/tarball/${source.sha}"), token, limit = 32 * 1024 * 1024)
        if (status != 200) throw RepositoryFailure("Не удалось получить исходники: проверьте доступ GitHub App к репозиторию (HTTP $status).")
        return bytes
    }

    companion object {
        fun repositoryName(url: String): String {
            val normalized = url.trim().removePrefix("git@github.com:").removePrefix("https://github.com/").removeSuffix(".git")
            require(normalized.matches(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")) && normalized.substringAfter('/') !in setOf(".", "..")) {
                "Укажите репозиторий GitHub: https://github.com/owner/repository"
            }
            return normalized
        }
    }
}
