#!/usr/bin/env python3
"""Update API/deployer after a compatible V6 code fix without interrupting repository builds."""
from datetime import datetime,timezone
from pathlib import Path
import subprocess
import time

panel=Path('/opt/infra-hub/stacks/panel')
compose=['docker','compose','-f','compose.live.yaml','-f','compose.repositories.yaml','-f','compose.deployments.yaml']
def run(args,**kwargs):return subprocess.run(args,cwd=panel,check=True,**kwargs)
active=run(compose+['exec','-T','postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c',"SELECT count(*) FROM deployments WHERE status IN ('QUEUED','RUNNING','INTERRUPTED')"],capture_output=True,text=True).stdout.strip()
assert active=='0'
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup=Path('/opt/infra-hub/backups')/('managed-runtime-'+stamp);backup.mkdir(mode=0o700)
with (backup/'database.dump').open('wb') as out:run(compose+['exec','-T','postgres','pg_dump','-U','infra_hub','-d','infra_hub','-Fc'],stdout=out)
for item in ['api','web','deployer']:run(['docker','tag','infra-hub-'+item+':local','infra-hub-'+item+':before-managed-runtime-'+stamp])
run(['tar','-xzf','/opt/infra-hub/stacks/managed-runtime.tar.gz','-C',str(panel)])
run(compose+['build','--quiet','api','caddy'])
run(compose+['build','--quiet','deployer'])
# Pause only new claims while API restarts; running compilation keeps its lease and processes.
app_name='infra-hub-maintenance-'+stamp
lock=subprocess.Popen(compose+['exec','-T','-e','PGAPPNAME='+app_name,'postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c','SELECT pg_advisory_lock(4821901); SELECT pg_sleep(55);'],cwd=panel,stdout=subprocess.DEVNULL)
try:
    for _ in range(40):
        granted=run(compose+['exec','-T','postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c',"SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.application_name='"+app_name+"' AND l.locktype='advisory' AND l.granted"],capture_output=True,text=True).stdout.strip()
        if granted=='1':break
        time.sleep(0.1)
    else:raise RuntimeError('Maintenance claim lock not acquired')
    for _ in range(60):
        fetching=run(compose+['exec','-T','postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c',"SELECT count(*) FROM builds b JOIN build_steps s ON s.build_id=b.id AND s.position=0 WHERE b.status='RUNNING' AND s.status='RUNNING'"],capture_output=True,text=True).stdout.strip()
        if fetching=='0':break
        time.sleep(0.1)
    else:raise RuntimeError('Wait for source download before API restart')
    run(compose+['up','-d','--no-deps','--wait','api','deployer','caddy'])
finally:
    run(compose+['exec','-T','postgres','psql','-U','infra_hub','-d','infra_hub','-At','-c',"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='"+app_name+"'"],stdout=subprocess.DEVNULL)
    lock.wait(timeout=10)
print('API/deployer updated; repository worker left running. Backup: '+str(backup))
