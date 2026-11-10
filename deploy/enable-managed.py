#!/usr/bin/env python3
"""Install a tested release of the dedicated deployer on the personal control server."""
from datetime import datetime, timezone
from pathlib import Path
import os
import shutil
import subprocess

panel = Path('/opt/infra-hub/stacks/panel')
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/opt/infra-hub/backups') / f'managed-{stamp}'
old = ['docker', 'compose', '-f', 'compose.live.yaml', '-f', 'compose.repositories.yaml']
old += ['-f', 'compose.deployments.yaml']
compose = old


def run(args, **kwargs):
    return subprocess.run(args, cwd=panel, check=True, **kwargs)


assert os.getuid() == 0
active = run(old + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c',
    "SELECT count(*) FROM builds WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')"], capture_output=True, text=True).stdout.strip()
assert active == '0', 'Wait for builds before upgrading'
# Repeat installations also wait for unfinished deployments.
exists = run(old + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c', "SELECT to_regclass('deployments')"], capture_output=True, text=True).stdout.strip()
if exists:
    active = run(old + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c', "SELECT count(*) FROM deployments WHERE status IN ('QUEUED','RUNNING','INTERRUPTED')"], capture_output=True, text=True).stdout.strip()
    assert active == '0', 'Reconcile unfinished deployments before upgrading'
backup.mkdir(mode=0o700)
for name in ('.env', 'compose.live.yaml', 'compose.repositories.yaml', 'compose.deployments.yaml', 'Dockerfile.api', 'Dockerfile.web', 'Dockerfile.worker', 'Dockerfile.deployer'):
    if (panel / name).exists():
        shutil.copy2(panel / name, backup / name)
with (backup / 'database.dump').open('wb') as output:
    run(old + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'infra_hub', '-d', 'infra_hub', '-Fc'], stdout=output)
with (backup / 'ssh-keys.tar.gz').open('wb') as output:
    run(old + ['exec', '-T', 'api', 'tar', '-czf', '-', '-C', '/work/ssh', '.'], stdout=output)
for component in ('api', 'web', 'worker', 'deployer'):
    found = subprocess.run(['docker', 'image', 'inspect', f'infra-hub-{component}:local'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if found.returncode == 0:
        run(['docker', 'tag', f'infra-hub-{component}:local', f'infra-hub-{component}:before-deployments-{stamp}'])
run(['tar', '-xzf', '/opt/infra-hub/stacks/managed-release.tar.gz', '-C', str(panel)])
run(compose + ['config', '-q'])
run(compose + ['build', 'api', 'caddy'])
run(compose + ['build', 'worker', 'deployer'])
run(compose + ['up', '-d', '--wait'])
print(f'Managed pipelines installed; rollback backup: {backup}')
