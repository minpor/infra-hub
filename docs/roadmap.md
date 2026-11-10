# Roadmap

This is a direction, not a delivery schedule. [Current capabilities](capabilities.md) are documented separately.

## Installation and operations

- Independently exercise a clean server installation and restoration using the community guides.
- Add automated Compose/configuration checks and repeatable packaging/release artifacts.
- Make rootless provisioning portable and resource-aware.
- Implement log/artifact/image retention without losing rollback dependencies.
- Improve observability, external failure notification and backup verification.

## Product and integrations

- Extend YAML workflow editing/discovery and versioned schemas; multiple repository-owned workflow bindings are available.
- Generalize project-specific Compose/Android adapters with explicit compatibility contracts.
- Maintain English/Russian UI and documentation parity; extend diagnostic message translations.
- Consider source provider integrations, PR checks and release metadata based on user demand.
- Evaluate persistent dependency caching while preserving job isolation.

## Security and reliability

- Improve secret-at-rest handling and operator key rotation.
- Expand adversarial executor and interrupted-deployment checks.
- Evaluate team access and stronger execution isolation only with an explicit trust model.

Kubernetes, HA and public multi-tenant code execution are not current guarantees. Open an issue with the operator problem and constraints before proposing a large platform expansion.
