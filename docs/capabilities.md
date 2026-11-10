# Capabilities and boundaries

This describes the current source, not a guarantee that every combination of host and application has been tested. Start with the [English](self-hosting.md) or [Russian](self-hosting.ru.md) installation guide.

## General functionality

[Deployment YAML](deployment-yaml.md) defines project-specific build/deploy/verify/rollback shell steps, with multiple bindings, exact source snapshots, optional JAR images and a generic SSH executor. It supports pinned SSH-only targets for SCRIPT applications.

- Projects, repository links, exact SHA source snapshots, persisted build steps/logs and cancellation.
- PostgreSQL queue with transactional claim, one active heavy build, execution lease, stale-worker fencing, request UUID idempotency and explicit interrupted states.
- Real local Java example. Repository executor uses a separate rootless Docker daemon with cgroup v2. General builder provides Java 21, Node.js 22 and Python 3.
- Archive delivery through a worker-secret and live-lease protected internal API; optional read-only GitHub App access for private sources.
- One executable JAR packaged using the platform Dockerfile. Digest, JAR SHA-256 and size are persisted. No repository Dockerfile execution.
- SSH server onboarding with a dedicated key and pinned ED25519 host key; status represents the last check, not continuous monitoring.
- STANDALONE application deployment, image transfer over SSH, content verification, HTTP health checks, receipts, interruption reconciliation and previous-version rollback.
- Branch polling pipelines, pause/resume and explicit releases. Build success and deployment success are separate states.
- MCP with revocable keys, per-project scope, three permission levels, idempotent writes and read/log/artifact access.

## Advanced and project-specific functionality

COMPOSE adoption uses a pre-existing compatible application stack. The protocol expects particular configuration keys, layouts, Docker ownership and health behavior. It is not an arbitrary Compose YAML deploy service. Inspect [deployments](deployments.md) before adopting a stack.

Android is an Aukni integration: allowed app IDs/URLs, version file layout, signing files, release artifact names and upload paths contain project-specific constraints. `build-aukni-web.py`, web/Android bundlers and the SDK-equipped Dockerfile likewise serve that integration. They are included as source for adaptation, not advertised as a generic Android pipeline. The generic self-host overlay intentionally mounts no signing secrets or Android SDK.

## Bounds

| Resource | Current bound |
| --- | --- |
| GitHub source archive | 32 MiB; no `.git`, LFS or submodules |
| Repository command | 40 minutes; container watchdog about 46 minutes |
| Processes | 512 per repository container |
| Temporary storage | `/work` up to 4 GiB, `/tmp` up to 512 MiB; consumes job RAM |
| Job resources | Initially 6 CPU / 16 GiB; API ceilings default to 20 CPU / 18 GiB |
| Published JAR | One regular file, up to 256 MiB |
| Deployment image export | Up to 2 GiB |
| Build concurrency | One active heavy build for the installation |

## Not yet provided

Team/RBAC or multi-tenant hosting; a universal workflow YAML engine; PR check publishing and release tags; Kubernetes/HA; Windows/macOS workers; a general Android setup wizard; automatic retention/garbage collection; persistent shared dependency caching; a verified one-command installer; application-level encryption of environment values. Operator-owned backups and restoration tests remain necessary.
