# Самостоятельное развёртывание Infra Hub

[English](self-hosting.md) · [Русский](self-hosting.ru.md) · [README](../README.ru.md)

Инструкция использует общую конфигурацию `deploy/self-host/`. Базовая установка запускает панель с авторизацией владельца и worker Java-примера. Сборки репозиториев, публикация и деплой подключаются отдельно. Это первый путь установки для сообщества: независимая приёмка на чистом внешнем сервере ещё не проведена. Начните с временного стенда.

## 1. Подготовьте машину

Нужны Linux, Docker Engine, Docker Compose v2, домен с DNS на этот сервер и входящие TCP 80/443 для Caddy. Исходящий HTTPS нужен для GitHub, образов и зависимостей. PostgreSQL и Docker API не публикуются. Если 80/443 уже заняты вашим proxy, адаптируйте ingress.

Для сборки панели нужны Git, JDK 21 и Node.js 22.12+ с npm. API-образ уже содержит OpenSSH. Для репозиториев дополнительно нужны systemd, cgroup v2 и rootless Docker с действующими ограничениями CPU/RAM/PID. Workers Windows/macOS не поддерживаются.

Универсальные минимальные требования к железу не установлены. Оставляйте память для PostgreSQL, ОС, Docker и инструментов сборки сверх лимитов контейнеров панели. Начальный job запрашивает **6 CPU / 16 GiB**: до первой сборки настройте раздел «Раннер» под своё оборудование. Изменение цифры в интерфейсе не увеличивает мощность машины. Приложения могут работать на отдельных серверах.

## 2. Соберите файлы релиза

От обычного пользователя:

```bash
git clone https://github.com/minpor/infra-hub.git
cd infra-hub
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/prepare-release.sh
```

Укажите фактический путь к JDK. Скрипт выполняет Gradle `bootJar`, `npm ci`, проверку TypeScript и Vite, затем готовит `deploy/artifacts/app.jar` и `deploy/artifacts/dist/`. Полный набор тестов и деплой не запускает. Проверки выполняются отдельно: `./scripts/check.sh`, см. [локальную разработку](local-development.md).

Можно собрать на другой Linux-машине и передать подготовленные файлы вместе с конфигурацией `deploy/`. Контекст сборки API и web-образов — небольшой каталог `deploy/artifacts`. Готовые публичные релизные образы Infra Hub пока не предоставляются.

## 3. Настройте вход владельца

Создайте GitHub **OAuth App**:

- Homepage: `https://hub.example.com` — ваш домен.
- Authorization callback: `https://hub.example.com/login/oauth2/code/github`.
- Client secret остаётся только на API-сервере.

Укажите **числовой ID пользователя GitHub**, не логин. Получить его можно командой `gh api users/YOUR_LOGIN --jq .id`. Войти сможет только этот ID. Вход OAuth не даёт доступа к приватным исходникам: для них используется отдельный GitHub App на шаге 5.

```bash
cd deploy/self-host
cp .env.example .env
chmod 600 .env
```

Заполните `.env`: `HUB_DOMAIN` без схемы и пути, случайный `HUB_DATABASE_PASSWORD`, OAuth client ID/secret и `HUB_OWNER_GITHUB_ID`. Случайное значение: `openssl rand -hex 32`. Замените все `replace-with-...`; шаблонные строки не являются рабочими credentials. `.env` нельзя добавлять в Git.

## 4. Запустите базовую панель

Из `deploy/self-host`:

```bash
docker compose config --quiet
docker compose build api caddy
docker compose up -d --wait
```

Запускаются PostgreSQL, production API, worker примера и Caddy. Порты хоста публикует только Caddy. Для получения TLS нужны корректные DNS и доступность домена. Flyway применяет миграции при старте приложения.

`up --wait` недостаточно для проверки готовности приложения. Выполните:

```bash
curl --fail https://hub.example.com/api/health
curl -s -o /dev/null -w '%{http_code}\n' https://hub.example.com/api/projects
```

