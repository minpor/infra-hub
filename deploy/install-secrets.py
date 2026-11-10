#!/usr/bin/env python3
"""Install an OAuth client secret without putting its value in a command or the repo."""
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

source = Path(sys.argv[1] if len(sys.argv) > 1 else '.local/oauth-client-secret').expanduser()
if not source.is_file() or source.stat().st_mode & 0o077:
    raise SystemExit('Secret file missing or readable by group/others; use chmod 600')
secret = source.read_text().strip()
if not re.fullmatch(r'[A-Za-z0-9_]{30,300}', secret):
    raise SystemExit('Invalid GitHub client secret format')
client_id = os.environ['HUB_GITHUB_CLIENT_ID']
owner_id = os.environ['HUB_OWNER_GITHUB_ID']
ssh_host = os.environ['HUB_INSTALL_SSH_HOST']
if not re.fullmatch(r'[A-Za-z0-9_]+', client_id) or not owner_id.isdigit():
    raise SystemExit('Invalid client ID or numeric owner ID')
db_password = secrets.token_urlsafe(40)
content = (f'HUB_DATABASE_PASSWORD={db_password}\nHUB_GITHUB_CLIENT_SECRET={secret}\n'
           f'HUB_GITHUB_CLIENT_ID={client_id}\nHUB_OWNER_GITHUB_ID={owner_id}\n')
subprocess.run([
    'ssh', '-o', 'BatchMode=yes', ssh_host,
    'umask 077; set -C; cat > /opt/infra-hub/stacks/panel/.env; chmod 600 /opt/infra-hub/stacks/panel/.env'
], input=content, text=True, check=True)
print('Server secret file installed (mode 600). Values were not printed.')
