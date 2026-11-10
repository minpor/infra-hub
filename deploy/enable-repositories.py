#!/usr/bin/env python3
"""Apply a prepared repository-release.tar.gz on the personal Infra Hub host.

Does not print secrets. Requires an already installed GitHub App private key.
Backs up database and prior images before changing the running panel.
"""
from datetime import datetime, timezone
from pathlib import Path
import os
import secrets
import shutil
import subprocess

panel = Path('/opt/infra-hub/stacks/panel')
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/opt/infra-hub/backups') / f'repositories-{stamp}'
compose = ['docker', 'compose', '-f', 'compose.live.yaml']


def run(args, **kwargs):
    return subprocess.run(args, cwd=panel, check=True, **kwargs)


key = panel / 'github-app/private-key.pem'
assert key.is_file(), 'Install GitHub App key first'
assert os.getuid() == 0
active = run(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c',
                      "SELECT count(*) FROM builds WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')"], capture_output=True, text=True).stdout.strip()
assert active == '0', 'Wait for queued/running builds before upgrading'
backup.mkdir(mode=0o700)
for name in ('.env', 'compose.live.yaml', 'Dockerfile.api', 'Dockerfile.web'):
    shutil.copy2(panel / name, backup / name)
with (backup / 'database.dump').open('wb') as output:
    run(compose + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'infra_hub', '-d', 'infra_hub', '-Fc'], stdout=output)
for component in ('api', 'web'):
    run(['docker', 'tag', f'infra-hub-{component}:local', f'infra-hub-{component}:before-repositories-{stamp}'])
run(['tar', '-xzf', '/opt/infra-hub/stacks/repository-release.tar.gz', '-C', str(panel)])
env = panel / '.env'
lines = env.read_text().splitlines()
values = {
    'HUB_GITHUB_APP_ID': '5682106',
    'HUB_GITHUB_INSTALLATION_ID': '180194622',
    'HUB_RUNNER_UID': str(__import__('pwd').getpwnam('infra-build').pw_uid),
    'HUB_RUNNER_SOCKET_GID': str(Path(f'/run/user/{__import__("pwd").getpwnam("infra-build").pw_uid}/docker.sock').stat().st_gid)
}
if not any(line.startswith('HUB_WORKER_SECRET=') for line in lines):
    values['HUB_WORKER_SECRET'] = secrets.token_hex(32)
lines = [line for line in lines if line.split('=', 1)[0] not in values]
env.write_text('\n'.join(lines + [f'{name}={value}' for name, value in values.items()]) + '\n')
env.chmod(0o600)
os.chown(key.parent, 10001, 10001)
key.parent.chmod(0o700)
os.chown(key, 10001, 10001)
key.chmod(0o600)
full = compose + ['-f', 'compose.repositories.yaml']
run(full + ['config', '-q'])
run(compose + ['build', 'api', 'caddy'])
run(full + ['build', 'worker'])
run(full + ['up', '-d', '--wait'])
print(f'Repository runner enabled; rollback backup: {backup}')