Health должен вернуть `UP` и production-режим. Projects без сессии — `401`. Откройте панель, войдите владельцем, создайте проект без репозитория и выполните Java-пример. Его успех не проверяет rootless-сборки и деплой.

При ошибке входа сверьте схему/домен/путь callback, числовой owner ID и secret. Secure cookies требуют HTTPS. При перезапуске серверные сессии теряются. Журналы: `docker compose logs --tail=100 api worker caddy`.

## Runner репозиториев и deployer

### 5. Настройте доступ к исходникам

Публичные репозитории доступны без GitHub App с учётом API rate limits. Для приватных создайте и установите **GitHub App** с Repository **Contents: read-only** и Metadata read-only. Выберите только необходимые репозитории. Webhooks не нужны: pipeline использует polling. Права записи/администратора для этого пути не требуются.

Заполните `HUB_GITHUB_APP_ID` и `HUB_GITHUB_INSTALLATION_ID`. PKCS8-ключ хранится в `deploy/self-host/github-app/private-key.pem`:

```bash
mkdir -p github-app
chmod 700 github-app
openssl pkcs8 -topk8 -nocrypt -in /secure/location/downloaded.pem -out github-app/private-key.pem
sudo chown -R 10001:10001 github-app
sudo chmod 600 github-app/private-key.pem
```

Преобразование выполняйте до смены владельца каталога. Ни исходный, ни преобразованный ключ не добавляются в Git. API работает под UID 10001 и должен читать файл. Installation token ограничен одним репозиторием; worker получает архив через внутренний endpoint с проверкой lease. Ключ App и токен ему не передаются. Отзыв доступа блокирует новые загрузки, но не может отозвать архив уже работающего job.

Создайте `HUB_WORKER_SECRET` командой `openssl rand -hex 32`; `.env` передаст одинаковое значение API/worker. Минимальная длина — 32 символа. `/internal/*` не должен быть доступен через публичный proxy.

### 6. Подготовьте отдельный rootless Docker

Rootful Docker socket панели нельзя передавать исполнителю репозиториев. Создайте отдельную учётную запись и rootless Docker по процедуре вашей ОС. `deploy/runner/setup-rootless.sh` — **привилегированный пример для Debian/Ubuntu**: предполагает настроенный Docker APT repository, создаёт `infra-build`, включает lingering и задаёт пул службы **20 CPU / 20 GiB**. Прочитайте и адаптируйте его до запуска; это не универсальный установщик.

Проверьте наличие `rootless`, cgroup версии `2` и реальное действие лимитов. Укажите в `.env` фактические `HUB_RUNNER_UID` и `HUB_RUNNER_SOCKET_GID`; они не обязательно равны 1001. Socket: `/run/user/<UID>/docker.sock`. Worker/deployer управляют только этим daemon. Rootless Docker, registry и proxy должны работать после выхода пользователя.

Из корня репозитория:

```bash
export RUNNER_SOCKET=unix:///run/user/1001/docker.sock  # замените UID

docker --host "$RUNNER_SOCKET" build -t infra-hub-builder:java21 \
  -f deploy/runner/Dockerfile.generic deploy/runner

docker --host "$RUNNER_SOCKET" compose -f deploy/runner/compose.proxy.yaml up -d --build
docker --host "$RUNNER_SOCKET" compose -f deploy/runner/compose.registry.yaml up -d
```

Пользователь должен иметь доступ к socket. Proxy создаёт общий с jobs named volume `infra-hub-dependency-proxy`. До первой сборки проверьте его журнал и наличие socket внутри volume. Без него зависимости не загрузятся.

Общий builder содержит Java 21, Node.js 22 и Python 3. Для браузерных тестов, нативных библиотек и Android нужен собственный расширенный image. Старый `deploy/runner/Dockerfile` требует локальный `android-sdk/` и содержит проектные helpers; общий путь его не использует.

