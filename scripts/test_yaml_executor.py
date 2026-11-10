"""Run the real fixed remote protocol against disposable local directories only."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

SCRIPT = Path(__file__).resolve().parents[1] / 'backend/src/main/resources/deploy/apply-yaml.py'


class YamlExecutorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='infra-hub-yaml-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = SCRIPT.read_text().replace("Path('/opt/infra-hub/applications')", repr(self.root / 'receipts').replace('PosixPath', 'Path'))
        self.app = str(uuid.uuid4())

    def payload(self, run="printf applied >> applied", verify="test -f applied", rollback=None):
        def step(name, command): return {'name': name, 'run': command, 'timeoutSeconds': 2}
        return {'applicationId': self.app, 'deploymentId': str(uuid.uuid4()), 'imageId': '', 'environment': {'HUB_COMMIT_SHA': 'a' * 40, 'TEST_SECRET': 'private-not-logged'}, 'definition': {'directory': str(self.root), 'steps': [step('Deploy', run)], 'verify': [step('Check', verify)], 'rollback': [step('Restore', rollback)] if rollback else []}}

    def invoke(self, payload):
        process = subprocess.run(['python3', '-c', self.script], input=json.dumps(payload), text=True, capture_output=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertNotIn('private-not-logged', process.stdout + process.stderr)
        return json.loads(process.stdout)

    def test_success_reconciliation_never_repeats_apply_and_detects_changed_target(self):
        payload = self.payload()
        self.assertEqual(self.invoke(payload)['status'], 'SUCCEEDED')
        payload = dict(reversed(list(payload.items())))
        self.assertEqual(self.invoke(payload)['status'], 'SUCCEEDED')
        self.assertEqual((self.root / 'applied').read_text(), 'applied')
        (self.root / 'applied').unlink()
        self.assertEqual(self.invoke(payload)['status'], 'INTERRUPTED')
        self.assertFalse((self.root / 'applied').exists())

    def test_failed_health_runs_rollback_once_and_records_failure(self):
        payload = self.payload(verify='false', rollback='rm -f applied; printf restored >> restored')
        self.assertEqual(self.invoke(payload)['status'], 'FAILED')
        self.assertEqual(self.invoke(payload)['status'], 'FAILED')
        self.assertEqual((self.root / 'restored').read_text(), 'restored')
        self.assertFalse((self.root / 'applied').exists())

    def test_timeout_kills_step_tree_and_keeps_uncertainty_without_blind_retry(self):
        payload = self.payload(run='sleep 3; printf unexpected > delayed', verify='test -f delayed')
        payload['definition']['steps'][0]['timeoutSeconds'] = 1
        self.assertEqual(self.invoke(payload)['status'], 'INTERRUPTED')
        self.assertEqual(self.invoke(payload)['status'], 'INTERRUPTED')
        self.assertFalse((self.root / 'delayed').exists())

    def test_running_orphan_step_blocks_reconciliation(self):
        payload = self.payload()
        record = self.root / 'receipts' / self.app
        record.mkdir(parents=True)
        import hashlib
        raw = json.dumps(payload, sort_keys=True, separators=(',', ':'))
        (record / ('yaml-' + payload['deploymentId'] + '.json')).write_text(json.dumps({'fingerprint': hashlib.sha256(raw.encode()).hexdigest(), 'status': 'RUNNING'}))
        pid = os.getpid()
        (record / ('yaml-' + payload['deploymentId'] + '-process.json')).write_text(json.dumps({'pid': pid, 'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip(), 'started': Path('/proc/' + str(pid) + '/stat').read_text().rsplit(')', 1)[1].split()[19]}))
        self.assertEqual(self.invoke(payload)['status'], 'INTERRUPTED')
        self.assertFalse((self.root / 'applied').exists())


if __name__ == '__main__':
    unittest.main()
