"""Run on prepared host; disposable DB/worker and registry, no live app deployment."""
import json
import os
import subprocess
import sys
import time
import uuid

compose = ['docker', 'compose', '-p', 'infra-hub-image-test', '-f', sys.argv[1]]
docker = ['docker', '--host', f"unix:///run/user/{os.environ['HUB_RUNNER_UID']}/docker.sock"]
registry = 'infra-hub-image-test-registry'


def call(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def sql(statement):
    return call(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'infra_hub', '-d', 'infra_hub_test', '-At', '-v', 'ON_ERROR_STOP=1'], input=statement)


def wait(predicate, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(1)
    raise RuntimeError('Timed out')


def enqueue(command, artifact):
    project, build, request = [str(uuid.uuid4()) for _ in range(3)]
    quote = lambda text: "'" + text.replace("'", "''") + "'"
    sql(f"""
    INSERT INTO projects(id,name) VALUES ('{project}','Image smoke {project}');
    INSERT INTO builds(id,project_id,request_id,recipe,repository,source_ref,commit_sha,build_command,artifact_path)
    VALUES ('{build}','{project}','{request}','REPOSITORY_V1','owner/repo','main','{'a'*40}',{quote(command)},{quote(artifact)});
    INSERT INTO build_steps(build_id,position,name) SELECT '{build}',n,'Step '||n FROM generate_series(0,4) n;
    """)
    return build


def result(build):
    return wait(lambda: (value if (value := sql(f"SELECT status FROM builds WHERE id='{build}'")) in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED') else None))


try:
    subprocess.run(docker + ['run', '-d', '--name', registry, '--memory=256m', '--cpus=1', '--pids-limit=128', '-p', '127.0.0.1:15000:5000', os.environ['HUB_REGISTRY_IMAGE']], check=True)
    subprocess.run(compose + ['up', '-d', '--wait'], check=True)
    wait(lambda: sql("SELECT to_regclass('public.builds')") == 'builds')
    command = '''mkdir -p output; printf 'public class Hello { public static void main(String[] args) { System.out.println("image-smoke-ok"); } }' > output/Hello.java; javac output/Hello.java; jar --create --file output/app.jar --main-class Hello -C output Hello.class'''
    build = enqueue(command, 'output/app.jar')
    outcome = result(build)
    assert outcome == 'SUCCEEDED', sql(f"SELECT message FROM builds WHERE id='{build}'; SELECT position||':'||status||':'||coalesce(exit_code::text,'') FROM build_steps WHERE build_id='{build}' ORDER BY position; SELECT text FROM build_logs WHERE build_id='{build}' ORDER BY id")
    image = sql(f"SELECT image_reference FROM builds WHERE id='{build}'")
    assert '@sha256:' in image
    subprocess.run(docker + ['pull', image], check=True)
    output = call(docker + ['run', '--rm', '--network=none', '--memory=128m', '--pids-limit=64', '--read-only', image])
    assert output == 'image-smoke-ok', output
    missing = enqueue('true', 'output/missing.jar')
    assert result(missing) == 'FAILED'
    assert sql(f"SELECT count(*) FROM builds WHERE id='{missing}' AND image_reference IS NOT NULL") == '0'
    assert sql(f"SELECT status FROM build_steps WHERE build_id='{missing}' AND position=2") == 'FAILED'
    # A successful compilation must not become a successful build when push fails.
    subprocess.run(docker + ['stop', registry], check=True)
    rejected = enqueue(command, 'output/app.jar')
    assert result(rejected) == 'FAILED'
    assert sql(f"SELECT count(*) FROM builds WHERE id='{rejected}' AND image_reference IS NOT NULL") == '0'
    assert sql(f"SELECT status FROM build_steps WHERE build_id='{rejected}' AND position=4") == 'FAILED'
    print(json.dumps({'result': 'PASS', 'build': build, 'image': image, 'runtimeOutput': output, 'missingJar': 'FAILED', 'registryUnavailable': 'FAILED'}), flush=True)
finally:
    subprocess.run(compose + ['logs', '--tail', '20', 'worker'])
    subprocess.run(compose + ['down', '-v'], check=True)
    subprocess.run(docker + ['rm', '-fv', registry])
    # Only remove images whose repositories belong to this disposable registry.
    images = call(docker + ['image', 'ls', '--format', '{{.Repository}} {{.ID}}']).splitlines()
    for image in images:
        if image.startswith('localhost:15000/infra-hub/'):
            subprocess.run(docker + ['image', 'rm', '-f', image.split()[1]])