Настройте CPU/RAM в панели до первого job. Верхние пределы API по умолчанию — 20 CPU / 18 GiB (`HUB_RUNNER_MAX_CPUS`, `HUB_RUNNER_MAX_MEMORY_GIB`). Их изменение требует согласованных environment override API/worker и ёмкости хостового пула. Builder сверяет фактические cgroup-лимиты до распаковки исходников и отказывается работать при несоответствии.

### 7. Настройте публикацию образов

Для локального registry задайте `HUB_IMAGE_NAMESPACE=localhost:5000/infra-hub`. Registry должен слушать только loopback. Worker и deployer используют один rootless daemon; целевым серверам не нужны registry credentials.

Выберите совместимый с JAR Java 21 runtime и закрепите digest:

```bash
docker --host "$RUNNER_SOCKET" pull eclipse-temurin:21-jre-jammy
docker --host "$RUNNER_SOCKET" image inspect --format '{{index .RepoDigests 0}}' eclipse-temurin:21-jre-jammy
```

Полный результат `eclipse-temurin@sha256:...` запишите в `HUB_RUNTIME_IMAGE`. Образы должны соответствовать CPU-архитектуре целевого сервера; кросс-сборка не настроена. При пустых настройках публикации можно выполнять test-only jobs, но job с JAR завершится ошибкой конфигурации.

Внешний registry требует отдельной настройки TLS и доступа. Worker поддерживает `HUB_REGISTRY_CONFIG_DIRECTORY`: каталог Docker config нужно явно смонтировать read-only и указать property. Общий overlay настраивает локальный registry; авторизованный pull со стороны deployer потребует отдельного разбора конфигурации/кода.

### 8. Включите исполнение репозиториев

Из `deploy/self-host`, когда базовый API image уже собран:

```bash
docker compose -f compose.yaml -f compose.runner.yaml config --quiet
docker compose -f compose.yaml -f compose.runner.yaml build worker deployer
docker compose -f compose.yaml -f compose.runner.yaml up -d --wait
```

Дальнейшие команды для этой установки выполняйте с **обоими файлами**. API/web собираются первыми: worker/deployer используют `infra-hub-api:local` как базовый image. Overlay включает доступ к исходникам, заменяет image worker и запускает deployer. API не получает Docker socket; код репозитория — SSH-ключи. У deployer намеренно есть rootless socket и read-only доступ к ключам SSH.

Создайте проект GitHub и запустите, например, `sh ./gradlew --no-daemon build`. Рабочая директория — корень архива; для подпроекта используйте `cd backend && ...`. Для публикации заполните точный путь JAR, например `backend/build/libs/app.jar`. Маски, абсолютные пути и `..` запрещены. `.git`, LFS и содержимого submodules в архиве нет. После публикации сохраняется digest; приложение ещё не развёрнуто.

### 9. Подключите сервер и выполните деплой

Начните с временного Linux-сервера. Нужны OpenSSH, ED25519 host key, Python 3, Docker/Compose и пользователь с правами управления контейнерами и записи `/opt/infra-hub/applications`. Доступ к Docker даёт широкие привилегии — выбирайте учётную запись осознанно.

В «Серверах» укажите доступный hostname, порт и пользователя. Добавьте сгенерированный публичный ключ через существующее доверенное SSH-соединение. До подтверждения сравните отпечаток с `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` на сервере. Проверка подключения должна дать `READY`. Парольный вход, ProxyJump и IPv6-литералы в этом пути не поддерживаются.

Создайте приложение **STANDALONE**: проект, READY-сервер, host port 1024–65535, container port и путь health check. Он должен возвращать успешный HTTP-ответ без авторизации. При необходимости задайте runtime environment: значения скрыты в ответах API, но сохраняются в PostgreSQL без шифрования на уровне приложения.

Выберите успешную сборку с опубликованным образом и запустите деплой. Deployer делает pull по digest, передаёт до 2 GiB по SSH, сверяет содержимое и выполняет целевой протокол. Standalone-порт слушает loopback целевого сервера; внешний reverse proxy настраивается отдельно. При неуспешном health check выполняется попытка восстановления прежнего контейнера. Во время замены возможен перерыв. Откат миграций БД организует владелец.

