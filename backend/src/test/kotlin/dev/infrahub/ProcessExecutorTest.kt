package dev.infrahub

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText

class ProcessExecutorTest {
    @TempDir lateinit var directory: Path

    @Test fun `binary artifact output is bounded and stderr is excluded`() {
        val output = directory.resolve("artifact.jar")
        val exit = ProcessExecutor().runInput(listOf("/bin/sh", "-c", "printf 'PK'; echo diagnostic >&2"), directory, 5, null,
            { BuildStatus.RUNNING }, {}, output, 8)
        assertThat(exit).isZero()
        assertThat(output.readText()).isEqualTo("PK")
        assertThatThrownBy {
            ProcessExecutor().runInput(listOf("/bin/sh", "-c", "head -c 1024 /dev/zero"), directory, 5, null,
                { BuildStatus.RUNNING }, {}, output, 8)
        }.isInstanceOf(RunnerFailure::class.java)
    }

    @Test fun `captures both output streams and nonzero exit`() {
        val logs = StringBuilder()
        val exit = ProcessExecutor().run(listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 7"), directory, 5, { BuildStatus.RUNNING }, { logs.append(it) })
        assertThat(exit).isEqualTo(7)
        assertThat(logs.toString()).contains("out", "err")
    }

    @Test fun `cancellation kills the process`() {
        val calls = AtomicInteger()
        assertThatThrownBy {
            ProcessExecutor().run(listOf("/bin/sh", "-c", "echo $$ > pid; exec sleep 30"), directory, 10,
                { if (calls.incrementAndGet() > 2) BuildStatus.CANCEL_REQUESTED else BuildStatus.RUNNING }, {})
        }.isInstanceOf(ExecutionStopped::class.java)
        val pid = directory.resolve("pid").readText().trim().toLong()
        assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).isFalse()
    }

    @Test fun `timeout stops a long process`() {
        assertThatThrownBy {
            ProcessExecutor().run(listOf("/bin/sleep", "30"), directory, 0, { BuildStatus.RUNNING }, {})
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `output is capped without blocking the process`() {
        val logs = StringBuilder()
        val exit = ProcessExecutor().run(listOf("/bin/sh", "-c", "head -c 1048576 /dev/zero | tr '\\0' x"), directory, 5, { BuildStatus.RUNNING }, { logs.append(it) })
        assertThat(exit).isZero()
        assertThat(logs.length).isLessThan(270000)
        assertThat(logs.toString()).contains("превышен лимит")
    }

    @Test fun `build process does not inherit backend database settings`() {
        val logs = StringBuilder()
        ProcessExecutor().run(listOf("/usr/bin/env"), directory, 5, { BuildStatus.RUNNING }, { logs.append(it) })
        assertThat(logs.toString()).doesNotContain("HUB_DATABASE", "JAVA_HOME", "GRADLE_USER_HOME")
    }
}
