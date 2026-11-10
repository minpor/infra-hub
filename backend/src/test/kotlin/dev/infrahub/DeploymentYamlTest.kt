package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeploymentYamlTest {
    private val parser = DeploymentYaml()
    private val valid = """
        version: 1
        name: Web production
        build:
          steps:
            - name: Tests
              run: printf 'test success'
        deploy:
          directory: /srv/example
          steps:
            - name: Switch
              run: ./release.sh
          verify:
            - name: Health
              run: ./health.sh
          rollback:
            - name: Restore
              run: ./rollback.sh
    """.trimIndent()
    @Test fun `named steps and optional artifact compile without interpreting shell text`() {
        val value = parser.parse(valid)
        assertThat(value.build.artifact).isNull()
        assertThat(value.deploy.directory).isEqualTo("/srv/example")
        assertThat(parser.command(value)).contains("timeout --kill-after=5s 300s sh -eu -c", "test success")
        assertThat(parser.hash(valid)).hasSize(64)
        assertThat(parser.path(".infra-hub/deploy/production.yaml")).isEqualTo(".infra-hub/deploy/production.yaml")
    }
    @Test fun `unsafe paths tags duplicates aliases and unknown fields fail before enqueue`() {
        listOf("../deploy.yml", "/deploy.yml", "https://example/deploy.yml", "a/../b.yaml").forEach { path ->
            assertThatThrownBy { parser.path(path) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf(
            valid.replace("version: 1", "version: 1\nversion: 1"),
            valid.replace("version: 1", "version: 2"),
            valid.replace("name: Web production", "name: &title Web production"),
            valid.replace("name: Web production", "name: !!java.lang.String exploit"),
            valid + "\nunknown: true",
            valid.replace("directory: /srv/example", "directory: /srv/../etc"),
            valid.replace("timeoutSeconds: 300", "timeoutSeconds: 9999").replace("name: Tests", "name: Tests\n      timeoutSeconds: 9999"),
            valid.replace("  verify:", "  invalidVerify:")
        ).forEach { bad -> assertThatThrownBy { parser.parse(bad) }.isInstanceOf(IllegalArgumentException::class.java) }
    }
}
