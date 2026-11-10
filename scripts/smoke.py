"""Real API/worker restart and browser checks against check.sh's isolated database."""
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / ".local" / "checks"
OUTPUT.mkdir(parents=True, exist_ok=True)
PORT = int(os.environ.get("HUB_TEST_API_PORT", "18081"))
BASE = f"http://127.0.0.1:{PORT}"
ENV = dict(os.environ, HUB_PORT=str(PORT), HUB_WORK_DIRECTORY=str(OUTPUT / "work"), HUB_SSH_DIRECTORY=str(OUTPUT / "ssh"))
PROCESSES = []
FILES = []
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def start(name, command, env=ENV, cwd=ROOT):
    file = (OUTPUT / f"{name}.log").open("w")
    FILES.append(file)
    process = subprocess.Popen(command, cwd=cwd, env=env, stdout=file, stderr=subprocess.STDOUT, start_new_session=True)
    PROCESSES.append(process)
    return process


def stop(process, abrupt=False):
    if process.poll() is None:
        if abrupt:
            process.kill()  # crash just the worker; its job must not become a success
        else:
            process.terminate()
        process.wait(timeout=15)


def request(path, body=None, headers=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    defaults = {"Content-Type": "application/json", "X-Infra-Hub": "local-ui"}
    defaults.update(headers or {})
    req = urllib.request.Request(BASE + "/api" + path, data=data, headers=defaults)
    try:
        with HTTP.open(req, timeout=5) as response:
            assert response.status == expected, (response.status, expected)
            return json.load(response)
    except urllib.error.HTTPError as error:
        assert error.code == expected, (error.code, expected, error.read())
        return None


def wait_for(predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.3)
    raise AssertionError("Timed out waiting for condition; see .local/checks logs")


JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
COMMAND = [JAVA, "-Xmx256m", "-jar", str(ROOT / "backend/build/libs/infra-hub.jar")]


def api_start(name):
    process = start(name, COMMAND)
    wait_for(lambda: request("/health"))
    return process


def enqueue(project):
    return request(f"/projects/{project['id']}/builds", {"requestId": str(uuid.uuid4())}, expected=202)


def status(build):
    return request(f"/builds/{build['id']}")["build"]["status"]


def has_status(build, wanted):
    return status(build) == wanted


try:
    centre = api_start("api-1")
    request("/projects", {"name": "blocked"}, headers={"Origin": "https://evil.example"}, expected=403)
    request("/projects", {"name": "blocked"}, headers={"X-Infra-Hub": ""}, expected=403)
    request("/projects", headers={"Host": "evil.example"}, expected=403)
    project = request("/projects", {"name": "Smoke " + str(uuid.uuid4())[:8]}, expected=201)
    build = enqueue(project)
    cancelled = enqueue(project)
    request(f"/builds/{cancelled['id']}/cancel", {})
    assert has_status(cancelled, "CANCELLED")
    stop(centre)
    centre = api_start("api-2")
    assert has_status(build, "QUEUED"), "Queue did not survive API restart"
    worker = start("worker-1", COMMAND + ["--spring.profiles.active=worker"])
    wait_for(lambda: has_status(build, "SUCCEEDED"))
    detail = request(f"/builds/{build['id']}")
    assert [step["status"] for step in detail["steps"]] == ["SUCCEEDED"] * 3
    assert "PASS: greeting" in "".join(log["text"] for log in request(f"/builds/{build['id']}/logs"))
    running = enqueue(project)
    wait_for(lambda: has_status(running, "RUNNING"))
    request(f"/builds/{running['id']}/cancel", {})
    wait_for(lambda: has_status(running, "CANCELLED"))
    during_restart = enqueue(project)
    wait_for(lambda: has_status(during_restart, "RUNNING"))
    stop(centre)
    centre = api_start("api-3")
    wait_for(lambda: has_status(during_restart, "SUCCEEDED"))
    interrupted = enqueue(project)
    wait_for(lambda: any(step["position"] == 1 and step["status"] == "RUNNING" for step in request(f"/builds/{interrupted['id']}")["steps"]))
    stop(worker, abrupt=True)
    wait_for(lambda: has_status(interrupted, "INTERRUPTED"), timeout=25)
    worker = start("worker-2", COMMAND + ["--spring.profiles.active=worker"])
    followup = enqueue(project)
    wait_for(lambda: has_status(followup, "SUCCEEDED"))
    assert has_status(interrupted, "INTERRUPTED"), "Crash was automatically retried"
    print("PASS: API access checks, queue persistence, build, logs, cancellation, API restart, worker crash/recovery", flush=True)
    ui_env = dict(ENV, HUB_API_TARGET=BASE)
    start("frontend", ["npm", "run", "dev", "--", "--port", "15173"], env=ui_env, cwd=ROOT / "frontend")
    def ui_ready():
        with HTTP.open("http://127.0.0.1:15173", timeout=2) as response:
            return response.status == 200
    wait_for(ui_ready)
    subprocess.run(["npx", "playwright", "test"], cwd=ROOT / "frontend", env=ui_env, check=True)
finally:
    for process in reversed(PROCESSES):
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    for process in PROCESSES:
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
    for file in FILES:
        file.close()
