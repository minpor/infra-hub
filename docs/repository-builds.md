# Repository builds

Follow the [rootless setup](self-hosting.md#repository-runner-and-deployer). Create a project with `https://github.com/owner/repository` or `git@github.com:owner/repository.git`. Choose a branch, tag or SHA (`HEAD` resolves the default branch), command and optional JAR path. A request UUID prevents duplicate enqueue. The stored SHA/recipe remains fixed even if the branch moves.

Source delivery uses GitHub archives, not an SSH clone. There is no `.git`, LFS or submodule content. Limit: 32 MiB. The command starts at the archive root, e.g. `sh ./gradlew --no-daemon build` or `cd backend && sh ./gradlew build`. Supply custom toolchains through a trusted builder image, never by adding host access to a job.

## Isolation and limits

A separate rootless Docker daemon with cgroup v2 executes jobs as UID/GID 10001. Containers have read-only root filesystems, no capabilities, no-new-privileges, no network interface beyond loopback, no host bind mounts, no Docker socket and no platform credentials. A shared read-only proxy socket permits only HTTPS CONNECT to allowlisted public dependency hosts. Review `deploy/runner/dependency-proxy.py` when dependencies need another host.

CPU/RAM are read from persisted runner settings when the job starts. Defaults: 6 CPU / 16 GiB, ceilings default 20/18. No additional swap, 512 PIDs, 40-minute command timeout, tmpfs `/work` 4 GiB and `/tmp` 512 MiB within job RAM. The trusted entrypoint validates effective cgroup limits before extraction. There is one active heavy build.

Cancellation, timeout or lost lease triggers cleanup; container removal is part of success. Worker recovery removes stale labeled containers before accepting another job. Interrupted jobs are not blindly retried. The trusted worker can control its rootless daemon; this is a single-owner setup, not public tenant isolation.

Dependency downloads are ephemeral; persistent shared caching is not implemented. A blank JAR path runs tests/build only and removes temporary artifacts. A populated JAR path enables [image publication](image-publication.md). Logs and history remain in PostgreSQL with no built-in retention policy.
