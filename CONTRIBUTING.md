# Contributing to Infra Hub

Thanks for helping make self-hosted builds and deployments easier to operate. English and Russian issues and pull requests are welcome. The project is in early development; discuss changes to execution, deployment protocols or authentication before implementing a large redesign.

## Report an issue

Use [GitHub Issues](https://github.com/minpor/infra-hub/issues). Include the commit, OS/architecture, Java/Node/Docker versions, setup mode (local, base panel or rootless runner), reproduction steps, expected/actual result and sanitized logs. Replace tokens, SSH addresses and private project information. Report vulnerabilities privately under [SECURITY.md](SECURITY.md).

## Work locally

1. Fork and clone the repository; create a focused branch.
2. Follow [local development](docs/local-development.md). Use JDK 21 and Node.js 22.12+; run `npm --prefix frontend ci`.
3. Keep changes limited to the issue. Add meaningful tests for changed queue, lease, auth, source-access or deployment behavior.
4. Run `./scripts/check.sh` and the relevant Python tests. Record anything you could not run. Integration scripts that contact a live server require your own disposable installation and must not be run against someone else's infrastructure.
5. Update both READMEs and both installation guides when changing the public setup. Keep UI translations aligned: Russian source keys and English values are in `frontend/src/en.json`; dates/numbers use `frontend/src/i18n.tsx`. Preserve raw process output instead of translating logs.
6. Open a pull request explaining the problem, resulting behavior, checks and limitations.

Backend integration tests require a dedicated database named `infra_hub_test`; use `scripts/check.sh`, which creates it temporarily. Never point tests at a live database. Do not commit generated builds, `.local`, database dumps, private keys, signing material or populated `.env` files.

## Code and migration conventions

Use existing Kotlin, Spring, React and TypeScript patterns. Kotlin code uses no trailing commas. Keep stored source SHA and image digest immutable. Preserve idempotency, fencing and cancellation semantics. A successful build does not imply a successful deployment.

Flyway SQL migrations are append-only once distributed. Add a new version for schema changes; do not rewrite checksums of existing migrations. Keep API, worker, deployer and builder protocol changes compatible or document the coordinated upgrade.

Dependencies are pinned in Gradle and the npm lockfile. Include the lockfile when changing frontend dependencies. Do not add arbitrary network access, secrets or Docker sockets to repository containers.

## Community expectations

Be respectful, explain technical disagreements and keep feedback about the work. No harassment or publication of private data. Maintainers may remove abusive content or close discussions that prevent constructive collaboration. There is no promised support response time.

## License

By contributing, you agree that your contribution can be distributed under the repository's MIT license. Only submit material you have the right to share. Preserve third-party notices and identify new bundled assets and their licenses.

## Кратко по-русски

Issues и PR можно писать по-русски. Начните с [локального запуска](docs/local-development.md), используйте JDK 21 и Node.js 22.12+, запускайте `scripts/check.sh`. Описывайте проблему, изменение поведения и проверки; обновляйте английскую и русскую документацию. Не публикуйте секреты, дампы и данные серверов. Уязвимости отправляйте приватно по [SECURITY.md](SECURITY.md). Вклад распространяется по MIT; сохраняйте лицензии сторонних материалов.
