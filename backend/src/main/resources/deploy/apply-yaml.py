"""Versioned owner-configured shell steps over pinned SSH; receipts prevent blind replay."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

raw = sys.stdin.buffer.read(262145)
assert len(raw) <= 262144
payload = json.loads(raw)
app = str(uuid.UUID(payload['applicationId']))
release = str(uuid.UUID(payload['deploymentId']))
definition = payload['definition']
environment = dict(os.environ)
environment.update(payload['environment'])
directory = Path(definition['directory'])
assert directory.is_absolute() and str(directory) != '/' and '..' not in directory.parts
assert directory.is_dir(), 'Deployment directory must already exist'
os.umask(0o077)
state = Path('/opt/infra-hub/applications') / app
state.mkdir(parents=True, exist_ok=True)
lock = (state / 'yaml.lock').open('a')
# Bounded lock wait prevents hanging forever behind an external/manual executor.
limit = time.monotonic() + 30
while True:
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        if time.monotonic() >= limit:
            raise RuntimeError('Another executor still holds the application lock')
        time.sleep(0.1)
record = state / ('yaml-' + release + '.json')
fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
active_file = state / ('yaml-' + release + '-process.json')
boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def save(value):
    temporary = record.with_suffix('.tmp')
    with temporary.open('w') as file:
        json.dump(value, file)
        file.flush()
        os.fsync(file.fileno())
    temporary.replace(record)


def steps(stage):
    for step in definition.get(stage, []):
        print(stage + ': ' + step['name'], file=sys.stderr, flush=True)
        child = subprocess.Popen(['sh', '-eu', '-c', step['run']], cwd=directory, env=environment,
                                 stdout=sys.stderr, stderr=sys.stderr, start_new_session=True)
        active_file.write_text(json.dumps({'pid': child.pid, 'boot': boot, 'started': Path('/proc/' + str(child.pid) + '/stat').read_text().rsplit(')', 1)[1].split()[19]}))
        try:
            code = child.wait(timeout=step['timeoutSeconds'])
        except BaseException:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            raise
        finally:
            active_file.unlink(missing_ok=True)
        if code != 0:
            raise RuntimeError('Step failed')


def result(status, message):
    return {'status': status, 'deploymentId': release, 'imageId': payload['imageId'], 'message': message}


prior = json.loads(record.read_text()) if record.exists() else None
if prior:
    assert prior['fingerprint'] == fingerprint, 'Receipt belongs to different inputs'
    if prior['status'] == 'FAILED':
        print(json.dumps(prior['result']))
        sys.exit(0)
    if active_file.exists():
        active = json.loads(active_file.read_text())
        try:
            running = active['boot'] == boot and Path('/proc/' + str(active['pid']) + '/stat').read_text().rsplit(')', 1)[1].split()[19] == active['started']
        except FileNotFoundError:
            running = False
        if running:
            print(json.dumps(result('INTERRUPTED', 'A previous remote step is still running. Wait and inspect before reconciliation.')))
            sys.exit(0)
    try:
        # Verification is the only user stage replayed on reconciliation. Deploy and
        # rollback stages are never blindly repeated after an uncertain side effect.
        steps('verify')
        answer = result('SUCCEEDED', 'Existing YAML deployment verified; apply steps were not repeated.')
        save({'fingerprint': fingerprint, 'status': 'SUCCEEDED', 'result': answer})
    except Exception:
        answer = result('INTERRUPTED', 'Verification could not confirm the existing deployment. Inspect the target; apply steps were not repeated.')
    print(json.dumps(answer))
    sys.exit(0)

save({'fingerprint': fingerprint, 'status': 'RUNNING'})
try:
    steps('steps')
    steps('verify')
    answer = result('SUCCEEDED', 'YAML steps and verification completed.')
    save({'fingerprint': fingerprint, 'status': 'SUCCEEDED', 'result': answer})
except Exception:
    if definition.get('rollback'):
        try:
            steps('rollback')
            answer = result('FAILED', 'YAML execution failed; configured rollback steps completed.')
            save({'fingerprint': fingerprint, 'status': 'FAILED', 'result': answer})
        except Exception:
            answer = result('INTERRUPTED', 'YAML execution and rollback could not be confirmed. Inspect the target.')
    else:
        answer = result('INTERRUPTED', 'YAML execution could not be confirmed; no rollback is configured. Inspect the target.')
print(json.dumps(answer))
