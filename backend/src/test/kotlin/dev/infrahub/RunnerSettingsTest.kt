package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class RunnerSettingsTest @Autowired constructor(private val settings: RunnerSettings) {
    @Test fun `persist resource limits and reject requests beyond the runner capacity without changing saved values`() {
        val previous = settings.resources()
        try {
            settings.save(RunnerResources(8, 16))
            assertThat(settings.resources()).isEqualTo(RunnerResources(8, 16))
            assertThat(settings.resources().memoryBytes).isEqualTo(17179869184L)
            assertThatThrownBy { settings.save(RunnerResources(21, 16)) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { settings.save(RunnerResources(8, 19)) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { settings.save(RunnerResources(0, 1)) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(settings.resources()).isEqualTo(RunnerResources(8, 16))
        } finally { settings.save(previous) }
    }
}
