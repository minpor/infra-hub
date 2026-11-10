# Infra Hub

**Build on your own hardware. Deploy to your own servers.**

[English](README.md) · [Русский](README.ru.md) · [Self-hosting](docs/self-hosting.md) · [Contributing](CONTRIBUTING.md) · [MIT license](LICENSE)

Infra Hub is a self-hosted build and deployment control panel for people running their own projects and Linux servers. Keep your source code on GitHub, run builds on your hardware, publish an immutable image, and deploy it over SSH with health checks and a recorded result.

The software does not require a subscription or purchased CI minutes. You provide the hardware, storage, network access, domain and backups; third-party services may have their own costs.

> **Early development / 0.1.** This is a working project being prepared for broader use. The current installation model has one owner. Repository builds require additional rootless Docker setup. The UI and setup documentation support English and Russian; use the language selector in the header. There is no hosted service, high-availability guarantee or universal GitHub Actions compatibility.

## What it can do

| Area | Implemented behavior |
| --- | --- |
| Projects | GitHub repository links, search, build history and exact commit snapshots |
| Queue | PostgreSQL-backed jobs, idempotent enqueue, execution leases, cancellation and interrupted-job recovery |
| Builds | Separate worker; disposable Java 21 / Node.js containers on a rootless Docker daemon |
| Visibility | Persisted steps/logs, build/deployment minutes, queue wait and per-project runner CPU/RAM settings and dependency cache state |
| Images | Package one executable JAR with a platform-owned Dockerfile, push to a registry and retain its digest |
| Servers | Dedicated SSH keys, host fingerprint confirmation, connection checks and measured CPU/RAM/disk snapshots |
| Deployments | Transfer images over SSH, verify imported content, check HTTP health, retain history and roll back supported deployments |
| Pipelines | Branch polling, build-to-deployment orchestration, manual runs and pause/resume |
| Agents | MCP endpoint with revocable, project-scoped READ / RELEASE / CONFIGURE keys |
| Example | Built-in Java verification pipeline and a separate static UI prototype |

General publication supports executable JARs. Existing Compose applications can use an advanced adapter with specific layout and health-check assumptions. Android signing/publication and several build helpers are currently **Aukni-specific integrations**, rather than a general mobile CI service. See [capabilities and limits](docs/capabilities.md).

Use [repository-owned deployment YAML](docs/deployment-yaml.md) for per-project build, deploy, verify and rollback steps. Attach multiple files for different services/environments; image publication is optional.

## How it fits together

```text
Browser / MCP client
        │ HTTPS
      Caddy ── static React UI
        │
   Kotlin / Spring Boot API ── GitHub OAuth + optional GitHub App
        │                     (exact commit archive)
    PostgreSQL
        │
   worker ── rootless Docker ── disposable build container
        │                       (restricted dependency proxy)
     registry
        │ image digest
   deployer ── pinned SSH connection ── target Docker server
```

API, worker and deployer share the state database. Repository code receives neither the Docker socket nor the database, OAuth, GitHub App or SSH credentials. The trusted worker controls a **separate rootless** Docker daemon; the deployer also holds deployment access. This is a trust boundary for a single-owner installation, not isolation for a public multi-tenant build service.

Only one heavy build runs at a time. Losing a worker lease produces `INTERRUPTED`, rather than automatic success or a blind retry. A successful build confirms an artifact; a successful deployment separately confirms the application check.

## Try it locally

Requirements: Linux, **JDK 21** including `javac`/`jar`, **Node.js 22.12+**, npm, and **PostgreSQL 16**. Docker Compose v2 can provide the local database. OpenSSH client is needed for server management.

```bash
git clone https://github.com/minpor/infra-hub.git
cd infra-hub
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

docker compose up -d --wait postgres
./gradlew :backend:bootJar
npm --prefix frontend ci
```

Adjust `JAVA_HOME` to your JDK installation. Start each command in a separate terminal from the repository root:

```bash
# 1. API
java -jar backend/build/libs/infra-hub.jar
```

```bash
# 2. Worker
java -jar backend/build/libs/infra-hub.jar --spring.profiles.active=worker
```

```bash
# 3. UI
npm --prefix frontend run dev
```

