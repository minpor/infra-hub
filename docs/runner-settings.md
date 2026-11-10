# Runner resources

The Runner page stores CPU and memory in PostgreSQL. API: `GET /api/runner-settings`, `POST /api/runner-settings` with `{"cpus":4,"memoryGiB":8}`. Production requires owner session and CSRF.

Initial settings: 6 CPU / 16 GiB. Default ceilings: 20 CPU / 18 GiB via `HUB_RUNNER_MAX_CPUS` / `HUB_RUNNER_MAX_MEMORY_GIB`. These are configuration defaults, not a measurement of the host. Fit them to actual capacity and systemd/rootless pool limits before the first repository job. For changed ceilings, provide matching overrides to API and worker.

Worker snapshots the pair when starting a job, including one already queued. A running container does not resize. Docker gets CPU/RAM, equal memory-swap (no extra swap) and 512 PIDs. The trusted entrypoint verifies real cgroup limits before extracting source. Memory includes JVM/compiler/browser processes, page cache and tmpfs (`/work` up to 4 GiB, `/tmp` 512 MiB). Gradle heap is separate from the container limit and needs headroom.

One heavy job runs at a time. A compatible worker/builder pair is required when changing the entrypoint contract; retain both previous versions for upgrade rollback. Host pool limits must leave capacity for the OS, panel, database, registry and proxy.

## Per-project settings and dependency cache

In **Settings → Runner**, select **All projects · defaults** or an individual project. Each project can override CPU, memory and the dependency cache switch. **Use global defaults** removes the override, so later global changes apply again. The global capacity ceilings also apply to project overrides. API: `GET/POST /api/projects/{id}/runner-settings`, and `POST /api/projects/{id}/runner-settings/reset` with `{}`. The save body is `{"cpus":12,"memoryGiB":18,"dependencyCacheEnabled":true}`. Projects with no override inherit global settings. Resource choices take effect when the worker starts a build; the actual CPU, RAM and cache state are saved with that build, so later edits cannot change its history.

Dependency caching is enabled by default. A build mounts a named volume `infra-hub-dependencies-v1-<project UUID>` at `/cache` on the configured rootless Docker daemon. Different projects never share that volume; refs and YAML workflows within one project do. Gradle wrapper distributions, Gradle caches and npm download caches persist; Gradle user properties, source trees and working directories do not. The first build downloads dependencies, and later builds let Gradle/npm validate and reuse their cached files. A new dependency/tool version still downloads normally. This is dependency reuse, not a guarantee that a task or every dependency is a cache hit.

Turning caching off omits the volume entirely: the build uses disposable directories. Existing saved files remain for re-enabling. YAML `build.cache: true/false` overrides the project switch for that workflow; omit it to inherit. The build card and source log distinguish disabled, connecting, empty and populated storage and show the volume name. Old builds with no recorded execution information do not invent cache results.

Upgrade the **builder image and worker together**: the builder must include `/cache` owned by UID/GID 10001, and the updated entrypoint. Volumes consume runner disk and have no automatic size limit, eviction or backup. Monitor disk space; remove an individual project's volume only while its builds are stopped. Cache content is writable by repository code from that project, so only reuse it across refs/authors you trust together. No host directory or Docker socket is mounted into builds.
