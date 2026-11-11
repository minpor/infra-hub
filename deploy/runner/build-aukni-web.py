"""Run branch-release validation from the exact source workflow, using installed sandbox tools."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import re
import subprocess
import time
import yaml

root = Path.cwd()
qa = Path('/work/qa');qa.mkdir()
env=dict(os.environ, RUNNER_TEMP=str(qa), GITHUB_SHA=os.environ['HUB_COMMIT_SHA'])


def run_web_step(step):
    command = step['run']
    command = re.sub(r"CHROME_BIN=\$\(node -p [\"']require\('playwright'\)\.chromium\.executablePath\(\)[\"']\)", 'CHROME_BIN=/usr/bin/chromium', command)
    command = command.replace('python3 deploy/nginx/test-request-photos.py', 'python3 /opt/hub/local-nginx-test.py deploy/nginx/test-request-photos.py')
    current = dict(env)
    for key, value in step.get('env', {}).items():
        value = str(value).replace('${{ runner.temp }}', str(qa))
        assert '${{' not in value
        current[key] = value
    name = str(step.get('name', command.splitlines()[0]))
    timed_check(name, lambda: subprocess.run(
        ['bash', '-euo', 'pipefail', '-c', command],
        cwd=root / step.get('working-directory', '.'), env=current, check=True))


def check_web():
    workflow = yaml.safe_load((root / '.github/workflows/ci.yml').read_text())
    # Only browser steps may overlap. Installation and other checks are barriers;
    # multiple commands inside one step retain their original ordering and env.
    with ThreadPoolExecutor(max_workers=2) as browsers:
        pending = []
        for step in workflow['jobs']['landing']['steps']:
            if 'run' not in step or 'pull_request' in str(step.get('if', '')):
                continue
            if 'npx playwright install' in step['run']:
                continue
            if re.search(r'\bnode\s+tests/browser-[\w-]+\.cjs\b', step['run']):
                pending.append(browsers.submit(run_web_step, step))
            else:
                for check in pending:
                    check.result()
                pending.clear()
                run_web_step(step)
        for check in pending:
            check.result()
    subprocess.run(['bash', 'deploy/check-assets.sh'], check=True)
    subprocess.run(['bash', '-n', 'deploy/monitoring/apply.sh'], check=True)
    subprocess.run(['bash', 'deploy/monitoring/apply.sh', '--self-test'], check=True)


def check_backend():
    subprocess.run(['sh','./gradlew','--no-daemon','--max-workers=4','build','shadowJar'],cwd=root/'backend',check=True)


def timed_check(name, check):
    started = time.monotonic()
    print(f"START: {name}", flush=True)
    try:
        check()
    finally:
        print(f"FINISH: {name} ({time.monotonic() - started:.1f}s)", flush=True)


# Browser checks use at most two workers. Both groups share the
# runner's CPU/RAM limits, and packaging requires both to complete successfully.
with ThreadPoolExecutor(max_workers=2) as executor:
    checks = [executor.submit(timed_check, name, check) for name, check in
              (("web checks", check_web), ("backend build", check_backend))]
    for check in checks:
        check.result()

subprocess.run(['python3','/opt/hub/bundle-web.py','site','backend/build/libs/aukni-waitlist.jar'],check=True)
