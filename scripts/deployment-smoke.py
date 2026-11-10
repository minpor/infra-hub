"""Run on the hub: isolated DB/API/deployer, synthetic images, dedicated test app on an explicitly selected disposable server."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1] / 'deploy'
test_server_id = str(uuid.UUID(os.environ['HUB_SMOKE_SERVER_ID']))
test_server_host = os.environ['HUB_SMOKE_SERVER_HOST']
test_server_user = os.environ['HUB_SMOKE_SERVER_USER']
compose = ['docker', 'compose', '-p', 'infra-hub-deployment-test', '-f', str(root / 'runner/compose.deployments.test.yaml')]
cli = ['docker', '--host', f"unix:///run/user/{os.environ['HUB_RUNNER_UID']}/docker.sock"]
registry = 'infra-hub-deployment-test-registry'
app_id = str(uuid.uuid4())
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
server = None
images = []


def call(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def sql(statement):
    return call(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub_test', '-At', '-v', 'ON_ERROR_STOP=1'], input=statement)


def api(path, body=None):
    req = urllib.request.Request('http://127.0.0.1:18082/api' + path, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json', 'X-Infra-Hub': 'local-ui'})
    with http.open(req, timeout=10) as response:
        return json.load(response)


def wait(predicate, timeout=300):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(1)
    raise RuntimeError('Timed out waiting for deployment')


def target(command):
    return call(compose + ['exec', '-T', 'deployer', 'ssh', '-F', '/dev/null', '-T', '-i', '/work/ssh/' + server['id'] + '/identity', '-p', str(server['port']), '-l', server['username'], '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=none', '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=/test/known_hosts', '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'ConnectTimeout=5', server['host'], command])


def result(deployment, wanted='SUCCEEDED'):
    row = wait(lambda: next((d for d in api('/deployments') if d['id'] == deployment['id'] and d['status'] in ('SUCCEEDED', 'FAILED', 'INTERRUPTED')), None))
    logs = ''.join(entry['text'] for entry in api('/deployments/' + row['id'] + '/logs'))
    assert row['status'] == wanted, (row, logs)
    assert 'smoke-secret-value' not in logs
    return row


def launch(build):
    return api('/applications/' + app_id + '/deployments', {'requestId': str(uuid.uuid4()), 'buildId': build})


try:
    subprocess.run(cli + ['run', '-d', '--name', registry, '--memory=256m', '--cpus=1', '--pids-limit=128', '-p', '127.0.0.1:15001:5000', 'registry@sha256:38907510915c3f1604c7e83d6ab602ab3020d092f73bdaed8696773693ff768c'], check=True)
    subprocess.run(compose + ['up', '-d', '--wait'], check=True)
    def ready():
        try:
            return api('/health')
        except Exception:
            return None
    wait(ready)
    # Copy public connection metadata only, never key bytes or environment secrets.
    live = ['docker', 'compose', '-f', '/opt/infra-hub/stacks/panel/compose.live.yaml', '-f', '/opt/infra-hub/stacks/panel/compose.repositories.yaml']
    statement = f"SELECT row_to_json(s) FROM servers s WHERE id='{test_server_id}'"
    server = json.loads(call(live + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub', '-At', '-c', statement]))
    assert server['host'] == test_server_host and server['username'] == test_server_user
    columns = ['id', 'name', 'host', 'port', 'username', 'public_key', 'host_key', 'status']
    quote = lambda v: "'" + str(v).replace("'", "''") + "'"
    sql('INSERT INTO servers(' + ','.join(columns) + ') VALUES (' + ','.join(quote(server[c]) for c in columns) + ')')
    (root / 'known_hosts').write_text(f"[{server['host']}]:{server['port']} {server['host_key']}\n{server['host']} {server['host_key']}\n")
    project = api('/projects', {'name': 'Synthetic deploy smoke'})
    app = api('/applications', {'id': app_id, 'name': 'Synthetic HTTP fixture', 'projectId': project['id'], 'serverId': server['id'], 'hostPort': 18090, 'containerPort': 8080, 'healthPath': '/health', 'environment': {'DEMO_SECRET': 'smoke-secret-value'}})
    assert app['environmentKeys'] == ['DEMO_SECRET'] and 'smoke-secret-value' not in json.dumps(app)
    builds = []
    for version, health in [('v1', '200'), ('v2', '200'), ('broken', '503')]:
        tag = 'localhost:15001/deployment-test/' + app_id + ':' + version
        subprocess.run(cli + ['build', '--network=none', '--build-arg', 'VERSION=' + version, '--build-arg', 'HEALTH_STATUS=' + health, '-t', tag, str(root / 'fixture')], check=True)
        subprocess.run(cli + ['push', tag], check=True)
        reference = json.loads(call(cli + ['image', 'inspect', '--format', '{{json .RepoDigests}}', tag]))[0]
        image_id = call(cli + ['image', 'inspect', '--format', '{{.Id}}', tag])
        images.append((tag, image_id))
        build = str(uuid.uuid4()); request = str(uuid.uuid4())
        sql(f"INSERT INTO builds(id,project_id,request_id,recipe,status,image_reference,artifact_sha256,artifact_size,repository,source_ref,commit_sha,build_command) VALUES ('{build}','{project['id']}','{request}','REPOSITORY_V1','SUCCEEDED',{quote(reference)},repeat('a',64),100,'fixture/synthetic','main',repeat('a',40),'synthetic fixture')")
        builds.append(build)
    first = result(launch(builds[0]))
    assert target('curl --fail --silent http://127.0.0.1:18090/') == 'v1'
    first_container = target("docker ps -aq --filter label=dev.infrahub.application=" + app_id)
    # Simulate a lost reply after the remote receipt was committed, before the centre saved success.
    sql(f"UPDATE applications SET current_deployment=NULL WHERE id='{app_id}'; UPDATE deployments SET status='INTERRUPTED' WHERE id='{first['id']}'")
    api('/deployments/' + first['id'] + '/retry', {})
    result(first)
    assert target("docker ps -aq --filter label=dev.infrahub.application=" + app_id) == first_container
    second = result(launch(builds[1]))
    assert target('curl --fail --silent http://127.0.0.1:18090/') == 'v2'
    failed = result(launch(builds[2]), 'FAILED')
    assert 'восстановлен' in failed['message']
    assert target('curl --fail --silent http://127.0.0.1:18090/') == 'v2'
    rollback = api('/applications/' + app_id + '/rollback', {'requestId': str(uuid.uuid4())})
    result(rollback)
    assert target('curl --fail --silent http://127.0.0.1:18090/') == 'v1'
    # Missing registry object must remain visible as an unresolved operation, with no target mutation.
    bad = str(uuid.uuid4())
    sql(f"INSERT INTO builds(id,project_id,request_id,recipe,status,image_reference,artifact_sha256,artifact_size,repository,source_ref,commit_sha,build_command) VALUES ('{bad}','{project['id']}','{uuid.uuid4()}','REPOSITORY_V1','SUCCEEDED','localhost:15001/missing@sha256:{'f'*64}',repeat('a',64),100,'fixture/synthetic','main',repeat('a',40),'synthetic fixture')")
    result(launch(bad), 'INTERRUPTED')
    assert target('curl --fail --silent http://127.0.0.1:18090/') == 'v1'
    second_app = api('/applications', {'id': str(uuid.uuid4()), 'name': 'Host key failure fixture', 'projectId': project['id'], 'serverId': server['id'], 'hostPort': 18091, 'containerPort': 8080, 'healthPath': '/health'})
    wrong_key = server['host_key'][:-1] + ('s' if server['host_key'][-1] != 's' else 'r')
    sql('UPDATE servers SET host_key=' + quote(wrong_key) + ' WHERE id=' + quote(server['id']))
    rejected_ssh = api('/applications/' + second_app['id'] + '/deployments', {'requestId': str(uuid.uuid4()), 'buildId': builds[0]})
    result(rejected_ssh, 'INTERRUPTED')
    ssh_logs = ''.join(entry['text'] for entry in api('/deployments/' + rejected_ssh['id'] + '/logs'))
    assert 'Host key verification failed' in ssh_logs or 'IDENTIFICATION HAS CHANGED' in ssh_logs
    sql('UPDATE servers SET host_key=' + quote(server['host_key']) + ' WHERE id=' + quote(server['id']))
    assert target('curl --fail --silent http://127.0.0.1:18090/') == 'v1'
    print(json.dumps({'result': 'PASS', 'application': app_id, 'first': first['id'], 'second': second['id'], 'failedHealth': failed['id'], 'rollback': rollback['id'], 'lostReplyReplay': 'same container', 'pullFailure': 'INTERRUPTED'}), flush=True)
finally:
    if server:
        # Exact randomly generated application label and path; existing target applications are untouched.
        target('docker ps -aq --filter label=dev.infrahub.application=' + app_id + ' | xargs -r docker rm -f; rm -rf /opt/infra-hub/applications/' + app_id)
        if images:
            target('docker image rm ' + ' '.join(image_id for _, image_id in images) + ' >/dev/null 2>&1 || true')
    subprocess.run(compose + ['logs', '--tail', '20', 'deployer'])
    subprocess.run(compose + ['down', '-v'], check=True)
    subprocess.run(cli + ['rm', '-fv', registry])
    for tag, image_id in images:
        subprocess.run(cli + ['image', 'rm', '-f', tag])
