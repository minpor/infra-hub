# Deployment YAML

[English](deployment-yaml.md) · [Русский](deployment-yaml.ru.md) · [Examples](../examples/deploy/) · [Editor schema](deployment.schema.json)

Each project owns its deployment workflows in Git. Attach any number of `.yaml` / `.yml` files, such as `.infra-hub/dev.yaml`, `.infra-hub/production.yaml` and `.infra-hub/worker.yaml`. Different projects use different files. Multiple bindings may target one application, but only one binding can automatically deploy that application at a time; others remain available for explicit runs.

This is Infra Hub's versioned YAML format. It supports named shell steps and is independent of the older application-specific adapters. It does not interpret GitHub Actions or GitLab CI files.

## Connect a workflow

1. Create a GitHub project and connect a server with a verified SSH fingerprint.
2. In Applications create a **YAML workflow over SSH** application; choose the project and server. An SSH-only target is sufficient. Configure runtime environment values there; do not put secrets in YAML.
3. Commit your YAML to that repository.
4. Click **Attach YAML**, select the application, explicit ref and repository-relative file path. **Validate YAML** reads the file at an exact SHA and shows its content before saving.
5. Save the binding. Automation initially stays disabled. **Run YAML release** explicitly builds and deploys once, including a paused binding. Enable automation separately to poll new commits on the selected ref.

Preview performs no build or deployment. Registration rejects a branch that moved since preview. Each run reads the YAML from its source SHA and stores its content, hash and parsed definition with the build. Deployment snapshots runtime environment values in a private database field. Retries and rollback builds use the saved definition; editing the repository does not rewrite old runs. Generic YAML workflows poll the whole commit SHA; they have no hardcoded project/branch/mobile-path logic.

## Format, version 1

```yaml
version: 1
name: Production service
build:
  steps:
    - name: Tests
      run: sh ./scripts/test.sh
      timeoutSeconds: 600
    - name: Package
      run: sh ./scripts/package.sh
      timeoutSeconds: 600
  # Optional: publish an executable JAR as a digest-pinned Docker image.
  # artifact: build/libs/service.jar

deploy:
  directory: /srv/my-service
  steps:
    - name: Apply release
      run: ./deploy.sh "$HUB_COMMIT_SHA"
      timeoutSeconds: 300
  verify:
    - name: Confirm version and health
      run: ./verify.sh "$HUB_COMMIT_SHA"
      timeoutSeconds: 60
  rollback:
    - name: Restore previous version
      run: ./rollback.sh
      timeoutSeconds: 300
```

`deploy.directory` must already exist on the target. Commands in this example refer to scripts already installed there. The hub does not silently copy a full repository checkout to that directory. A workflow can explicitly fetch/check out the exact commit or use an imported immutable image. Target Git credentials/tools are provisioned by its owner; the hub does not forward its GitHub App token.

- `version`: integer `1`.
- `name`: nonempty display name, up to 80 characters.
- `build.steps`: 1–20 named `run` steps. Each runs in its own `sh -eu` process at the source archive root inside the existing rootless sandbox. Files persist between steps; shell variables and `cd` changes do not. The build still has the runner's CPU/RAM/network/toolchain limits.
- `build.artifact`: optional relative path to one executable JAR. When provided, existing JAR publication produces an image digest and the deployer imports it on the target. When omitted, no platform Docker build/push/pull is required; the successful source build can deploy via shell steps.
- `deploy.directory`: absolute application working directory; root and traversal are rejected.
- `deploy.steps`: 1–20 commands executed on the target as the selected SSH user.
- `deploy.verify`: 1–20 **idempotent, read-only** checks. Exit zero must confirm the intended version and health. Use image IDs/version endpoints, not just an open port. These checks can run again during reconciliation.
- `deploy.rollback`: optional 1–20 recovery steps, run if initial apply/verification fails. They define recovery for your project; the hub cannot infer application/database rollback.
- Every step has `name`, `run` and optional `timeoutSeconds` (default 300, range 1–900). Build timeouts sum to at most 2400 seconds; remote apply + verify + rollback sum to at most 3600 seconds. The builder's overall 40-minute limit remains.

