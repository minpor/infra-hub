# Managed pipelines

For repository-owned workflows and multiple files per project, use [deployment YAML](deployment-yaml.md). The sections below also describe legacy native adapters.

A pipeline binds an application, ref, sandbox command and exact JAR path. Create/configure it through the application API/MCP or owner console. Use **Open project** to read **Settings & scripts** first, or choose the **Run** tab for build-only, DEV or PROD, then a GitHub branch and the application/pipeline. Commands stay under **Build settings**; YAML can be edited under **Pipeline configuration**. Advanced native adapter creation still uses API/MCP.

Branch polling runs every 60 seconds; state orchestration every 15 seconds. A new branch SHA can enqueue a build and, for an enabled pipeline, deploy its saved image. An unfinished deployment blocks superseding work. Failures stay in history; interrupted deployment needs reconciliation.

A manual preview on a paused pipeline builds without automatic deployment. Explicit MCP `hub_ensure_release` can request a single release without enabling future automation. Repeated requests reuse compatible existing work; a full expected SHA is checked against GitHub to detect branch drift. Pipeline settings can change next-build CPU/command behavior, so CONFIGURE access is release authority.

## Integration adapters

COMPOSE adoption preserves existing data/runtime configuration under documented assumptions; inspect [deployment semantics](deployments.md). Android polling/signing is currently tailored to Aukni: dev fingerprints `mobile/`, production reads versionName from `mobile/version.properties`, versionCode is derived from the published version endpoint, and artifact names/package/paths are constrained. Signing keys live only with the trusted deployer. Generic Java/Node installations should use STANDALONE until deliberately adapting these mechanisms.

This is native branch-release orchestration, not execution of arbitrary GitHub Actions YAML. PR statuses, release tags and Google Play upload are not provided. Do not leave a second CI/deploy mechanism racing to manage the same environment.

## Environments and manual launches

Each pipeline can have an explicit `environment` of `dev`, `prod` or `null` (unassigned). Set it when attaching YAML or in the application’s pipeline settings. Existing explicit application `configuration.target` values are retained; other pipelines remain unassigned until configured. Branch and application names do not assign environments. This label groups existing targets; changing it does not move an application to a different server or change its runtime settings.

The project launcher lists branches from GitHub, with pagination and a manual branch/tag/SHA fallback. Deployments require a configured pipeline matching both environment and branch. The target application and server are shown before launch. A branch without a matching pipeline can still be built without deployment.

`POST /api/pipelines/{id}/release` accepts `requestId`, plus optional `expectedRef` and `expectedEnvironment`. The project UI sends both selection checks; changed settings return a conflict instead of launching a different target. Both native and YAML pipelines support an explicit one-time release while paused, without enabling future automatic releases. Retries reuse the same request ID.

`POST /api/pipelines/{id}/environment` accepts `{ "environment": "dev" }`, `"prod"`, or `null`. Explicit application targets must agree with the pipeline environment. `GET /api/projects/{id}/branches?page=1` returns the repository default branch, up to 100 branch names and commit SHAs, and `hasMore`.

## Inspect settings and scripts

Opening a project lands on **Settings & scripts**. Select a deployment pipeline to read its configuration and script sources without launching work. Repository YAML opens directly and retains the existing editor. Native pipelines show a clearly labelled YAML representation of the saved Infra Hub settings, including the build command, artifact, runner settings, target server and deployment adapter; it is not presented as an existing repository file. Runtime environment values are excluded.

The file list links build helpers and deployment scripts, plus referenced repository YAML and scripts. Repository reads pin the first selected commit for subsequent file reads in that pipeline view; switching pipeline or refreshing resets the snapshot. Platform sources come from the installed Infra Hub JAR. When deploying build helpers, verify their contents against the actual builder image; the API has no Docker socket or general host-file reader.

Owner-only read endpoints: `GET /api/pipelines/{id}/configuration` and `GET /api/pipelines/{id}/source?origin=REPOSITORY&path=.github/workflows/ci.yml`. `origin=BUILDER` and `origin=DEPLOYER` accept fixed platform script paths only. Repository paths must be relative source files, previews are bounded to 128 KiB, and an optional full `sha` keeps subsequent reads on the same commit.

## Edit and add source files

The project file browser has **Edit file**, **+ New file**, **Open repository file**, and **Copy to project**. Repository edits and new Shell/Python/Node.js/YAML files are validated and committed to the selected pipeline branch, with branch drift and existing-file protection. Shell/Python/Node.js validation checks text/path/size rather than executing code. A bound deployment YAML uses the deployment schema; other YAML uses syntax validation.

The native settings editor changes command/artifact through the existing pipeline guard, rejecting active releases and stale commands. **Use for build** prepares that editor with the selected repository script's invocation; when replacing a copied `/opt/hub/` entrypoint, existing command prefixes are preserved. Creating or copying a file alone does not execute it or replace the shared deployment adapter. Remote YAML steps still run in the target's configured directory; repository scripts need inline commands or explicit file delivery there.

Read endpoints include `/source-base`, `/repository-files` and `/source-access` under `/api/pipelines/{id}`. Owner/CSRF-protected writes are `/source/validate`, `/source/save` and `/build-settings`. File writes require explicit branch snapshots, a `create` flag, and GitHub App Contents write permission (also Workflows write for `.github/workflows`). The UI shows missing permissions before saving. No app permission is changed automatically. See the workflow in the English and Russian READMEs.
