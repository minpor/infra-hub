#!/usr/bin/env python3
"""Install additive runner settings after draining the active job; never interrupt compilation."""
from datetime import datetime, timezone
from pathlib import Path
import os
import shutil
import subprocess
import time

panel = Path('/opt/infra-hub/stacks/panel')
compose = ['docker', 'compose', '-f', 'compose.live.yaml', '-f', 'compose.repositories.yaml', '-f', 'compose.deployments.yaml']
docker = ['docker', '--host', 'unix:///run/user/1000/docker.sock']

def run(args, **kwargs):
    return subprocess.run(args, cwd=panel, check=True, **kwargs)

def sql(query):
    return run(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c', query], capture_output=True, text=True).stdout.strip()

assert os.getuid() == 0
assert sql("SELECT count(*) FROM deployments WHERE status IN ('QUEUED','RUNNING','INTERRUPTED')") == '0'
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/opt/infra-hub/backups') / ('runner-settings-' + stamp)
backup.mkdir(mode=0o700)
with (backup / 'database.dump').open('wb') as output:
    run(compose + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'infra_hub', '-d', 'infra_hub', '-Fc'], stdout=output)
for name in ['api', 'web', 'worker', 'deployer']:
    run(['docker', 'tag', 'infra-hub-' + name + ':local', 'infra-hub-' + name + ':before-runner-settings-' + stamp])
run(docker + ['tag', 'infra-hub-builder:java21', 'infra-hub-builder:before-runner-settings-' + stamp])
for name in ['.env', 'compose.live.yaml', 'compose.repositories.yaml', 'compose.deployments.yaml']:
    shutil.copy2(panel / name, backup / name)
app_name = 'infra-hub-runner-settings-' + stamp
lock = subprocess.Popen(compose + ['exec', '-T', '-e', 'PGAPPNAME=' + app_name, 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c', 'SELECT pg_advisory_lock(4821901); SELECT pg_sleep(1800);'], cwd=panel, stdout=subprocess.DEVNULL)
try:
    for _ in range(60):
        if sql("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.application_name='" + app_name + "' AND l.locktype='advisory' AND l.granted") == '1':
            break
        time.sleep(0.1)
    else:
        raise RuntimeError('Claim lock not acquired')
    run(['tar', '-xzf', '/opt/infra-hub/stacks/runner-settings-runtime.tar.gz', '-C', str(panel)])
    run(compose + ['build', '--quiet', 'api', 'caddy'])
    run(compose + ['build', '--quiet', 'worker', 'deployer'])
    runner = Path('/opt/infra-hub/stacks/managed-check/runner')
    run(['tar', '-xzf', '/opt/infra-hub/stacks/runner-settings-builder.tar.gz', '-C', str(runner)])
    run(docker + ['build', '--quiet', '-t', 'infra-hub-builder:java21', str(runner)])
    print('Images ready; waiting for the current job to finish without interruption', flush=True)
    for _ in range(600):
        assert lock.poll() is None, 'Claim lock expired'
        if sql("SELECT count(*) FROM builds WHERE status IN ('RUNNING','CANCEL_REQUESTED')") == '0':
            break
        time.sleep(2)
    else:
        raise RuntimeError('Job still active; retry maintenance later')
    run(compose + ['up', '-d', '--no-deps', '--wait', 'api', 'worker', 'deployer', 'caddy'])
    print('Runner settings installed; backup: ' + str(backup), flush=True)
finally:
    sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + app_name + "'")
    lock.wait(timeout=10)
