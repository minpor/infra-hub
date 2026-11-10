package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class DeploymentStoreTest @Autowired constructor(private val store: DeploymentStore, private val queue: QueueStore, private val jdbc: JdbcTemplate) {
    private lateinit var project: Project
    private lateinit var app: HubApplication
    @BeforeEach fun setup() {
        check(jdbc.queryForObject("SELECT current_database()", String::class.java) == "infra_hub_test")
        jdbc.execute("TRUNCATE projects, servers CASCADE")
        project = queue.createProject(NewProject("Deploy test"))
        val server = UUID.randomUUID()
        jdbc.update("INSERT INTO servers(id,name,host,port,username,public_key,host_key,status) VALUES (?,?,'server.test',22,'deploy','ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr','ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr','READY')", server, "Test")
        app = store.create(NewApplication(UUID.randomUUID(), project.id, server, "Application", 18090, 8080, "/health", mapOf("SECRET" to "kept-private")))
    }
    private fun build(): Build {
        val b = queue.enqueue(project.id, UUID.randomUUID(), SourceSnapshot("owner/repo", "main", "a".repeat(40), "build"))
        jdbc.update("UPDATE builds SET status='SUCCEEDED',artifact_sha256=repeat('a',64),artifact_size=10,image_reference=? WHERE id=?", "localhost:5000/infra-hub/project@sha256:" + "b".repeat(64), b.id)
        return queue.get(b.id)
    }
    @Test fun `snapshot retry port ownership and environment privacy`() {
        assertThat(app.environmentKeys).containsExactly("SECRET")
        assertThat(app.toString()).doesNotContain("kept-private")
        val b = build()
        val id = UUID.randomUUID()
        val first = store.enqueue(app.id, NewDeployment(id, b.id))
        jdbc.update("UPDATE builds SET image_reference='changed' WHERE id=?", b.id)
        assertThat(store.enqueue(app.id, NewDeployment(id, b.id))).isEqualTo(first)
        assertThatThrownBy { store.enqueue(app.id, NewDeployment(UUID.randomUUID(), b.id)) }.isInstanceOf(Conflict::class.java)
        assertThatThrownBy { store.create(NewApplication(UUID.randomUUID(), project.id, app.serverId, "Other", 18090, 8080)) }.isInstanceOf(Conflict::class.java)
    }
    @Test fun `lost lease blocks another release until explicit same release reconciliation`() {
        val d = store.enqueue(app.id, NewDeployment(UUID.randomUUID(), build().id))
        val owner = UUID.randomUUID()
        assertThat(store.claim(owner)?.id).isEqualTo(d.id)
        jdbc.update("UPDATE deployments SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", d.id)
        assertThat(store.claim(UUID.randomUUID())).isNull()
        assertThat(store.get(d.id).status).isEqualTo("INTERRUPTED")
        store.finish(d.id, owner, "SUCCEEDED", "late")
        assertThat(store.application(app.id).currentDeployment).isNull()
        assertThatThrownBy { store.enqueue(app.id, NewDeployment(UUID.randomUUID(), build().id)) }.isInstanceOf(Conflict::class.java)
        store.retry(d.id)
        val next = UUID.randomUUID()
        assertThat(store.claim(next)?.id).isEqualTo(d.id)
        store.finish(d.id, next, "SUCCEEDED", "Confirmed")
        assertThat(store.application(app.id).currentDeployment).isEqualTo(d.id)
    }
    @Test fun `rollback is another immutable release and failure preserves current version`() {
        val first = store.enqueue(app.id, NewDeployment(UUID.randomUUID(), build().id))
        val owner = UUID.randomUUID()
        store.claim(owner); store.finish(first.id, owner, "SUCCEEDED", "OK")
        val second = store.enqueue(app.id, NewDeployment(UUID.randomUUID(), build().id))
        store.claim(owner); store.finish(second.id, owner, "SUCCEEDED", "OK")
        val rollback = store.rollback(app.id, UUID.randomUUID())
        assertThat(rollback.buildId).isEqualTo(first.buildId)
        store.claim(owner); store.finish(rollback.id, owner, "FAILED", "Old restored")
        assertThat(store.application(app.id).currentDeployment).isEqualTo(second.id)
    }
    @Test fun `unpublished build and foreign project cannot deploy`() {
        val noImage = queue.enqueue(project.id, UUID.randomUUID())
        assertThatThrownBy { store.enqueue(app.id, NewDeployment(UUID.randomUUID(), noImage.id)) }.isInstanceOf(Conflict::class.java)
        val other = queue.createProject(NewProject("Other"))
        val b = queue.enqueue(other.id, UUID.randomUUID())
        jdbc.update("UPDATE builds SET status='SUCCEEDED',image_reference='fake' WHERE id=?", b.id)
        assertThatThrownBy { store.enqueue(app.id, NewDeployment(UUID.randomUUID(), b.id)) }.isInstanceOf(Conflict::class.java)
    }
}
