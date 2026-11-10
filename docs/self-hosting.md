# Self-hosting Infra Hub

[English](self-hosting.md) · [Русский](self-hosting.ru.md) · [README](../README.md)

This guide uses the generic configuration in `deploy/self-host/`. The base installation provides the owner-authenticated panel and Java example worker. Repository builds, publishing and deployment are a second, explicit setup step. The configuration is an initial community installation path; a fresh external server installation has not yet been independently accepted. Test on a disposable host before using production workloads.

## 1. Prepare the host

Use a Linux host with Docker Engine, Docker Compose v2, DNS pointing to it, and inbound TCP 80/443 for Caddy. Outbound HTTPS is required for GitHub, image registries and dependencies. Do not publish PostgreSQL or the runner Docker API. If another proxy already owns 80/443, adapt the ingress rather than starting two proxies on those ports.

Build requirements: JDK 21, Node.js 22.12+, npm and Git. Server management also needs OpenSSH tools; the supplied API image includes them. Repository execution additionally requires systemd, cgroup v2 and rootless Docker with effective CPU/memory/PID delegation. Windows/macOS workers are not supported.

No hardware minimum has been established for every project. The panel containers have their own memory limits; leave additional memory for PostgreSQL, the OS, Docker and build tooling. The initial repository job asks for **6 CPU / 16 GiB**. Before the first repository build, change the Runner page to fit your host. Raising a UI limit does not add physical capacity. Production application servers can be separate machines.

## 2. Build release files

As a normal user, from a checkout:

```bash
git clone https://github.com/minpor/infra-hub.git
cd infra-hub
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/prepare-release.sh
```

Use the actual JDK path. The script runs Gradle `bootJar`, `npm ci`, TypeScript checks and Vite, then stages `deploy/artifacts/app.jar` and `deploy/artifacts/dist/`. It does not run the full test suite or deploy anything. Run `./scripts/check.sh` separately; see [local development](local-development.md).

You can build on another Linux machine and transfer the staged files together with the `deploy/` configuration. Both container images use `deploy/artifacts` as their small build context. There are no prebuilt public Infra Hub release images.

## 3. Configure GitHub owner authentication

Create a GitHub **OAuth App** for login:

- Homepage: `https://hub.example.com` (your actual domain).
- Authorization callback: `https://hub.example.com/login/oauth2/code/github`.
- Keep its client secret on the API host only.

Set your account's **numeric GitHub user ID**, not your username. For example, `gh api users/YOUR_LOGIN --jq .id` returns it. Only this ID is allowed to log in. OAuth login does not grant access to private repository source; that uses a separate GitHub App in step 5.

```bash
cd deploy/self-host
cp .env.example .env
chmod 600 .env
```

Edit `.env`: set `HUB_DOMAIN` (hostname only), a strong `HUB_DATABASE_PASSWORD`, OAuth client ID/secret and `HUB_OWNER_GITHUB_ID`. Generate random passwords with `openssl rand -hex 32`. Replace all `replace-with-...` placeholders; the template values are not usable credentials. Do not commit `.env`.

## 4. Start the base panel

From `deploy/self-host`:

```bash
docker compose config --quiet
docker compose build api caddy
docker compose up -d --wait
```

The stack starts PostgreSQL, production API, example worker and Caddy. Only Caddy publishes host ports. Caddy obtains TLS certificates for `HUB_DOMAIN`; DNS and public reachability must be correct. Flyway applies database migrations at application startup.

Check explicitly; `up --wait` alone is not an application readiness test:

```bash
curl --fail https://hub.example.com/api/health
curl -s -o /dev/null -w '%{http_code}\n' https://hub.example.com/api/projects
```

The health endpoint should return `UP` and production mode. Projects should return `401` without a session. Open the panel, log in as the configured owner, create a project without a repository, and run the Java example. A successful example does not verify rootless repository builds or deployment.

If login fails, compare the exact callback scheme/hostname/path, numeric owner ID and OAuth secret. Secure cookies require HTTPS. A restart clears server-side login sessions. Logs: `docker compose logs --tail=100 api worker caddy`.

## Repository runner and deployer

### 5. Configure source access

Public repositories can be resolved without App credentials, subject to GitHub API limits. For private repositories, create and install a **GitHub App** with Repository **Contents: read-only** and Metadata read-only. Select only repositories you intend to build. Webhooks are not required; the current pipeline uses polling. Do not grant write/admin permissions for this path.

Set `HUB_GITHUB_APP_ID` and `HUB_GITHUB_INSTALLATION_ID` in `.env`. Store a PKCS8 private key as `deploy/self-host/github-app/private-key.pem`:

```bash
mkdir -p github-app
chmod 700 github-app
openssl pkcs8 -topk8 -nocrypt -in /secure/location/downloaded.pem -out github-app/private-key.pem
sudo chown -R 10001:10001 github-app
sudo chmod 600 github-app/private-key.pem
```

