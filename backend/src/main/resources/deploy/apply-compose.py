"""Adopt an existing Compose application without replacing its environment, networks or data services."""
import fcntl
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
import zipfile

p = json.loads(sys.stdin.buffer.read(65537))
app, release = str(uuid.UUID(p['applicationId'])), str(uuid.UUID(p['deploymentId']))
assert re.fullmatch('sha256:[a-f0-9]{64}', p['imageId'])
c = p['configuration']
base = Path(c['root']).resolve()
site = Path(c['site']).resolve()
assert base.is_dir() and site.is_dir() and (base / '.env').is_file()
assert base.is_relative_to('/opt') and (site.is_relative_to('/opt') or site.is_relative_to('/root/docker/static'))
assert (base / c['composeFile']).resolve().is_relative_to(base)
assert all(re.fullmatch('[A-Za-z0-9_-]*', c[k]) for k in ['service', 'webService', 'proxy'])
assert re.fullmatch('https://[A-Za-z0-9.-]+', c['url'])
os.umask(0o077)
state_dir = Path('/opt/infra-hub/applications') / app
state_dir.mkdir(parents=True, exist_ok=True)
lock = (state_dir / 'lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX)
record_file = state_dir / (release + '.json')
override = state_dir / 'compose.json'
compose = ['docker', 'compose', '--project-directory', str((base / c['composeFile']).parent), '--env-file', str(base / '.env'), '-f', str(base / c['composeFile'])]


def command(args, check=True):
    result = subprocess.run(args, capture_output=True, timeout=90)
    if check and result.returncode:
        raise RuntimeError('operation failed')
    return result


def inspect(container):
    return json.loads(command(['docker', 'inspect', container]).stdout)[0]


def container(service):
    return command(compose + ['ps', '-aq', service]).stdout.decode().strip()


def write(path, value):
    temp = path.with_suffix('.tmp')
    with temp.open('w') as f:
        json.dump(value, f); f.flush(); os.fsync(f.fileno())
    temp.replace(path)


http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def healthy(expected):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            obj = inspect(container(c['service']))
            assert obj['Image'] == expected and obj['State']['Running']
            ip = next(iter(obj['NetworkSettings']['Networks'].values()))['IPAddress']
            with http.open('http://' + ip + ':' + str(p['containerPort']) + p['healthPath'], timeout=3) as response:
                assert response.status == 200
                assert json.loads(response.read(16385)).get('status') == 'ok'
            with http.open(c['url'] + p['healthPath'], timeout=5) as response:
                assert response.status == 200
                assert json.loads(response.read(16385)).get('status') == 'ok'
            return True
        except Exception:
            time.sleep(2)
    return False


def restart_web():
    if c['webService']:
        command(compose + ['up', '-d', '--no-deps', '--pull', 'never', '--force-recreate', c['webService']])
    if c['proxy']:
        command(['docker', 'exec', c['proxy'], 'nginx', '-t'])
        command(['docker', 'exec', c['proxy'], 'nginx', '-s', 'reload'])


record = json.loads(record_file.read_text()) if record_file.exists() else None
if record and record.get('status') == 'SUCCEEDED' and healthy(p['imageId']):
    print(json.dumps(record['result'])); sys.exit(0)
if record and record.get('status') == 'FAILED':
    print(json.dumps(record['result'])); sys.exit(0)
old = inspect(container(c['service']))
assert old['Config']['Labels']['com.docker.compose.service'] == c['service']
resolved=json.loads(command(compose+['config','--format','json']).stdout)
assert resolved['name']==old['Config']['Labels']['com.docker.compose.project']
actual_mounts={m['Destination']:m['Name'] if m['Type']=='volume' else m['Source'] for m in old['Mounts']}
for mount in resolved['services'][c['service']].get('volumes',[]):
    source=resolved['volumes'][mount['source']]['name'] if mount['type']=='volume' else mount['source']
    assert actual_mounts[mount['target']]==source
actual_env=dict(item.split('=',1) for item in old['Config']['Env'])
assert all(actual_env.get(k)==str(v) for k,v in resolved['services'][c['service']].get('environment',{}).items())
if c['webService']:
    web=inspect(container(c['webService']))
    web_mounts={m['Destination']:m['Source'] for m in web['Mounts'] if m['Type']=='bind'}
    assert web_mounts['/usr/share/nginx/html']==str(site)
    for mount in resolved['services'][c['webService']].get('volumes',[]):
        if mount['type']=='bind':assert web_mounts[mount['target']]==mount['source']
if not record:
    uid = command(['docker', 'exec', old['Id'], 'id', '-u']).stdout.decode().strip()
    gid = command(['docker', 'exec', old['Id'], 'id', '-g']).stdout.decode().strip()
    assert uid.isdigit() and gid.isdigit()
    record = {'status': 'RUNNING', 'previousImage': old['Image'], 'user': uid + ':' + gid,
              'entrypoint': old['Config']['Entrypoint'], 'command': old['Config']['Cmd'],
              'previousOverride': json.loads(override.read_text()) if override.exists() else None,
              'healthcheck': old['Config'].get('Healthcheck')}
    write(record_file, record)
backup_site = state_dir / (release + '-site-previous')
stage_site = site.parent / ('.infra-hub-' + release)
export = 'infra-hub-export-' + release
phase = 'Подготовка сайта'
try:
    if not backup_site.exists():
        shutil.rmtree(stage_site, ignore_errors=True)
        stage_site.mkdir(mode=0o755)
        with tempfile.TemporaryDirectory(dir=state_dir) as work:
            jar = Path(work) / 'app.jar'
            command(['docker', 'rm', '-f', export], check=False)
            command(['docker', 'create', '--name', export, p['imageId']])
            try:
                command(['docker', 'cp', export + ':/app/app.jar', str(jar)])
            finally:
                command(['docker', 'rm', export], check=False)
            with zipfile.ZipFile(jar) as release_jar:
                data = release_jar.read('META-INF/infra-hub/site.zip')
                assert len(data) <= 128 * 1024 * 1024
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                total = 0
                for item in archive.infolist():
                    rel = Path(item.filename)
                    assert not rel.is_absolute() and '..' not in rel.parts and '\\' not in item.filename
                    assert item.file_size <= 32 * 1024 * 1024 and item.compress_size >= 0
                    total += item.file_size
                    assert total <= 128 * 1024 * 1024
                    path = stage_site / rel
                    if item.is_dir():
                        path.mkdir(parents=True, exist_ok=True, mode=0o755)
                    else:
                        path.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
                        with path.open('wb') as f, archive.open(item) as content:
                            shutil.copyfileobj(content, f)
                        path.chmod(0o644)
            assert (stage_site / 'index.html').is_file() and (stage_site / 'app/index.html').is_file()
        # Parent directories were created under umask 077; nginx must be able to traverse them.
        for folder in stage_site.rglob('*'):
            if folder.is_dir(): folder.chmod(0o755)
        stage_site.chmod(0o755)
    phase = 'Переключение Compose'
    next_override = {'services': {c['service']: {'image': p['imageId'], 'user': record['user'], 'entrypoint': record['entrypoint'], 'command': record['command']}}}
    health = record.get('healthcheck')
    if health:
        spec = {'test': health['Test']}
        for docker_key, compose_key in [('Interval','interval'),('Timeout','timeout'),('StartPeriod','start_period')]:
            if health.get(docker_key): spec[compose_key] = str(health[docker_key]) + 'ns'
        if health.get('Retries'): spec['retries'] = health['Retries']
        next_override['services'][c['service']]['healthcheck'] = spec
    write(override, next_override)
    command(compose + ['-f', str(override), 'up', '-d', '--no-deps', '--pull', 'never', c['service']])
    if not backup_site.exists():
        site.rename(backup_site)
        stage_site.rename(site)
    restart_web()
    phase = 'Проверка API и версии'
    assert healthy(p['imageId'])
    result = {'status': 'SUCCEEDED', 'deploymentId': release, 'imageId': p['imageId'], 'message': 'Существующий Compose обновлён; backend, сайт, внешний API и версия проверены. Данные и сети сохранены.'}
    record.update(status='SUCCEEDED', result=result)
    write(record_file, record)
except Exception:
    restored = False
    try:
        if backup_site.exists():
            shutil.rmtree(site)
            backup_site.rename(site)
        old_override = record['previousOverride'] or {'services': {c['service']: {'image': record['previousImage'], 'user': record['user'], 'entrypoint': record['entrypoint'], 'command': record['command']}}}
        write(override, old_override)
        command(compose + ['-f', str(override), 'up', '-d', '--no-deps', '--pull', 'never', c['service']])
        restart_web()
        restored = healthy(record['previousImage'])
    except Exception:
        pass
    result = {'status': 'FAILED' if restored else 'INTERRUPTED', 'deploymentId': release, 'message': phase + ': ошибка. ' + ('Предыдущая версия восстановлена.' if restored else 'Нужна повторная сверка.')}
    record.update(status='FAILED' if restored else 'RUNNING', result=result)
    write(record_file, record)
finally:
    shutil.rmtree(stage_site, ignore_errors=True)
print(json.dumps(result))
