# Production panel

For a new installation use [self-hosting](self-hosting.md) / [установка](self-hosting.ru.md), not a report from the author's server. The generic stack lives in `deploy/self-host` and uses your own domain, numeric GitHub owner ID and OAuth App.

Production enables HTTPS secure sessions, owner-only OAuth, CSRF checks and protected API access. PostgreSQL remains internal. Check both `/api/health` and unauthenticated `/api/projects` (expected 401), then verify owner login. A Compose process being up alone does not establish application readiness.

`deploy/compose.live.yaml`, the older Caddyfiles and privileged `enable-*`/`update-*` scripts are historical maintenance sources with installation-specific assumptions. They are retained for understanding previous upgrades; they are not the community installer and must not be run unchanged on a new or unrelated server.
