# Architecture

Infra Hub is a modular Kotlin/Spring Boot application with separate JVM roles, a English/Russian React/TypeScript UI, PostgreSQL state and a Docker Compose installation. GitHub remains the source host; a bundled Git service, Coolify or Gitea is not required.

## Processes

- API: owner login, projects, source resolution, queue, servers, pipeline orchestration and MCP.
- Worker profile: claim build jobs, maintain leases, execute local example or disposable rootless repository containers, persist steps and publish JAR images.
- Deployer profile: claim deployment jobs, pull by digest, transfer over pinned SSH, validate imported content and apply the remote protocol.
- PostgreSQL: Flyway schema, jobs, logs, leases, projects, server metadata, applications, pipelines and hashed MCP credentials.
- Caddy: TLS, static UI and public API/MCP routes. Internal source delivery stays off the public proxy.
- Rootless Docker: separate daemon for disposable builds, registry, dependency proxy and trusted image packaging.

## Queue and failure semantics

```text
QUEUED -> RUNNING -> SUCCEEDED / FAILED
   |         |---- CANCEL_REQUESTED -> CANCELLED
   |         '---- lease expires -> INTERRUPTED
   '---- cancellation -> CANCELLED
```

Transactional claims use PostgreSQL advisory locking and row locks. A partial unique index prevents two active heavy builds. Worker identity, active state and lease are checked on writes, preventing a stale worker from overwriting a newer outcome. Build lease duration is 15 seconds. The API sweeps stale jobs; worker also reconciles before claiming work. Terminal jobs are not automatically requeued.

Repository container cleanup is part of success; the next recovery pass removes labeled containers without a valid lease. The local Java example is a development executor, with weaker crash isolation than the container executor.

Deployments have separate leases and remote receipts. After a lost SSH reply, reconcile the same deployment ID to discover the real result. Cancellation after remote switching begins lets the bounded transaction complete and records the actual outcome.

## Security and persistence

Only API handles OAuth/App source credentials; only API/deployer see SSH keys. Repository code gets source bytes and restricted dependency egress. API has no Docker socket. These controls do not establish a public multi-tenant security boundary. Runtime environment values are unencrypted in PostgreSQL; protect DB/volume backups. See [security policy](../SECURITY.md).

See [capabilities](capabilities.md), [self-hosting](self-hosting.md), [deployment protocol](deployments.md) and [MCP](mcp.md).
