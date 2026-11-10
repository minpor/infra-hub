#!/usr/bin/env python3
"""Install MCP settings and release control, draining executors under both claim locks."""
from datetime import datetime,timezone
from pathlib import Path
import os,shutil,subprocess,time
panel=Path('/opt/infra-hub/stacks/panel')
compose=['docker','compose','-f','compose.live.yaml','-f','compose.repositories.yaml','-f','compose.deployments.yaml']
def run(args,**kwargs):return subprocess.run(args,cwd=panel,check=True,**kwargs)
def sql(query):return run(compose+['exec','-T','postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c',query],capture_output=True,text=True).stdout.strip()
assert os.getuid()==0
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup=Path('/opt/infra-hub/backups')/('mcp-'+stamp);backup.mkdir(mode=0o700)
with (backup/'database.dump').open('wb') as output:run(compose+['exec','-T','postgres','pg_dump','-U','infra_hub','-d','infra_hub','-Fc'],stdout=output)
for name in ['.env','Caddyfile.live','compose.live.yaml','compose.repositories.yaml','compose.deployments.yaml']:
 shutil.copy2(panel/name,backup/name)
for name in ['api','web','worker','deployer']:run(['docker','tag','infra-hub-'+name+':local','infra-hub-'+name+':before-mcp-'+stamp])
app_name='infra-hub-mcp-maintenance-'+stamp
lock=subprocess.Popen(compose+['exec','-T','-e','PGAPPNAME='+app_name,'postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c','SELECT pg_advisory_lock(4821901); SELECT pg_advisory_lock(4821902); SELECT pg_sleep(1800);'],cwd=panel,stdout=subprocess.DEVNULL)
try:
 for _ in range(60):
  if sql("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.application_name='"+app_name+"' AND l.locktype='advisory' AND l.granted")=='2':break
  time.sleep(.1)
 else:raise RuntimeError('Claim locks not acquired')
 run(['tar','-xzf','/opt/infra-hub/stacks/mcp-release.tar.gz','-C',str(panel)])
 run(compose+['build','--quiet','api','caddy'])
 run(compose+['build','--quiet','worker','deployer'])
 for _ in range(600):
  assert lock.poll() is None
  active=sql("SELECT (SELECT count(*) FROM builds WHERE status IN ('RUNNING','CANCEL_REQUESTED'))+(SELECT count(*) FROM deployments WHERE status IN ('RUNNING','CANCEL_REQUESTED'))")
  if active=='0':break
  time.sleep(2)
 else:raise RuntimeError('Executors still active; retry later without interrupting jobs')
 run(compose+['up','-d','--no-deps','--wait','api','worker','deployer','caddy'])
 print('MCP settings/release control installed. Backup: '+str(backup),flush=True)
finally:
 sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='"+app_name+"'")
 lock.wait(timeout=10)
