# Runner resources

The Runner page stores CPU and memory in PostgreSQL. API: `GET /api/runner-settings`, `POST /api/runner-settings` with `{"cpus":4,"memoryGiB":8}`. Production requires owner session and CSRF.

Initial settings: 6 CPU / 16 GiB. Default ceilings: 20 CPU / 18 GiB via `HUB_RUNNER_MAX_CPUS` / `HUB_RUNNER_MAX_MEMORY_GIB`. These are configuration defaults, not a measurement of the host. Fit them to actual capacity and systemd/rootless pool limits before the first repository job. For changed ceilings, provide matching overrides to API and worker.

Worker snapshots the pair when starting a job, including one already queued. A running container does not resize. Docker gets CPU/RAM, equal memory-swap (no extra swap) and 512 PIDs. The trusted entrypoint verifies real cgroup limits before extracting source. Memory includes JVM/compiler/browser processes, page cache and tmpfs (`/work` up to 4 GiB, `/tmp` 512 MiB). Gradle heap is separate from the container limit and needs headroom.

One heavy job runs at a time. A compatible worker/builder pair is required when changing the entrypoint contract; retain both previous versions for upgrade rollback. Host pool limits must leave capacity for the OS, panel, database, registry and proxy.