Open [127.0.0.1:5173](http://127.0.0.1:5173), create a project **without a repository**, and run the Java example. It compiles, tests and packages real code; the UI shows its steps and saved logs. Connecting a GitHub repository requires the [repository runner](docs/self-hosting.md#repository-runner-and-deployer).

The local profile uses loopback and has no user login. **Do not expose it to a network.** For a public panel, use the production profile, HTTPS and owner authentication below. Compose reads `.env`; a standalone JVM does not, so custom database settings must also be exported to both Java processes.

Stop application processes with Ctrl+C and the database with `docker compose stop`. Database data stays in its volume. `docker compose down -v` deletes it.

## Install on a server

The [English self-hosting guide](docs/self-hosting.md) and [Russian guide](docs/self-hosting.ru.md) cover:

1. Building release files from source.
2. Configuring your domain, GitHub OAuth app and numeric owner ID.
3. Starting the panel, PostgreSQL and example worker with Docker Compose.
4. Adding a separate rootless runner, dependency proxy, registry and SSH deployer.
5. Connecting a GitHub App for private repositories, publishing the first JAR and deploying it.
6. Backups, updates, recovery, resource limits and troubleshooting.

Use [`deploy/self-host/`](deploy/self-host/), starting with [`.env.example`](deploy/self-host/.env.example). Older `deploy/enable-*.py`, `update-*.py` and `compose.live.yaml` are installation-specific maintenance material, **not a fresh-install procedure**.

The server configuration is source-built: prebuilt public Infra Hub images and a one-command installer are not provided. New Compose configuration should be validated in your environment before production adoption.

## Important limits

- One configured GitHub owner; no organizations, team roles or tenant isolation.
- GitHub source archives, not a Git checkout: no `.git`, Git LFS or submodule contents. Source archive limit: 32 MiB.
- Repository command timeout: 40 minutes; 512 processes; 4 GiB `/work` and 512 MiB `/tmp` tmpfs within the job memory limit. CPU/RAM defaults: 6 CPU / 16 GiB, adjustable in the UI. These are job settings, not minimum panel requirements.
- Build network access goes through an explicit HTTPS dependency allowlist; arbitrary hosts, private registries and extra toolchains require operator changes.
- General image publishing takes one JAR up to 256 MiB; it does not execute repository Dockerfiles. Deployment image transfer is limited to 2 GiB.
- No automatic log/image retention, PR status publishing, release tags, GitHub Actions YAML engine, Kubernetes support or HA installation.
- Rollback does not undo database migrations or external side effects. Interrupted deployments require reconciliation of the same deployment ID.
- Runtime environment values are stored in PostgreSQL and are **not encrypted at rest by the application**. Protect the database, volumes and backups.

## Development and checks

```bash
npm --prefix frontend ci
(cd frontend && npx playwright install chromium)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./scripts/check.sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The main check creates a temporary PostgreSQL database, runs backend tests, builds the frontend, and exercises real API/worker restarts and Playwright flows. It needs PostgreSQL server binaries (`initdb`, `pg_ctl`, `createdb`, `pg_config`), available local ports, and a non-root user. `PG_BIN`, `HUB_TEST_DB_PORT`, and `HUB_TEST_API_PORT` can override local defaults. Use `./scripts/check.sh --backend-only` for JVM checks only.

See [runner resources and cache](docs/runner-settings.md), [local development](docs/local-development.md), [contribution guide](CONTRIBUTING.md), and [security policy](SECURITY.md). Integration scripts that contact an existing server are opt-in and are not part of this local check.

## Repository map

| Path | Contents |
| --- | --- |
| [`backend/`](backend/) | API, queue, GitHub integration, worker, deployer, MCP and Flyway migrations |
| [`frontend/`](frontend/) | React/TypeScript application and Playwright scenarios |
| [`deploy/self-host/`](deploy/self-host/) | Generic server Compose configuration and environment template |
| [`deploy/runner/`](deploy/runner/) | Rootless runner, restricted proxy and registry configuration |
| [`scripts/`](scripts/) | Checks, release preparation and opt-in integration checks |
| [`docs/`](docs/) | Installation, capabilities, architecture and operating guidance |
| [`sample/`](sample/) | Offline HTML prototype with demo data; no real infrastructure actions |
| [`design/`](design/) | Brand assets, design references and mockups |

## Documentation and participation

See [languages, resource measurements and execution time](docs/observability.md). Start with [capabilities](docs/capabilities.md), [self-hosting](docs/self-hosting.md), [operations](docs/operations.md), [MCP](docs/mcp.md) and [roadmap](docs/roadmap.md). Reports and proposals belong in [GitHub Issues](https://github.com/minpor/infra-hub/issues); improvements are welcome as pull requests. Please include reproduction steps and sanitized logs. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

Infra Hub code is available under the [MIT license](LICENSE). Bundled fonts retain their upstream licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
