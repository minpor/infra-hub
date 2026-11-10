# Requirements and scope

The supported starting point is a single-owner Linux installation, GitHub-hosted source, JDK 21/Node.js builds and a Docker application target reached by SSH. Preserve source SHA and image digest, persist queue/log/history, use idempotent mutations and distinguish build success from deployment success.

Every source download and worker write must check current authority/lease. Repository jobs must not inherit control-plane secrets or Docker access. Resource limits must be enforced on actual containers. Lost responses require reconciliation, not blind repetition of a deployment.

Fresh installation must use operator-owned domain/credentials and document backups, update/recovery and limitations. English/Russian setup docs and MIT/community files are part of the public distribution. Cross-platform workers, teams, HA, universal workflow compatibility and generalized Android are outside the current baseline. See [capabilities](capabilities.md), [security policy](../SECURITY.md), [roadmap](roadmap.md).
