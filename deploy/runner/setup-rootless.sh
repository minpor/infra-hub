#!/usr/bin/env bash
# Run as root on the Infra Hub host. Leaves the panel's rootful Docker untouched.
set -euo pipefail
test "$(id -u)" = 0
apt-get update
apt-get install -y docker-ce-rootless-extras uidmap slirp4netns dbus-user-session
if ! id infra-build >/dev/null 2>&1; then useradd --create-home --shell /bin/bash infra-build; fi
runner_uid="$(id -u infra-build)"
mkdir -p "/etc/systemd/system/user@${runner_uid}.service.d"
cat > "/etc/systemd/system/user@${runner_uid}.service.d/infra-hub.conf" <<'EOF'
[Service]
Delegate=cpu cpuset io memory pids
MemoryMax=20G
CPUQuota=2000%
TasksMax=2048
EOF
systemctl daemon-reload
loginctl enable-linger infra-build
systemctl start "user@${runner_uid}.service"
runuser -u infra-build -- env XDG_RUNTIME_DIR="/run/user/$runner_uid" DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$runner_uid/bus" dockerd-rootless-setuptool.sh install --force
runuser -u infra-build -- env XDG_RUNTIME_DIR="/run/user/$runner_uid" DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$runner_uid/bus" systemctl --user enable --now docker
docker --host "unix:///run/user/$runner_uid/docker.sock" info --format '{{json .SecurityOptions}} {{.CgroupDriver}} {{.CgroupVersion}}'
printf 'HUB_RUNNER_UID=%s\n' "$runner_uid"
printf 'HUB_RUNNER_SOCKET_GID=%s\n' "$(stat -c %g /run/user/$runner_uid/docker.sock)"
