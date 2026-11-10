# Fixed read-only Linux host probe. No subprocesses, sudo, installs or user input.
import json
import os
import platform
import time
from pathlib import Path


def cpu():
    values = [int(v) for v in Path('/proc/stat').read_text().splitlines()[0].split()[1:9]]
    return sum(values), values[3] + values[4]


first = cpu()
time.sleep(0.2)
second = cpu()
delta = second[0] - first[0]
usage = None if delta <= 0 else round(100 * (1 - (second[1] - first[1]) / delta), 1)
mem = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemTotal:', 'MemAvailable:'))}
disk = os.statvfs('/')
release = platform.freedesktop_os_release()
print(json.dumps({
    'hostname': platform.node(), 'os': release.get('PRETTY_NAME', 'Linux'),
    'architecture': platform.machine(), 'cpuCount': os.cpu_count(), 'cpuUsagePercent': usage,
    'memoryTotalBytes': mem['MemTotal'], 'memoryUsedBytes': mem['MemTotal'] - mem['MemAvailable'],
    'diskTotalBytes': disk.f_blocks * disk.f_frsize, 'diskUsedBytes': (disk.f_blocks - disk.f_bfree) * disk.f_frsize,
    'loadAverage': os.getloadavg()[0], 'uptimeSeconds': int(float(Path('/proc/uptime').read_text().split()[0]))
}))