После обрыва SSH выполняйте **сверку/повтор того же UUID**, проверяя журнал, remote receipt и фактическое здоровье приложения. Не создавайте новый деплой вслепую. Ручной откат использует предыдущие успешные native deployments. Подробнее — [семантика деплоя](deployments.md).

COMPOSE — расширенный сценарий принятия совместимого существующего стека. Android-публикация привязана к проекту и этим overlay не включается. Старые `enable-*`/`update-*` не являются установщиками для новых пользователей.

## Обслуживание

Для runner-установки из `deploy/self-host` используйте оба файла; для базовой уберите `-f compose.runner.yaml`:

```bash
docker compose -f compose.yaml -f compose.runner.yaml ps
docker compose -f compose.yaml -f compose.runner.yaml logs --tail=100 api worker deployer
```

- Остановка: приостановите pipeline, дождитесь или отмените работу, затем `docker compose ... stop`. Volumes при обычной остановке не удаляются.
- Бэкап: PostgreSQL, SSH volume, `.env`, GitHub App key, registry, артефакты и целевые receipt/configuration-каталоги. Шифрованная копия нужна вне этого хоста. Бэкап центра не содержит БД и пользовательские файлы приложений на других машинах.
- Обновление: запишите commit, сохраните теги прежних API/web/worker/deployer/builder; приостановите pipeline и дождитесь jobs; сделайте dump и копии volumes/configuration; обновите checkout; запустите `scripts/prepare-release.sh`; соберите API/web, затем worker/deployer и пересоздайте службы. Flyway применяется при старте; уже применённые SQL-миграции не редактируются.
- Приёмка: health, вход владельца, 401 для API без входа, миграции, Java-пример и временный деплой. После этого включайте pipeline.
- Откат: совместимые сохранённые образы и конфигурация. Возврат старого приложения не откатывает схему БД; при необходимости восстанавливайте проверенный backup в окне обслуживания.

Пример dump с закрытыми правами файла:

```bash
umask 077
docker compose -f compose.yaml -f compose.runner.yaml exec -T postgres \
  pg_dump -U infra_hub -d infra_hub -Fc > infra-hub.dump
```

Восстанавливайте сначала в изолированную пустую replacement-БД при остановленных API/worker/deployer: `pg_restore -U infra_hub -d infra_hub --no-owner` через `docker compose exec -T postgres`, затем проверяйте перед переключением трафика. Копирование файлов работающей PostgreSQL не заменяет согласованный dump. Для volumes нужна отдельная процедура с сохранением владельцев: приложение UID 10001, rootless Docker использует собственное отображение UID.

## Диагностика

| Проблема | Что проверить |
| --- | --- |
| Нет TLS | A/AAAA, доступность 80/443, другой listener, журнал Caddy |
| Ошибка OAuth | Точный callback, HTTPS cookies, secret и числовой owner ID |
| Job остаётся QUEUED | Worker, связь с БД, другое активное задание |
| Runner не настроен | Оба Compose-файла, secret, доступ к rootless socket, builder image |
| Ошибка resource validation | cgroup v2, delegation, реальные лимиты и настройки CPU/RAM |
| Не загружаются зависимости | Proxy/socket/volume, allowlist, DNS/HTTPS, нужный toolchain |
| Нет приватных исходников | Installation ID, выбранный репозиторий, формат/права ключа и Contents read |
| Ошибка JAR/push | Точный путь, лимит 256 MiB, executable JAR, runtime digest, registry |
| Сменился SSH host key | Независимая проверка сервера; автоматическая замена запрещена |
| Деплой INTERRUPTED | Сверка того же UUID, remote receipt и фактического приложения |
| Заканчивается диск | БД/логи, registry, Docker layers, артефакты; автоматической очистки нет |

Дополнительно: [эксплуатация](operations.md), [безопасность](../SECURITY.md), [возможности](capabilities.md). Проверяйте восстановление и изоляцию в своей среде: локальные тесты и разбор конфигурации сами по себе не подтверждают готовность production-установки.