Run the conversion before changing directory ownership. Never put the original or converted key into Git. The API runs as UID 10001 and must be able to read the key. An installation token is scoped to one repository; worker receives source bytes through a lease-protected internal endpoint, not the App key or token. Revoking GitHub access blocks future downloads but cannot recall source already delivered to a running job.

Generate `HUB_WORKER_SECRET` using `openssl rand -hex 32`, and set the same value for API/worker through `.env`. It must be at least 32 characters. `/internal/*` must stay unavailable at public ingress.

### 6. Prepare a separate rootless daemon

Do not mount the panel host's rootful Docker socket into the repository executor. Provision a dedicated account and rootless Docker, using your distribution's supported procedure. `deploy/runner/setup-rootless.sh` is a **privileged Debian/Ubuntu-oriented reference**, assumes Docker's package repository is already configured, creates `infra-build`, enables lingering and configures a **20 CPU / 20 GiB** user-service pool. Read and adapt those limits before running it; it is not a portable installer.

Verify the rootless daemon reports `rootless`, cgroup version `2`, and effective limits. Record its actual socket UID and GID in `.env` as `HUB_RUNNER_UID` and `HUB_RUNNER_SOCKET_GID`. Do not assume they are 1001. The socket is `/run/user/<UID>/docker.sock`; worker/deployer receive access only to this daemon. Keep rootless Docker and proxy/registry running after logout.

From the repository root, set `RUNNER_SOCKET` to that daemon and build the general Java/Node builder, dependency proxy and registry:

```bash
export RUNNER_SOCKET=unix:///run/user/1001/docker.sock  # replace 1001

docker --host "$RUNNER_SOCKET" build -t infra-hub-builder:java21 \
  -f deploy/runner/Dockerfile.generic deploy/runner

docker --host "$RUNNER_SOCKET" compose -f deploy/runner/compose.proxy.yaml up -d --build
docker --host "$RUNNER_SOCKET" compose -f deploy/runner/compose.registry.yaml up -d
```

The commands must be run by a user authorized to access this socket. The proxy creates `infra-hub-dependency-proxy`, a named volume shared with jobs. Confirm its logs show no startup errors and its socket exists before building a repository. A missing proxy socket means dependency downloads fail.

The builder has Java 21, Node.js 22 and Python 3. Browser tests, native libraries and Android need extra tools in a custom builder. The older `Dockerfile` in this directory expects a locally supplied `android-sdk/` and contains project-specific helpers; it is not used by this generic path.

Set runner CPU/RAM through the panel before the first job. Default API ceilings are 20 CPU / 18 GiB (`HUB_RUNNER_MAX_CPUS`, `HUB_RUNNER_MAX_MEMORY_GIB`); changing these requires a matching environment override on API and worker and matching host capacity. The builder validates actual cgroup limits before extracting source and fails closed if they are ineffective.

### 7. Configure image publication

Set `HUB_IMAGE_NAMESPACE=localhost:5000/infra-hub` for the loopback registry above. The registry must remain bound to loopback. Worker and deployer use the same rootless daemon, so remote application hosts do not need registry credentials.

Choose a Java 21 runtime compatible with your JAR and pin its digest. Example for resolving an image on the same rootless daemon:

```bash
docker --host "$RUNNER_SOCKET" pull eclipse-temurin:21-jre-jammy
docker --host "$RUNNER_SOCKET" image inspect --format '{{index .RepoDigests 0}}' eclipse-temurin:21-jre-jammy
```

Put the full returned `eclipse-temurin@sha256:...` value in `HUB_RUNTIME_IMAGE`. Use images matching the target CPU architecture; cross-compilation is not configured. With empty publication settings, test-only repository builds can run, but builds requesting JAR publication fail.

An external registry needs its own authentication and TLS configuration. Worker supports `HUB_REGISTRY_CONFIG_DIRECTORY`; mount the Docker config read-only and pass that property explicitly. The generic overlay only configures the local registry; authenticated pull by deployer would need separate configuration/code review.

### 8. Start repository execution

From `deploy/self-host`, after the base API image exists:

```bash
docker compose -f compose.yaml -f compose.runner.yaml config --quiet
docker compose -f compose.yaml -f compose.runner.yaml build worker deployer
docker compose -f compose.yaml -f compose.runner.yaml up -d --wait
```

Always include **both files** for subsequent runner-installation operations. Base `api` and `caddy` builds precede worker/deployer builds because the latter use `infra-hub-api:local` as their base. The overlay adds source access, replaces the example-only worker image and starts a deployer. API has no Docker socket; repository jobs have no SSH keys. The deployer intentionally has the rootless socket and read-only SSH access.

Create a GitHub project and run a repository command such as `sh ./gradlew --no-daemon build`. Commands run at the archive root; `cd backend && ...` is useful for subprojects. To publish, supply the exact executable JAR path, such as `backend/build/libs/app.jar`. No glob, absolute path or `..` is accepted. The source archive lacks `.git`, Git LFS and submodule content. A successful publish records the immutable digest; it has not yet deployed the application.

