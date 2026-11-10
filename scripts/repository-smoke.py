"""Run on the prepared server with deploy/runner/compose.test.yaml as argument.

Exercises the real worker and disposable PostgreSQL. Sources are a supplied
committed repository archive, not the live GitHub App download route.
"""
import json
import os
from pathlib import Path
import subprocess
import signal
import sys
import time
import uuid

compose = ["docker", "compose", "-p", "infra-hub-repository-test", "-f", sys.argv[1]]
rootless = ["docker", "--host", f"unix:///run/user/{os.environ['HUB_RUNNER_UID']}/docker.sock"]


def call(command, **kwargs):
    return subprocess.check_output(command, text=True, **kwargs).strip()


def sql(statement):
    return call(compose + ["exec", "-T", "postgres", "psql", "-U", "infra_hub", "-d", "infra_hub_test", "-At", "-v", "ON_ERROR_STOP=1"], input=statement)


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def wait(predicate, timeout=60):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = predicate()
        if value:
            return value
        time.sleep(1)
    raise RuntimeError("Timed out waiting for worker")


def enqueue(command):
    project, build, request = (str(uuid.uuid4()) for _ in range(3))
    sha = os.environ["SOURCE_SHA"]
    sql(f"""
    INSERT INTO projects(id,name,repository_url) VALUES ('{project}','Smoke {project}','https://github.com/minpor/aukni');
    INSERT INTO builds(id,project_id,request_id,recipe,repository,source_ref,commit_sha,build_command)
    VALUES ('{build}','{project}','{request}','REPOSITORY_V1','minpor/aukni','dev','{sha}',{quote(command)});
    INSERT INTO build_steps(build_id,position,name) VALUES ('{build}',0,'Source'),('{build}',1,'Build');
    """)
    return build


def status(build):
    return sql(f"SELECT status FROM builds WHERE id='{build}'")


def result(build, timeout=90):
    return wait(lambda: status(build) if status(build) in ("FAILED", "SUCCEEDED", "INTERRUPTED", "CANCELLED") else None, timeout)


def interrupted(signum, _frame):
    raise SystemExit(128 + signum)


for signum in (signal.SIGTERM, signal.SIGHUP):
    signal.signal(signum, interrupted)


try:
    subprocess.run(compose + ["up", "-d", "--wait"], check=True)
    wait(lambda: sql("SELECT to_regclass('public.builds')") == "builds")
    build = enqueue("python3 -c \"import os,socket; assert 'HUB_DATABASE_PASSWORD' not in os.environ; assert not os.path.exists('/var/run/docker.sock'); s=socket.socket(); s.settimeout(1); assert s.connect_ex(('1.1.1.1',443)) != 0; print('PASS: no credentials, socket or direct network')\"")
    assert result(build) == "SUCCEEDED", sql(f"SELECT text FROM build_logs WHERE build_id='{build}' ORDER BY id")
    print("PASS: real worker, source transfer, enforced cgroups, no secrets/socket/direct network", flush=True)
    failed = enqueue("exit 23")
    assert result(failed) == "FAILED"
    assert sql(f"SELECT exit_code FROM build_steps WHERE build_id='{failed}' AND position=1") == "23"
    cancelled = enqueue("sleep 120")
    wait(lambda: sql(f"SELECT status FROM build_steps WHERE build_id='{cancelled}' AND position=1") == "RUNNING")
    sql(f"UPDATE builds SET status='CANCEL_REQUESTED' WHERE id='{cancelled}'")
    assert result(cancelled) == "CANCELLED"
    assert not call(rootless + ["ps", "-aq", "--filter", f"label=dev.infrahub.repository-test.job={cancelled}"])
    crashed = enqueue("sleep 120")
    wait(lambda: sql(f"SELECT status FROM build_steps WHERE build_id='{crashed}' AND position=1") == "RUNNING")
    subprocess.run(compose + ["kill", "-s", "SIGKILL", "worker"], check=True)
    time.sleep(16)
    subprocess.run(compose + ["up", "-d", "worker"], check=True)
    assert result(crashed) == "INTERRUPTED"
    assert not call(rootless + ["ps", "-aq", "--filter", f"label=dev.infrahub.repository-test.job={crashed}"])
    print("PASS: failure exit code, cancellation kills container, crash recovery removes orphan", flush=True)
    aukni = enqueue("cd backend && sh ./gradlew --no-daemon --max-workers=4 build")
    outcome = result(aukni, 1250)
    log_path = Path(sys.argv[1]).parent / "aukni-build.log"
    log_path.write_text(sql(f"SELECT text FROM build_logs WHERE build_id='{aukni}' ORDER BY id"))
    summary = {"repository": "minpor/aukni", "sha": os.environ["SOURCE_SHA"], "build": aukni, "result": outcome, "log": str(log_path)}
    (log_path.parent / "smoke-result.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary), flush=True)
finally:
    subprocess.run(compose + ["logs", "--tail", "30", "worker"])
    subprocess.run(compose + ["down", "-v"], check=True)
