# Applications, deployment and rollback

Use the [self-hosting guide](self-hosting.md#9-connect-a-target-and-deploy) for the general STANDALONE path. Each application binds a project, READY server, ports and health path. The selected build must have succeeded for that project and contain a published image digest.

The deployer pulls by digest, exports image data (up to 2 GiB), transfers over pinned SSH, then checks imported layers/runtime configuration/architecture. The target need not reach the hub's loopback registry. Target image IDs can differ between Docker versions; transferable content is verified before use.

## STANDALONE

The remote Python protocol owns labeled application containers and receipt files under `/opt/infra-hub/applications/<application UUID>/`. It replaces the selected container, checks HTTP health and attempts to restore the previous container on failure. Host port binds to 127.0.0.1; public ingress is separately managed. Brief downtime is possible. Environment values are sent to the target but persisted unencrypted in the hub database; they are hidden from normal read responses.

Locks and atomic receipts prevent concurrent changes to one application. After an interrupted lease/SSH reply, reconcile the **same deployment UUID** to inspect the receipt and actual container/health before continuing. Another deployment cannot bypass unresolved work. Build status and deployment status must be checked separately.

Rollback selects a previous successful native deployment, without rebuilding. It does not revert schema migrations, uploads or external side effects. A first adoption may have no previous native release to select. Cancellation stops preparation before switching; after the remote transaction starts it is allowed to finish and the actual result is recorded, even if cancellation arrived late.

## COMPOSE (advanced)

This adapter adopts an existing compatible stack; it is not general-purpose Compose YAML execution. Configuration keys are `root`, `composeFile`, `service`, `site`, `webService`, `proxy`, `url`, `ref`. Paths/services/URLs are validated and the build ref must match the configured environment. Existing `.env`, data volumes and networks stay on the target. No extra environment map is accepted for this kind.

The adapter expects the release image's `/app/app.jar` and optionally an embedded site/QA bundle, a running app container to preserve runtime properties from, and application-specific HTTP/JSON health behavior. Inspect `backend/src/main/resources/deploy/apply-compose.py` before using it. It writes a persistent image override in the application's receipt directory; future manual Compose commands must include it or can select the old original image. It uses `up --no-deps` to limit replacement scope. Site replacement and proxy handling must match the existing stack.

## ANDROID (project-specific)

The included signing/publication path contains Aukni-specific package/URL/path and artifact constraints. It requires a separately provisioned SDK and signing files accessible only to deployer, not the build container. The generic overlay does not enable it. Treat this as an integration example to generalize, not a portable mobile release service. Lower Android versionCode rollback is rejected. See [managed pipelines](managed-pipelines.md).