File limit: 64 KiB; compiled build script limit: 32000 characters. Unknown fields, duplicate keys, multiple documents, object tags, anchors/aliases, traversal and invalid types fail validation. There are no YAML includes, implicit interpolation, matrices, parallel jobs or remote plugin downloads in version 1. Shell expansion happens only in the corresponding build or deploy shell. The format can evolve through explicit schema versions.

## Runtime values

The remote process receives the application's saved environment values, plus reserved values supplied by Infra Hub:

| Variable | Meaning |
| --- | --- |
| `HUB_COMMIT_SHA` | Exact source commit |
| `HUB_APPLICATION_ID`, `HUB_BUILD_ID`, `HUB_DEPLOYMENT_ID` | Stable operation IDs |
| `HUB_CONFIG_PATH`, `HUB_CONFIG_SHA256` | YAML file and content hash |
| `HUB_IMAGE_ID`, `HUB_IMAGE_REFERENCE` | Verified target image ID / registry digest, or empty when no artifact was requested |

The `HUB_` values override colliding application variables. SSH/GitHub/signing keys are not injected into scripts. Only application environment values are supplied. Database snapshots/backups contain these values unencrypted, as with existing application environment storage. Script output can reveal anything the author prints; avoid `set -x`, `env` and secret-bearing output. Review access to the repository: its YAML authors control release commands with the selected remote user's authority.

## Failure, retry and cancellation

Remote receipts and per-application locks record the exact request. A successful response means apply and verify completed. If initial failure is followed by successful configured rollback, the deployment is FAILED. Without confirmed recovery it is INTERRUPTED and blocks a new release until reconciled.

Reconcile the same deployment ID. The executor checks for an orphaned active step, then runs verification only; it does not blindly repeat apply/rollback. Bring the target to a verified state if needed and retry reconciliation. A changed input cannot reuse the same receipt. Commands must run foreground work to completion; unmanaged background children/external side effects cannot be made transactional by this protocol.

Cancellation before remote execution can stop preparation. Once user-defined remote effects begin, the bounded workflow finishes and reports its actual outcome; cancellation is not a promise to reverse arbitrary side effects. Rollback of a prior successful SCRIPT release reruns that earlier immutable recipe against its old SHA/image. Make scripts capable of intentionally releasing an earlier version. Android store/versionCode rules still belong to your workflow; legacy Android rollback remains disabled.

## API and compatibility

- `POST /api/deployment-yaml/preview`: `{applicationId, ref, path}`.
- `POST /api/deployment-yaml`: `{id, applicationId, ref, path, expectedSha, enabled}`.
- `POST /api/pipelines/{id}/release`: `{requestId}` for an explicit YAML release.
- Existing `/run` keeps its preview semantics for paused pipelines. `/enabled` controls automation. MCP `hub_ensure_release` also resolves the bound YAML at the requested exact SHA.

Owner session and CSRF protect browser writes. The application binds the server and project; YAML cannot select a different SSH connection. A YAML build cannot be deployed to another application. Different files have independent pipeline histories; heavy builds and remote deployments still follow the installation's concurrency limits.

V10 adds YAML bindings and immutable snapshots. Existing Aukni/Java/Compose adapters and releases remain compatible. Attaching a new file does not disable a current pipeline: pause the previous automatic owner before enabling a replacement. General YAML commands can deploy Compose (including multiple `-f` files), systemd, static sites or project scripts without adding project names/paths to platform code. Extra build tools and target dependencies remain operator-provisioned.

Examples: [Java image](../examples/deploy/java-image.yaml), [Compose production](../examples/deploy/compose-production.yaml), [systemd staging](../examples/deploy/systemd-staging.yaml). Adapt them to your application; they are not runnable releases for the author's infrastructure.
