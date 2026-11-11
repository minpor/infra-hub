"""Exercise the real Aukni builder with fixture commands and no external services."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class AukniParallelBuildTest(unittest.TestCase):
    def run_build(self, failure=None):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for directory in (".github/workflows", "backend", "deploy/monitoring"):
                (root / directory).mkdir(parents=True)
            # Neither group may finish unless the other has actually started.
            (root / "check.py").write_text('''import os
from pathlib import Path
import sys
import time
phase = sys.argv[1]
Path(phase + '.started').touch()
other = 'backend' if phase == 'web' else 'web'
deadline = time.monotonic() + 5
while not Path(other + '.started').exists():
    if time.monotonic() > deadline:
        raise RuntimeError('Checks did not overlap')
    time.sleep(0.01)
Path(phase + '.finished').touch()
sys.exit(1 if os.environ.get('FAIL_PHASE') == phase else 0)
''')
            (root / "backend/gradlew").write_text('cd ..\nexec python3 check.py backend\n')
            (root / "deploy/check-assets.sh").write_text('exit 0\n')
            (root / "deploy/monitoring/apply.sh").write_text('exit 0\n')
            (root / ".github/workflows/ci.yml").write_text('''jobs:
  landing:
    steps:
      - run: python3 check.py web
      - run: exit 99
        if: github.event_name == 'pull_request'
      - run: npx playwright install --with-deps chromium
''')
            (root / "bundle.py").write_text('''from pathlib import Path
assert Path('web.finished').exists()
assert Path('backend.finished').exists()
Path('bundled').touch()
''')
            source = (ROOT / "deploy/runner/build-aukni-web.py").read_text()
            source = source.replace("'/work/qa'", repr(str(root / "qa")))
            source = source.replace("'/opt/hub/bundle-web.py'", repr(str(root / "bundle.py")))
            builder = root / "builder.py"
            builder.write_text(source)
            env = dict(os.environ, HUB_COMMIT_SHA="a" * 40, FAIL_PHASE=failure or "")
            result = subprocess.run(["python3", str(builder)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=15)
            self.assertTrue((root / "web.finished").exists(), result.stdout + result.stderr)
            self.assertTrue((root / "backend.finished").exists(), result.stdout + result.stderr)
            self.assertEqual((root / "bundled").exists(), failure is None,
                             result.stdout + result.stderr)
            return result

    def test_checks_overlap_and_packaging_waits_for_both(self):
        result = self.run_build()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("FINISH: web checks", result.stdout)
        self.assertIn("FINISH: backend build", result.stdout)

    def test_web_failure_blocks_packaging(self):
        self.assertNotEqual(self.run_build("web").returncode, 0)

    def test_backend_failure_blocks_packaging(self):
        self.assertNotEqual(self.run_build("backend").returncode, 0)


class BrowserParallelBuildTest(unittest.TestCase):
    def run_browser_build(self, failure=""):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for directory in (".github/workflows", "backend", "site/tests", "deploy/monitoring"):
                (root / directory).mkdir(parents=True)
            (root / "backend/gradlew").write_text("exit 0\n")
            (root / "deploy/check-assets.sh").write_text("exit 0\n")
            (root / "deploy/monitoring/apply.sh").write_text("exit 0\n")
            (root / "site/tests/browser.py").write_text('''import fcntl
import os
from pathlib import Path
import sys
import time
phase = sys.argv[1]
assert Path('ready').exists(), 'Browser started before preparation'
assert os.environ['BROWSER_SHOTS'].endswith('/' + phase)
def change(delta):
    with open('lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        count = int(Path('active').read_text()) if Path('active').exists() else 0
        count += delta
        Path('active').write_text(str(count))
        if count > 2:
            Path('too-many').touch()
change(1)
Path(phase + '.started').touch()
partner = {'a': 'b', 'b': 'a', 'c': 'd', 'd': 'c'}[phase]
deadline = time.monotonic() + 5
while not Path(partner + '.started').exists():
    if time.monotonic() > deadline:
        raise RuntimeError('Browser steps did not overlap')
    time.sleep(0.01)
change(-1)
Path(phase + '.finished').touch()
sys.exit(1 if os.environ.get('FAIL_PHASE') == phase else 0)
''')
            steps = [{"run": "touch ready", "working-directory": "site"}]
            for phase in "abcd":
                (root / f"site/tests/browser-{phase}.cjs").write_text(
                    "const r = require('node:child_process').spawnSync('python3', "
                    f"['tests/browser.py', '{phase}'], {{stdio:'inherit'}}); process.exit(r.status ?? 1);\n")
                steps.append({"run": f"node tests/browser-{phase}.cjs", "working-directory": "site",
                              "env": {"BROWSER_SHOTS": "${{ runner.temp }}/" + phase}})
            steps.append({"run": "test -f a.finished && test -f b.finished && test -f c.finished && test -f d.finished && touch barrier", "working-directory": "site"})
            import yaml
            (root / ".github/workflows/ci.yml").write_text(yaml.safe_dump({"jobs": {"landing": {"steps": steps}}}))
            (root / "bundle.py").write_text("from pathlib import Path\nassert Path('site/barrier').exists()\nPath('bundled').touch()\n")
            source = (ROOT / "deploy/runner/build-aukni-web.py").read_text()
            source = source.replace("'/work/qa'", repr(str(root / "qa")))
            source = source.replace("'/opt/hub/bundle-web.py'", repr(str(root / "bundle.py")))
            (root / "builder.py").write_text(source)
            result = subprocess.run(["python3", str(root / "builder.py")], cwd=root,
                                    env=dict(os.environ, HUB_COMMIT_SHA="a" * 40, FAIL_PHASE=failure),
                                    capture_output=True, text=True, timeout=15)
            self.assertFalse((root / "site/too-many").exists(), result.stdout + result.stderr)
            for phase in "abcd":
                self.assertTrue((root / f"site/{phase}.finished").exists(), result.stdout + result.stderr)
            self.assertEqual((root / "bundled").exists(), not failure, result.stdout + result.stderr)
            self.assertEqual((root / "site/barrier").exists(), not failure, result.stdout + result.stderr)
            return result

    def test_two_browsers_overlap_and_later_checks_wait(self):
        result = self.run_browser_build()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_browser_failure_blocks_later_checks_and_packaging(self):
        self.assertNotEqual(self.run_browser_build("a").returncode, 0)


if __name__ == "__main__":
    unittest.main()
