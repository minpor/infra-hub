# Local development

Follow the quick start in [English README](../README.md#try-it-locally) or [русский README](../README.ru.md#локальный-запуск). Use Linux, JDK 21, Node.js 22.12+, PostgreSQL 16 and npm. Local API/UI are loopback-only and have no user authentication. Never expose this profile externally.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `HUB_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:55432/infra_hub` | API/worker database |
| `HUB_DATABASE_USER` | `infra_hub` | Database role |
| `HUB_DATABASE_PASSWORD` | `local-development-only` | Local-only password |
| `HUB_PORT` | `8080` | API port |
| `HUB_WORK_DIRECTORY` | `.local/work` | Temporary job directories |
| `HUB_SSH_DIRECTORY` | `.local/ssh` | Dedicated server keys |
| `HUB_API_TARGET` | `http://127.0.0.1:8080` | Vite proxy target |

Export identical database settings to both Java processes. Java does not automatically read Compose's `.env`. Flyway applies schema migrations at startup. A project without a repository runs the built-in `javac`/test/`jar` example; its temporary JAR is removed, while steps/logs remain in PostgreSQL. Repository projects need the separately configured [rootless runner](self-hosting.md#repository-runner-and-deployer).

## Checks

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
npm --prefix frontend ci
(cd frontend && npx playwright install chromium)
./scripts/check.sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Use a non-root user and PostgreSQL server binaries `initdb`, `pg_ctl`, `createdb`, `pg_config`. `PG_BIN` can select a particular installation. The script creates an isolated database `infra_hub_test` on port 55439, runs JVM integration tests, builds frontend, starts real API/worker processes and runs Playwright. It removes the temporary database on exit. It does not truncate the development database.

Overrides: `HUB_TEST_DB_PORT` (55439), `HUB_TEST_API_PORT` (18081); UI tests use 15173. Direct JVM integration tests require explicit `HUB_TEST_DATABASE_URL`, and the database name must be `infra_hub_test`. Backend only: `./scripts/check.sh --backend-only`.

Reports: `backend/build/reports/tests/test/index.html`; smoke logs/screenshots: `.local/checks/`; browser results: `.local/playwright/`. These generated files are not committed.

## Integration scripts

`scripts/smoke.py` belongs to the isolated main check. `test_dependency_proxy.py` uses synthetic data. In contrast, repository/image/deployment/MCP live smoke scripts require deliberately prepared Docker stacks, credentials and sometimes an existing server/database. They contain historical installation assumptions. Read and adapt them before using your own disposable infrastructure; do not treat them as offline unit tests or run them against an unrelated live installation.
