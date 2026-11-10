#!/usr/bin/env python3
"""Install prepared servers-release.tar.gz on the existing personal panel host."""
from datetime import datetime, timezone
from pathlib import Path
import os
import shutil
import subprocess

panel = Path('/opt/infra-hub/stacks/panel')
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = Path('/opt/infra-hub/backups') / f'servers-{stamp}'
compose = ['docker', 'compose', '-f', 'compose.live.yaml', '-f', 'compose.repositories.yaml']


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
for component in ('api', 'web', 'worker'):
    run(['docker', 'tag', f'infra-hub-{component}:local', f'infra-hub-{component}:before-servers-{stamp}'])
run(['tar', '-xzf', '/opt/infra-hub/stacks/servers-release.tar.gz', '-C', str(panel)])
run(compose + ['config', '-q'])
run(compose + ['build', 'api', 'caddy'])
run(compose + ['build', 'worker'])
run(compose + ['up', '-d', '--wait'])
print(f'Servers installed; rollback backup: {backup}')
