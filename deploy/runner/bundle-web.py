"""Run inside the disposable source container; embed a reproducible site snapshot in the release JAR."""
import io
import os
from pathlib import Path
import re
import sys
import zipfile

site = Path(sys.argv[1]).resolve()
jar = Path(sys.argv[2]).resolve()
sha = os.environ['HUB_COMMIT_SHA']
assert re.fullmatch('[a-f0-9]{40}', sha)
web = io.BytesIO()
with zipfile.ZipFile(web, 'w', zipfile.ZIP_DEFLATED) as archive:
    total = 0
    for path in sorted(site.rglob('*')):
        rel = path.relative_to(site)
        if any(part in ('node_modules', 'tests', '.git') for part in rel.parts) or not path.is_file():
            continue
        assert not path.is_symlink() and path.stat().st_size <= 32 * 1024 * 1024
        data = path.read_bytes()
        if path.suffix == '.html':
            data = re.sub(rb'(/assets/[^"?]+\.(?:css|js))\?v=[^"]*"', lambda m: m[1] + b'?v=' + sha[:8].encode() + b'"', data)
        total += len(data)
        assert total <= 128 * 1024 * 1024
        archive.writestr(str(rel), data)
with zipfile.ZipFile(jar, 'a', zipfile.ZIP_DEFLATED) as archive:
    assert 'META-INF/infra-hub/site.zip' not in archive.namelist()
    archive.writestr('META-INF/infra-hub/site.zip', web.getvalue())
    qa_root=Path('/work/qa')
    if qa_root.is_dir():
        qa=io.BytesIO()
        with zipfile.ZipFile(qa,'w',zipfile.ZIP_DEFLATED) as report:
            size=0
            for file in sorted(qa_root.rglob('*')):
                if not file.is_file() or file.is_symlink():continue
                size+=file.stat().st_size;assert size<=128*1024*1024
                report.write(file,str(file.relative_to(qa_root)))
        archive.writestr('META-INF/infra-hub/qa.zip',qa.getvalue())
print('Site snapshot embedded in JAR for ' + sha)
