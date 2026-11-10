package dev.infrahub

import org.junit.jupiter.api.Test
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import java.util.UUID

@ActiveProfiles("production")
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "hub.scheduling-enabled=false",
    "spring.datasource.url=\${HUB_TEST_DATABASE_URL}",
    "spring.security.oauth2.client.registration.github.client-id=test-id",
    "spring.security.oauth2.client.registration.github.client-secret=test-secret",
    "HUB_PUBLIC_URL=https://infra-hub.minpor.ru",
    "hub.owner-github-id=7196546"
])
class ProductionSecurityTest @Autowired constructor(private val mvc: MockMvc, private val json: ObjectMapper) {
    @Test fun `health is public while projects and session require login`() {
        mvc.get("/api/health").andExpect { status { isOk() } }
        mvc.get("/api/projects").andExpect { status { isUnauthorized() } }
        mvc.get("/api/applications").andExpect { status { isUnauthorized() } }
        mvc.get("/api/deployments").andExpect { status { isUnauthorized() } }
        mvc.get("/api/projects") { header("Accept", "*/*") }.andExpect { status { isUnauthorized() } }
        mvc.get("/api/servers").andExpect { status { isUnauthorized() } }
        mvc.get("/api/session").andExpect { status { isUnauthorized() } }
    }

    @Test fun `owner session cannot substitute for worker credentials`() {
        mvc.get("/internal/builds/${UUID.randomUUID()}/source") {
            with(oauth2Login())
            header("X-Worker-Id", UUID.randomUUID())
        }.andExpect { status { isUnauthorized() } }
    }

    @Test fun `server changes require CSRF even for a logged in user`() {
        listOf("/api/applications", "/api/applications/${UUID.randomUUID()}/deployments", "/api/applications/${UUID.randomUUID()}/rollback", "/api/deployments/${UUID.randomUUID()}/retry", "/api/servers", "/api/servers/${UUID.randomUUID()}/trust", "/api/servers/${UUID.randomUUID()}/check").forEach { path ->
            mvc.post(path) { with(oauth2Login()); contentType = org.springframework.http.MediaType.APPLICATION_JSON; content = "{}" }
                .andExpect { status { isForbidden() } }
        }
    }

    @Test fun `unauthenticated mutation cannot create a project`() {
        mvc.post("/api/projects") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"name":"forbidden"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test fun `token from csrf endpoint authorizes authenticated mutation`() {
        val response = mvc.get("/api/csrf") { with(oauth2Login()) }.andExpect { status { isOk() } }.andReturn().response
        val token = json.readTree(response.contentAsString)["token"].asText()
        val cookie = requireNotNull(response.getCookie("XSRF-TOKEN"))
        mvc.post("/api/projects") {
            with(oauth2Login())
            this.cookie(cookie)
            header("X-XSRF-TOKEN", token)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"name":"CSRF ${UUID.randomUUID()}"}"""
        }.andExpect { status { isCreated() } }
    }
}
