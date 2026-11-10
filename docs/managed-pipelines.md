# Managed pipelines

For repository-owned workflows and multiple files per project, use [deployment YAML](deployment-yaml.md). The sections below also describe legacy native adapters.

A pipeline binds an application, ref, sandbox command and exact JAR path. Create/configure it through the application API/MCP or owner console. The existing UI offers run/pause controls; not every advanced creation option has a wizard.

Branch polling runs every 60 seconds; state orchestration every 15 seconds. A new branch SHA can enqueue a build and, for an enabled pipeline, deploy its saved image. An unfinished deployment blocks superseding work. Failures stay in history; interrupted deployment needs reconciliation.

A manual preview on a paused pipeline builds without automatic deployment. Explicit MCP `hub_ensure_release` can request a single release without enabling future automation. Repeated requests reuse compatible existing work; a full expected SHA is checked against GitHub to detect branch drift. Pipeline settings can change next-build CPU/command behavior, so CONFIGURE access is release authority.

## Integration adapters

COMPOSE adoption preserves existing data/runtime configuration under documented assumptions; inspect [deployment semantics](deployments.md). Android polling/signing is currently tailored to Aukni: dev fingerprints `mobile/`, production reads versionName from `mobile/version.properties`, versionCode is derived from the published version endpoint, and artifact names/package/paths are constrained. Signing keys live only with the trusted deployer. Generic Java/Node installations should use STANDALONE until deliberately adapting these mechanisms.

This is native branch-release orchestration, not execution of arbitrary GitHub Actions YAML. PR statuses, release tags and Google Play upload are not provided. Do not leave a second CI/deploy mechanism racing to manage the same environment.
