"""No signing key in this sandbox. Package unsigned release artifacts for the trusted deployer."""
import io
import json
import os
from pathlib import Path
import re
import sys
import zipfile

target = sys.argv[1]
assert target in ('dev','prod')
version = next(x.split('=',1)[1].strip() for x in Path('mobile/version.properties').read_text().splitlines() if x.startswith('versionName='))
assert re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', version)
code = int(os.environ['HUB_ANDROID_CODE'])
root = Path('mobile/androidApp/build/outputs')
apk = list(root.glob('apk/' + target + '/release/*unsigned.apk'))
assert len(apk) == 1
bundle = list(root.glob('bundle/' + target + 'Release/*.aab'))
assert len(bundle) == 1
payload = io.BytesIO()
with zipfile.ZipFile(payload, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(apk[0], 'app-unsigned.apk')
    z.write(bundle[0], 'app-unsigned.aab')
    mapping = root / ('mapping/' + target + 'Release/mapping.txt')
    if mapping.is_file(): z.write(mapping, 'mapping.txt')
    qa_root=Path('/work/qa/android')
    if qa_root.is_dir():
        report=io.BytesIO()
        with zipfile.ZipFile(report,'w',zipfile.ZIP_DEFLATED) as q:
            for file in sorted(qa_root.rglob('*')):
                if file.is_file() and not file.is_symlink():q.write(file,str(file.relative_to(qa_root)))
        z.writestr('qa.zip',report.getvalue())
    z.writestr('metadata.json', json.dumps({'target':target,'versionName':version + ('-dev' if target=='dev' else ''),'versionCode':code,'sha':os.environ['HUB_COMMIT_SHA']}))
with zipfile.ZipFile('android-release.jar','w',zipfile.ZIP_STORED) as jar:
    jar.writestr('META-INF/infra-hub/android.zip',payload.getvalue())
print('Unsigned Android release packaged: ' + version + ' code ' + str(code))
