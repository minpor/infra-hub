"""Transport adapter: keep repository assertions, run the nginx fixture locally inside the sandbox."""
import atexit
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile

original = subprocess.check_output
child = None
work = tempfile.TemporaryDirectory(prefix='nginx-fixture-')
root = Path(work.name)


def cleanup():
    if child and child.poll() is None:
        child.terminate()
        try: child.wait(timeout=5)
        except subprocess.TimeoutExpired: child.kill(); child.wait()


def docker(command, **kwargs):
    global child
    if command[0] != 'docker': return original(command, **kwargs)
    if command[1] == 'run':
        mount = command[command.index('-v') + 1].split(':')[0]
        config = Path(mount).read_text()
        for directory in ['body', 'proxy', 'fastcgi', 'uwsgi', 'scgi']:(root/directory).mkdir()
        config = config.replace('http {', 'http {\n fastcgi_temp_path '+str(root/'fastcgi')+';\n uwsgi_temp_path '+str(root/'uwsgi')+';\n scgi_temp_path '+str(root/'scgi')+';\n client_body_temp_path '+str(root/'body')+';\n proxy_temp_path '+str(root/'proxy')+';')
        file = root/'nginx.conf';file.write_text(config)
        child = subprocess.Popen(['/usr/sbin/nginx','-c',str(file),'-p',str(root),'-g','daemon off; pid '+str(root/'nginx.pid')+'; error_log stderr;'])
        return 'sandbox-nginx-fixture'
    if command[1] == 'port':return '127.0.0.1:8081'
    if command[1] == 'rm':cleanup();return ''
    raise RuntimeError('Unsupported Docker fixture operation')


atexit.register(cleanup)
subprocess.check_output = docker
try:runpy.run_path(sys.argv[1],run_name='__main__')
finally:cleanup();work.cleanup()
