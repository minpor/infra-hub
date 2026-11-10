"""Run branch-release validation from the exact source workflow, using installed sandbox tools."""
import os
from pathlib import Path
import re
import subprocess
import yaml

root = Path.cwd()
qa = Path('/work/qa');qa.mkdir()
env=dict(os.environ, RUNNER_TEMP=str(qa), GITHUB_SHA=os.environ['HUB_COMMIT_SHA'])
workflow = yaml.safe_load((root/'.github/workflows/ci.yml').read_text())
for step in workflow['jobs']['landing']['steps']:
    if 'run' not in step or 'pull_request' in str(step.get('if','')):continue
    command = step['run']
    if 'npx playwright install' in command:continue
    command = re.sub(r'''CHROME_BIN=\$\(node -p ["']require\('playwright'\)\.chromium\.executablePath\(\)["']\)''','CHROME_BIN=/usr/bin/chromium',command)
    command=command.replace('python3 deploy/nginx/test-request-photos.py','python3 /opt/hub/local-nginx-test.py deploy/nginx/test-request-photos.py')
    current=dict(env)
    for key,value in step.get('env',{}).items():
        value=str(value).replace('${{ runner.temp }}',str(qa))
        assert '${{' not in value
        current[key]=value
    print('CHECK: '+str(step.get('name',command.splitlines()[0])),flush=True)
    subprocess.run(['bash','-euo','pipefail','-c',command],cwd=root/step.get('working-directory','.'),env=current,check=True)
subprocess.run(['bash','deploy/check-assets.sh'],check=True)
subprocess.run(['bash','-n','deploy/monitoring/apply.sh'],check=True)
subprocess.run(['bash','deploy/monitoring/apply.sh','--self-test'],check=True)
subprocess.run(['sh','./gradlew','--no-daemon','--max-workers=4','build','shadowJar'],cwd=root/'backend',check=True)
subprocess.run(['python3','/opt/hub/bundle-web.py','site','backend/build/libs/aukni-waitlist.jar'],check=True)
