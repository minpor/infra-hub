# Security policy

Infra Hub is in early development. Fixes target the current main branch; no long-term security support window or response-time guarantee is provided.

## Reporting a vulnerability

Do not open a public issue containing an exploit against a real installation, credentials or private infrastructure details. Use GitHub's **Report a vulnerability** on the repository Security tab if available. If private reporting is unavailable, open a public issue asking the maintainer to establish a private channel, without technical exploit details or sensitive data.

Include the affected commit, deployment mode, reproduction using synthetic data, impact and any suggested mitigation. Do not test against servers you do not own. Never send live keys or tokens.

## Installation trust model

- A production installation has one explicitly configured numeric GitHub owner ID. The local profile is loopback-only and has no user authentication; never expose it externally.
- API, database, worker, deployer and rootless Docker administrator are trusted. Repository containers must not receive their credentials or Docker sockets. Containers and rootless Docker are not a guarantee of isolation for arbitrary hostile tenants.
- The GitHub OAuth app authenticates the owner. A separate read-only GitHub App grants source access. Store its private key outside Git and back it up securely.
- Pin and independently verify SSH host keys. Dedicated deploy keys inherit the chosen remote user's authority; Docker access is highly privileged.
- Application environment values are stored in the database without application-level encryption. SSH keys are files in a protected volume. Protect database access, volumes, host disks and backups accordingly.
- Build logs and artifacts can contain anything a repository command prints. Review them before sharing. There is no universal secret-redaction guarantee for arbitrary commands.
- The dependency proxy restricts destinations, not the behavior of code fetched from an allowed host. Use trusted source repositories and review dependencies.
- Revoke MCP keys when no longer needed. Give agents only the projects and permissions needed; CONFIGURE can enable automatic release.

See [self-hosting](docs/self-hosting.md) for network exposure, permissions, update and recovery guidance.

## По-русски

Уязвимости сообщайте через приватный GitHub Report a vulnerability, если он доступен. Иначе создайте issue с просьбой о приватном канале без деталей эксплуатации. Не прикладывайте действующие ключи и данные чужих серверов. Локальный профиль нельзя публиковать в сеть; production рассчитан на одного владельца. БД, SSH volume и backups требуют защиты, runtime environment не шифруется приложением. Полная изоляция произвольного чужого кода не заявлена.
