"""Trusted remote release protocol. Payload is JSON on stdin, never shell input."""
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

p = json.loads(sys.stdin.buffer.read(65537))
app = str(uuid.UUID(p['applicationId']))
release = str(uuid.UUID(p['deploymentId']))
assert app == p['applicationId'] and release == p['deploymentId']
assert re.fullmatch(r'sha256:[a-f0-9]{64}', p['imageId'])
assert re.fullmatch(r'[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}', p['imageReference'])
assert type(p['hostPort']) is int and 1024 <= p['hostPort'] <= 65535
assert type(p['containerPort']) is int and 1 <= p['containerPort'] <= 65535
assert re.fullmatch(r'/[A-Za-z0-9/_.~,-]*', p['healthPath']) and len(p['healthPath']) <= 200
assert len(p['environment']) <= 50
assert all(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,79}', k) and isinstance(v, str) and len(v) <= 4096 and not any(c in v for c in '\n\r\0') for k, v in p['environment'].items())
os.umask(0o077)
root = Path(os.environ.get('INFRA_HUB_DEPLOY_ROOT', '/opt/infra-hub/applications')) / app
root.mkdir(parents=True, exist_ok=True, mode=0o700)
lock = (root / 'lock').open('a')
limit = time.monotonic() + 30
while True:
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        if time.monotonic() > limit:
            print(json.dumps({'status': 'INTERRUPTED', 'message': 'Предыдущая удалённая операция ещё выполняется. Повторите сверку.'}))
            sys.exit(0)
        time.sleep(0.5)


def read(path):
    return json.loads(path.read_text()) if path.exists() else None


