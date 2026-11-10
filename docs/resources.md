# Capacity planning

Measure your projects rather than treating configuration defaults as hardware requirements. The API has a 768 MiB container limit, example/runner worker 1 GiB, optional deployer 768 MiB; PostgreSQL, Caddy, Docker, proxy/registry and OS also need memory. Job containers live in a separate rootless pool.

Repository jobs initially request 6 CPU / 16 GiB, with default API ceilings of 20 CPU / 18 GiB. Change them before running on a smaller machine. Minimum UI memory setting is 2 GiB, which is not sufficient for every build. Pool limits must leave room for supporting services. `setup-rootless.sh` contains a reference 20 CPU / 20 GiB pool and must be adapted to the host.

Job RAM includes tmpfs, page cache and all compiler/test processes. Gradle heap plus Kotlin daemon, browsers and Android tooling can exceed the JVM heap alone. The 4 GiB work tmpfs also bounds source extraction and outputs. Track cold/repeated build time, queue wait, peak memory, disk growth and image transfer sizes.

One heavy build runs at a time; more worker processes do not create more queue slots. There is no shared dependency cache or automatic artifact/registry retention. Storage sizing must include rollback images, logs and external backups. No cost or performance guarantee is made for arbitrary workloads.
