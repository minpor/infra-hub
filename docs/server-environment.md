# Server environment

Use Linux with Docker Engine/Compose v2 and public DNS/TLS ingress for the panel. Repository jobs additionally need systemd, cgroup v2, an independently provisioned rootless Docker account and working resource delegation. The general toolchain is JDK 21 / Node.js 22 / Python 3; additional project tooling belongs in a custom builder.

Separate panel control processes from repository execution. Keep PostgreSQL internal and registry loopback-only. Secure SSH, choose deploy accounts deliberately, verify host keys, keep external backups and monitor disk/resource consumption. Existing application hosts require SSH, Python 3, Docker/Compose and writable deployment receipt directories.

No universal hardware sizing or OS-installation automation is claimed. See [self-hosting](self-hosting.md), [resources](resources.md), [runner settings](runner-settings.md) and [operations](operations.md).