def write(path, value):
    temp = path.with_suffix('.tmp')
    with temp.open('w') as f:
        json.dump(value, f)
        f.flush()
        os.fsync(f.fileno())
    temp.replace(path)
    fd = os.open(root, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def docker(*args, check=True):
    child = subprocess.run(['docker', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and child.returncode:
        raise RuntimeError('docker command failed')
    return child


def inspect(name):
    result = docker('inspect', name, check=False)
    if result.returncode:
        # Distinguish a missing container from a daemon/network failure.
        docker('info', '--format', '{{.ServerVersion}}')
        return None
    return json.loads(result.stdout)[0]


def owned(state, deployment):
    labels = state['Config'].get('Labels') or {}
    return labels.get('dev.infrahub.application') == app and labels.get('dev.infrahub.deployment') == deployment


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
name = 'infra-hub-' + app + '-' + release
record_path = root / (release + '.json')
current_path = root / 'current.json'
record = read(record_path)
current = read(current_path)
if record and record['status'] == 'FAILED':
    print(json.dumps(record['result']))
    sys.exit(0)
if record and record['status'] == 'SUCCEEDED':
    # A receipt proves past success. Confirm the current container before acknowledging a replay.
    if not current or current['deploymentId'] != release:
        print(json.dumps({'status': 'INTERRUPTED', 'message': 'Текущий удалённый выпуск отличается от квитанции. Требуется сверка.'}))
        sys.exit(0)
    try:
        state = inspect(name)
        assert state and owned(state, release) and state['Image'] == p['imageId'] and state['State']['Running']
        with http.open('http://127.0.0.1:' + str(p['hostPort']) + p['healthPath'], timeout=2) as response:
            assert 200 <= response.status < 300
        print(json.dumps(record['result']))
        sys.exit(0)
    except Exception:
        record['status'] = 'RUNNING'
        write(record_path, record)
previous = record['previous'] if record else current
stage = 'Проверка образа'
try:
    image = json.loads(docker('image', 'inspect', p['imageId']).stdout)[0]
    assert image['Id'] == p['imageId']
    if current and current['deploymentId'] not in (release, (previous or {}).get('deploymentId')):
        raise RuntimeError('current release changed')
    if previous:
        old = inspect(previous['container'])
        assert old and owned(old, previous['deploymentId']) and old['Image'] == previous['imageId']
    existing = inspect(name)
    if existing:
        assert owned(existing, release) and existing['Image'] == p['imageId']
    if not record:
        record = {'status': 'RUNNING', 'previous': previous, 'imageId': p['imageId']}
        write(record_path, record)
    assert record['imageId'] == p['imageId']
    stage = 'Запуск контейнера'
    if not existing:
        with tempfile.NamedTemporaryFile(mode='w', dir=root, prefix='env-', delete=False) as env:
            env_name = env.name
            for key, value in p['environment'].items():
                env.write(key + '=' + value + '\n')
        try:
            docker('create', '--name', name, '--label', 'dev.infrahub.application=' + app,
                   '--label', 'dev.infrahub.deployment=' + release, '--label', 'dev.infrahub.image=' + p['imageReference'],
                   '--restart', 'unless-stopped', '--read-only', '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m',
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '10001:10001',
                   '--memory', '512m', '--memory-swap', '512m', '--cpus', '1', '--pids-limit', '256',
                   '--log-driver', 'local', '--log-opt', 'max-size=10m', '--log-opt', 'max-file=2',
                   '--env-file', env_name, '-p', '127.0.0.1:' + str(p['hostPort']) + ':' + str(p['containerPort']), p['imageId'])
        finally:
            os.unlink(env_name)
    if previous and previous['container'] != name:
        docker('stop', '--time', '10', previous['container'])
    docker('start', name)
    stage = 'Проверка здоровья и версии'
    deadline = time.monotonic() + 60
    healthy = False
    while time.monotonic() < deadline:
        state = inspect(name)
        if not state or not owned(state, release) or state['Image'] != p['imageId']:
            break
        if state['State']['Running']:
            try:
                with http.open('http://127.0.0.1:' + str(p['hostPort']) + p['healthPath'], timeout=2) as response:
                    healthy = 200 <= response.status < 300
                if healthy:
                    break
            except Exception:
                pass
        time.sleep(1)
    if not healthy:
        raise RuntimeError('health check failed')
    # Preserve a short stability window and verify the exact container version again.
    time.sleep(2)
    state = inspect(name)
    assert state and state['State']['Running'] and state['Image'] == p['imageId'] and owned(state, release)
    result = {'status': 'SUCCEEDED', 'message': 'Контейнер запущен; HTTP-проверка и версия образа подтверждены.', 'imageId': p['imageId'], 'deploymentId': release}
    write(current_path, {'deploymentId': release, 'container': name, 'imageId': p['imageId']})
    record.update(status='SUCCEEDED', result=result)
    write(record_path, record)
except Exception:
    # Never touch containers without our exact application/deployment labels.
    restored = False
    try:
        candidate = inspect(name)
        if candidate:
            assert owned(candidate, release)
            docker('rm', '-f', name)
        if previous:
            old = inspect(previous['container'])
            assert old and owned(old, previous['deploymentId']) and old['Image'] == previous['imageId']
            docker('start', previous['container'])
            old = inspect(previous['container'])
            assert old and old['State']['Running']
            # Previous release used the same immutable application ports and health path.
            deadline = time.monotonic() + 60
            while True:
                try:
                    with http.open('http://127.0.0.1:' + str(p['hostPort']) + p['healthPath'], timeout=2) as response:
                        assert 200 <= response.status < 300
                    break
                except Exception:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(1)
            write(current_path, previous)
        else:
            current_path.unlink(missing_ok=True)
        restored = True
    except Exception:
        pass
    result = {'status': 'FAILED' if restored else 'INTERRUPTED',
              'message': stage + ': ошибка. ' + ('Предыдущий контейнер восстановлен.' if previous and restored else 'Работающий выпуск отсутствует.' if restored else 'Не удалось подтвердить восстановление; повторите сверку.'),
              'deploymentId': release}
    if record:
        record.update(status='FAILED' if restored else 'RUNNING', result=result)
        write(record_path, record)
print(json.dumps(result))
