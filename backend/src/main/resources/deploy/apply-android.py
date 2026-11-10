import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import uuid

p=json.loads(sys.stdin.buffer.read(65537))
release=str(uuid.UUID(p['deploymentId']))
c=p['configuration']
directory=Path(c['directory'])
assert str(directory) in ('/root/docker/static/aukni-download','/opt/aukni-dev-download')
assert c['target'] in ('dev','prod')
assert isinstance(p['versionCode'],int) and p['versionCode']>0
assert re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}(?:-dev)?',p['versionName'])
assert re.fullmatch('[a-f0-9]{64}',p['sha256'])
directory.mkdir(parents=True,exist_ok=True)
lock=(directory/'.infra-hub.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX)
source=Path('/opt/infra-hub/android-incoming')/release/'app.apk'
latest=directory/'latest.json'
current=json.loads(latest.read_text()) if latest.exists() else None
if current and current['versionCode']==p['versionCode'] and current['sha256']==p['sha256']:
    print(json.dumps({'status':'SUCCEEDED','message':'Этот APK уже опубликован; повтор подтверждён.'}));source.unlink(missing_ok=True);sys.exit(0)
if not current or current['versionCode']>=p['versionCode']:
    print(json.dumps({'status':'FAILED','message':'versionCode не больше опубликованного или отсутствует надёжная исходная версия.'}));sys.exit(0)
h=hashlib.sha256()
with source.open('rb') as f:
    for chunk in iter(lambda:f.read(65536),b''):h.update(chunk)
assert source.stat().st_size==p['size'] and h.hexdigest()==p['sha256']
backup=directory/'.infra-hub-backups'/release;backup.mkdir(parents=True,exist_ok=True,mode=0o700)
shutil.copy2(latest,backup/'latest.json')
filename='aukni-'+p['versionName']+'-'+str(p['versionCode'])+'.apk'
versioned=directory/filename
shutil.copyfile(source,versioned);versioned.chmod(0o644)
stable=directory/'.aukni.apk.tmp';shutil.copyfile(versioned,stable);stable.chmod(0o644);stable.replace(directory/'aukni.apk')
metadata={'versionCode':p['versionCode'],'versionName':p['versionName'],'url':'/download/aukni.apk','file':filename,'versionedUrl':'/download/'+filename,'size':p['size'],'sha256':p['sha256'],'publishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'deploymentId':release}
temp=directory/'.latest.json.tmp';temp.write_text(json.dumps(metadata));temp.chmod(0o644);temp.replace(latest)
source.unlink();source.parent.rmdir()
print(json.dumps({'status':'SUCCEEDED','message':'Подписанный APK опубликован атомарно; предыдущие файлы сохранены.'}))
