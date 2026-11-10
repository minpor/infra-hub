# Technical decisions

- MIT license; English and Russian public README/setup documentation.
- Kotlin/Spring Boot modular application with separate worker and deployer process roles.
- PostgreSQL for durable state, queue coordination, leases, history and Flyway migrations.
- React/TypeScript UI; current product text is Russian.
- GitHub OAuth owner authentication, distinct from read-only GitHub App source authorization.
- GitHub exact-commit archives rather than repository checkout; limitations are explicit.
- Separate rootless Docker and effective cgroup checks for repository code; no platform credentials in jobs.
- Platform-owned JAR packaging and immutable image digests; SSH transfer avoids exposing the private registry to targets.
- Remote receipts and explicit reconciliation when deployment completion is uncertain.
- Branch polling and native pipelines; no claim to execute arbitrary GitHub Actions workflows.
- Generic installation configs live in `deploy/self-host`; older deployment scripts are installation-specific references.

Open questions include independent fresh-install acceptance, portable provisioning, generic mobile/Compose adapters, retention, stronger secret-at-rest handling, UI localization and future team access. See [roadmap](roadmap.md).
