package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["hub.scheduling-enabled=false", "spring.datasource.url=\${HUB_TEST_DATABASE_URL}"])
class RunnerSettingsTest @Autowired constructor(private val settings: RunnerSettings, private val queue: QueueStore) {
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

    @Test fun `project overrides isolate workloads while defaults and cache can be inherited again`() {
        val previous = settings.resources()
        val android = queue.createProject(NewProject("Android runner"))
        val service = queue.createProject(NewProject("Small service runner"))
        try {
            settings.save(RunnerResources(4, 8, false))
            assertThat(settings.resources(android.id)).isEqualTo(RunnerResources(4, 8, false))
            assertThat(settings.view(android.id)["inherited"]).isEqualTo(true)
            settings.save(RunnerResources(12, 18, true), android.id)
            settings.save(RunnerResources(2, 4, false), service.id)
            settings.save(RunnerResources(6, 10, true))
            assertThat(settings.resources(android.id)).isEqualTo(RunnerResources(12, 18, true))
            assertThat(settings.resources(service.id)).isEqualTo(RunnerResources(2, 4, false))
            assertThatThrownBy { settings.save(RunnerResources(21, 8), android.id) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(settings.resources(android.id).cpus).isEqualTo(12)
            assertThat(settings.reset(android.id)["inherited"]).isEqualTo(true)
            assertThat(settings.resources(android.id)).isEqualTo(RunnerResources(6, 10, true))
            assertThat(settings.resources(service.id)).isEqualTo(RunnerResources(2, 4, false))
        } finally { settings.save(previous) }
    }
}
