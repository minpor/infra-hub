# Product direction

Infra Hub connects GitHub source, builds on owner-controlled hardware, immutable image publication and deployment to existing Linux servers. The first audience is an individual maintaining several projects and servers. Clear logs, durable state, explicit failure/recovery and understandable deployment outcomes take priority.

The stack is Kotlin/Spring Boot, React/TypeScript, PostgreSQL, separate worker/deployer roles and Docker Compose. Git hosting and registry are integrated components; Gitea/Coolify are not required. Software is MIT-licensed; hardware and third-party service costs remain with the operator.

Current functionality is listed in [capabilities](capabilities.md); remaining work is in [roadmap](roadmap.md). Source availability does not imply that visitors can execute code on an installed instance. Current ownership/auth is single-user; multi-user hosting is future work.