### 9. Connect a target and deploy

Use a disposable Linux target first. It needs OpenSSH, an ED25519 host key, Python 3, Docker and Compose, plus a user able to manage containers and write `/opt/infra-hub/applications`. Docker access is highly privileged; choose the account deliberately.

In Servers, enter the reachable hostname, port and user. Install the generated public key through an existing trusted SSH session. Compare the scanned fingerprint with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` on the target before confirming it. Run the connection check and require `READY`. Password login, ProxyJump and IPv6 literals are not supported by this path.

Create a **STANDALONE** application, choose the project/READY server, host port (1024–65535), container port and health path. It should return a successful HTTP response without login. Supply runtime environment values if needed. They are hidden in API responses but stored unencrypted in PostgreSQL; protect its backups.

Choose a successful published build and deploy. Deployer pulls by digest, transfers up to 2 GiB of image data over SSH, verifies imported content and executes the target protocol. Standalone ports bind to target loopback; use a separately managed reverse proxy for external access. A failed health check attempts to restore the previous container. Brief downtime during replacement is possible; database migration rollback is your responsibility.

After an interrupted SSH exchange, use **reconcile/retry on the same deployment UUID**; do not blindly create another deployment. Inspect deployment status, logs and the application's live state. Previous successful native deployments can be selected for rollback. See [deployment semantics](deployments.md).

Advanced COMPOSE adoption assumes an existing compatible stack and is documented separately. Android publication is project-specific and not enabled by this overlay. Do not use the author's old `enable-*`/`update-*` scripts as a new installation procedure.

## Operations

For runner installations, the following commands run from `deploy/self-host` and include both files. Base-only installations omit `-f compose.runner.yaml`.

```bash
docker compose -f compose.yaml -f compose.runner.yaml ps
docker compose -f compose.yaml -f compose.runner.yaml logs --tail=100 api worker deployer
```

- Stop safely: pause pipelines, wait for active work to finish or cancel it, then `docker compose ... stop`. Do not delete volumes during routine shutdown.
- Back up: PostgreSQL, SSH volume, `.env`, GitHub App key, registry data, artifacts and target receipt/configuration directories. Keep encrypted copies off this host. A hub backup does not include application databases or uploads on other servers.
- Upgrade: record the current commit and tag old API/web/worker/deployer/builder images; pause automatic pipelines and drain work; take a database dump and volume/configuration backups; update the checkout; run `scripts/prepare-release.sh`; build API/web first, then worker/deployer, and recreate services. Migrations apply on startup; do not edit previously applied migration SQL.
- Verify: health, owner login, unauthorized API rejection, database migrations, example build and a disposable deployment. Unpause pipelines only after verification.
- Roll back: use compatible saved image versions and configuration. Returning to older application images does not reverse a schema migration; if schema rollback is necessary, restore a tested backup during a maintenance window.

An example database dump (protect the output file) is:

```bash
umask 077
docker compose -f compose.yaml -f compose.runner.yaml exec -T postgres \
  pg_dump -U infra_hub -d infra_hub -Fc > infra-hub.dump
```

Restore only into an isolated, empty replacement database, with API/worker/deployer stopped; use `pg_restore -U infra_hub -d infra_hub --no-owner` through `docker compose exec -T postgres` and verify before switching traffic. Copying a running PostgreSQL data directory is not a substitute for a consistent dump. Named volumes need their own backup procedure and ownership preservation (application UID 10001; rootless Docker has its own user mapping).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| TLS fails | DNS A/AAAA records, public 80/443, another listener, Caddy logs |
| OAuth loop / denied login | Exact callback URL, HTTPS cookies, client secret, numeric owner ID |
| Job stays QUEUED | Worker logs, database connectivity, whether another heavy job holds the slot |
| Repository runner not configured | Both Compose files, worker secret, rootless socket access, builder image |
| Resource validation fails | cgroup v2, systemd delegation, actual CPU/RAM limits and configured job resources |
| Dependencies fail | Proxy container/socket/volume, allowlisted hostname, outbound DNS/HTTPS, required toolchain |
| Private source denied | App installation ID, repository selection, key format/permissions and Contents read access |
| JAR/image publish fails | Exact path, 256 MiB bound, executable JAR, runtime digest, registry availability |
| SSH host key changed | Independently verify the target; never automatically accept a replacement |
| Deployment INTERRUPTED | Reconcile the same ID, inspect remote receipt and real application health |
| Disk fills | PostgreSQL logs/history, registry, Docker layers, artifacts; no automatic retention policy exists |

See [operations](operations.md), [security policy](../SECURITY.md), and [capabilities](capabilities.md). Test restoration and isolation on your own host; configuration validation and local tests alone do not establish production readiness.
