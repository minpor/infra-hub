# Deployment files

Start with [`self-host/`](self-host/) and the [English](../docs/self-hosting.md) / [Russian](../docs/self-hosting.ru.md) guide.

- `self-host/compose.yaml`: generic owner-authenticated panel and Java example worker.
- `self-host/compose.runner.yaml`: optional rootless repository worker and SSH deployer.
- `runner/Dockerfile.generic`, `compose.proxy.yaml`, `compose.registry.yaml`: general runner and supporting services on the separate rootless daemon.
- `Dockerfile.api`, `Dockerfile.web`, `Dockerfile.worker`, `Dockerfile.deployer`: runtime images; API/web consume files staged by `scripts/prepare-release.sh`.

The remaining `compose.live.yaml`, `compose.repositories.yaml`, `compose.deployments.yaml`, older Caddyfiles and `enable-*`/`update-*` scripts contain assumptions about a previous installation, backups, paths and release bundles. They are historical maintenance references, not a fresh-install interface. Read/adapt them before using your own host. Never run privileged migration or live smoke scripts against an unrelated installation.

The SDK-equipped runner Dockerfile and Aukni helpers are a project integration example. Generic installation does not require an Android SDK or signing keys. No live credentials, populated environment files, private keys or release databases belong in this directory.
