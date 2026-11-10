#!/usr/bin/env python3
"""Install the tested image-publication release on the existing personal hub."""
from datetime import datetime, timezone
from pathlib import Path
import os
import shutil
import subprocess

panel = Path('/opt/infra-hub/stacks/panel')
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/opt/infra-hub/backups') / f'images-{stamp}'
compose = ['docker', 'compose', '-f', 'compose.live.yaml', '-f', 'compose.repositories.yaml']
rootless = ['docker', '--host', 'unix:///run/user/1000/docker.sock']


def run(args, **kwargs):
    return subprocess.run(args, cwd=panel, check=True, **kwargs)


assert os.getuid() == 0
active = run(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c',
    "SELECT count(*) FROM builds WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')"], capture_output=True, text=True).stdout.strip()
assert active == '0', 'Wait for queued/running builds before upgrading'
backup.mkdir(mode=0o700)
for name in ('.env', 'compose.live.yaml', 'compose.repositories.yaml', 'Dockerfile.api', 'Dockerfile.web', 'Dockerfile.worker'):
    shutil.copy2(panel / name, backup / name)
with (backup / 'database.dump').open('wb') as output:
    run(compose + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'infra_hub', '-d', 'infra_hub', '-Fc'], stdout=output)
# Keep the SSH-key volume together with its database backup.
with (backup / 'ssh-keys.tar.gz').open('wb') as output:
    run(compose + ['exec', '-T', 'api', 'tar', '-czf', '-', '-C', '/work/ssh', '.'], stdout=output)
for component in ('api', 'web', 'worker'):
    run(['docker', 'tag', f'infra-hub-{component}:local', f'infra-hub-{component}:before-images-{stamp}'])
run(['tar', '-xzf', '/opt/infra-hub/stacks/images-release.tar.gz', '-C', str(panel)])
env = panel / '.env'
lines = [line for line in env.read_text().splitlines() if not line.startswith(('HUB_IMAGE_NAMESPACE=', 'HUB_RUNTIME_IMAGE='))]
lines += ['HUB_IMAGE_NAMESPACE=localhost:5000/infra-hub', 'HUB_RUNTIME_IMAGE=eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474']
env.write_text('\n'.join(lines) + '\n')
env.chmod(0o600)
run(rootless + ['compose', '-f', 'runner/compose.registry.yaml', 'up', '-d'])
run(compose + ['config', '-q'])
run(compose + ['build', 'api', 'caddy'])
run(compose + ['build', 'worker'])
run(compose + ['up', '-d', '--wait'])
print(f'Image publication installed; rollback backup: {backup}')
