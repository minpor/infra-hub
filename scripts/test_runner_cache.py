"""Exercise the real runner entrypoint in an isolated filesystem without Docker/network."""
import io
import os
import re
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class RunnerCacheTest(unittest.TestCase):
    def test_project_cache_survives_runs_and_can_be_bypassed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cgroup = root / "cgroup"
            cgroup.mkdir()
            for name, value in {"memory.max": "4294967296", "pids.max": "512", "cpu.max": "200000 100000"}.items():
                (cgroup / name).write_text(value)
            bridge = root / "bridge.py"
            bridge.write_text("pass\n")
            cache = root / "project-cache"
            cache.mkdir()

            def run(index, enabled, command, storage=cache):
                work = root / f"work-{index}"
                work.mkdir()
                with tarfile.open(work / "source.tar.gz", "w:gz") as archive:
                    data = b"source file"
                    info = tarfile.TarInfo("repository/source.txt")
                    info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
                script = (ROOT / "deploy/runner/run-build").read_text()
                script = script.replace("/sys/fs/cgroup", str(cgroup)).replace("/work", str(work)).replace("/opt/hub/proxy-bridge.py", str(bridge))
                script = re.sub(r"/cache(?=/|[\s\"]|$)", str(storage), script)
                # Replace only the runner paths above; repository commands below use the exported paths.
                return subprocess.run(["sh", "-eu", "-c", script, "run-build", command], env=dict(os.environ, HOME=str(work / "home"), HUB_DEPENDENCY_CACHE=str(int(enabled)), HUB_EXPECTED_CPUS="2", HUB_EXPECTED_MEMORY_BYTES="4294967296"), capture_output=True, text=True)

            first = run(1, True, 'mkdir -p "$GRADLE_USER_HOME/wrapper/dists"; printf gradle > "$GRADLE_USER_HOME/wrapper/dists/gradle.zip"; printf library > "$GRADLE_USER_HOME/caches/library.jar"; printf npm > "$npm_config_cache/package"; printf private > "$GRADLE_USER_HOME/gradle.properties"')
            self.assertEqual(first.returncode, 0, first.stderr)
            second = run(2, True, 'test "$(cat "$GRADLE_USER_HOME/wrapper/dists/gradle.zip")" = gradle; test -f "$GRADLE_USER_HOME/caches/library.jar"; test -f "$npm_config_cache/package"; test ! -f "$GRADLE_USER_HOME/gradle.properties"')
            self.assertEqual(second.returncode, 0, second.stderr)
            clean = run(3, False, 'test ! -f "$GRADLE_USER_HOME/wrapper/dists/gradle.zip"; test ! -f "$npm_config_cache/package"')
            self.assertEqual(clean.returncode, 0, clean.stderr)
            self.assertTrue((cache / "gradle/wrapper/dists/gradle.zip").exists())
            other_cache = root / "other-project-cache"
            other_cache.mkdir()
            other = run(4, True, 'test ! -f "$GRADLE_USER_HOME/wrapper/dists/gradle.zip"; test ! -f "$npm_config_cache/package"', other_cache)
            self.assertEqual(other.returncode, 0, other.stderr)


if __name__ == "__main__":
    unittest.main()
