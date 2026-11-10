"""Verify portable image content, then return the target daemon's native image ID."""
import json
import re
import subprocess
import sys
p=json.loads(sys.stdin.buffer.read(1048577))
assert re.fullmatch(r'infra-hub-transfer:[a-f0-9-]{36}',p['tag'])
image=json.loads(subprocess.check_output(['docker','image','inspect',p['tag']],timeout=30))[0]
assert image['RootFS']['Layers']==p['layers']
assert image['Architecture']==p['architecture'] and image['Os']==p['os']
for key in ['Entrypoint','Cmd','Env','User','WorkingDir','Labels']:
    default={} if key=='Labels' else '' if key in ('User','WorkingDir') else []
    assert (image['Config'].get(key) or default)==(p['configuration'].get(key) or default), 'Imported image configuration mismatch: '+key
assert re.fullmatch('sha256:[a-f0-9]{64}',image['Id'])
print(json.dumps({'imageId':image['Id']}))
