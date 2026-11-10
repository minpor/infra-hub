# Operations and recovery

The [installation guide](self-hosting.md#operations) contains commands. Run from `deploy/self-host`, consistently using both Compose files once the runner overlay is enabled. Historical `enable-*` and `update-*` scripts target a prior installation and are not the generic maintenance path.

## Back up before upgrades

Keep a consistent PostgreSQL dump; SSH private-key volume; `.env`; GitHub App key; Compose/Caddy configuration; exact release commit and saved images; registry data; release artifacts; and target deployment receipt/override directories. Protect these as credentials and store an encrypted copy off the hub. Rootless Docker has a separate data directory and UID mapping. Application databases, uploads and remote `.env` files need their own backups.

Test restoration into an isolated replacement host: restore the empty database and volumes with correct ownership, confirm login and source access, run a disposable build/deploy, then switch traffic. Never use a production DB for test cleanup. A backup on the same disk is not disaster recovery. Retention and recovery-time targets are operator decisions; no managed backup service is included.

## Update safely

Pause automatic pipelines, wait for active builds and deployments, and resolve interrupted work. Record old image tags and take backups. Build release files and API/web images first, then worker/deployer; rebuild a compatible builder when its execution protocol changes. Recreate services, inspect Flyway startup and health, confirm owner login and unauthorized API rejection, then exercise a disposable release before unpausing.

Migrations are append-only. Returning to an old container image does not revert a schema migration. Review compatibility before rolling back; use tested data restoration in a maintenance window if necessary. Application deployment rollback likewise does not reverse application database migrations.

## Monitor

Check host memory/CPU/OOM, disk/inodes, PostgreSQL and registry size, Docker layers, artifact growth, queue age, stale leases, failed health checks, TLS, source access and backup freshness. Use an external availability check so a failed hub can still be detected. Logs, images and artifacts have no automatic retention policy; plan cleanup that preserves needed rollback images and receipts.

A lost worker becomes INTERRUPTED, not automatically retried. For deployments, reconcile the original UUID against its remote receipt. Avoid concurrent manual Compose changes that bypass Infra Hub's selected image override. Keep one release authority per application/environment.
