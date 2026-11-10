# Промпты генерации макетов

Способ: встроенный ImageGen. Фон непрозрачный. Каждая секция использована для отдельного изображения.

## 01 / Проекты

```text
Use case: ui-mockup.
Generate a high-fidelity DESKTOP APPLICATION SCREENSHOT for infra-hub, the open-source self-hosted build, deployment and infrastructure platform. This is a functional application mockup, not a brand board, not a landing page, not a marketing poster. ONE complete screen per image, landscape about 3:2, at high resolution with exceptionally crisp, legible Russian interface text. Flat orthographic screen, no monitor, no browser frame, no perspective, no photographic texture.
REFERENCE: The attached image is the APPROVED brand identity, direction 01 "СВОЯ МОЩНОСТЬ". Preserve its electric blue custom emblem of two offset interlocking angular ribbons and white negative-space opening. Preserve lowercase infra-hub wordmark, cobalt #3155E7, graphite #202323, warm off-white #F5F3EC, pale periwinkle #DEE5FF. Never switch to the muted teal bracket identity or moss arch identity. Unbounded-inspired expressive headings only, Onest-like highly legible normal-size UI text, IBM Plex Mono-like technical text. Do not use oversized heavy text for table data. Selectively clipped corners on main blue buttons, refined 4–6px corner radii on ordinary controls, thin neutral separators, generous but efficient information density.
COMMON APP SHELL, use this same shell consistently: a compact horizontal white masthead across the top with the small blue approved emblem and black wordmark at left; primary navigation "Проекты", "Сборки", "Инфраструктура", "MCP"; a small global search field "Поиск по инфраструктуре" and an owner avatar at right. Active section has blue underline. No permanent dark sidebar. A thin footer says "Личная установка / демонстрационные данные". Interior sidebar is allowed only as specific local navigation. Show restrained functional icons, native-looking readable fields, consistent button styles. Page title about 28–32px, ordinary UI text about 14–16px. Clear primary action, explicit states and timestamps. Avoid irrelevant charts, giant vanity counters, slogans, gradient cards, glow, decorative abstract logos blown up behind content. All panels and table columns must align and fit the frame. Text should be short, meaningful, spelled correctly. Fictional demonstration data only.
SCREEN 01: PROJECTS AND ENVIRONMENTS. Active top navigation "Проекты".
The image should instantly explain what the product does and how the user acts, not merely list menu names.
Header: "Проекты", subtext "Сборки и приложения на ваших серверах.", primary blue clipped-corner action "+ Подключить проект".
Workspace main area about 72% width, right operational rail about 28%. Main content has a search/filter row: "Найти проект", environment dropdown "Все окружения".
A readable operational project table with columns "Проект", "Последняя сборка", "Рабочая версия", "Сервер". Three rows:
- "Aukni", subtitle "Kotlin / Spring Boot · main"; latest build "#148 · Собирается" blue status plus a small four-segment pipeline, first two complete, third active, fourth pending; deployed commit "c2ef9a2" with green text "Работает"; server "prod-01" and domain "aukni.example".
- "Tuk Tuk Bot", subtitle "Kotlin / Telegram · main"; "#146 · Успешно"; running commit "70e0ce0"; server "prod-01".
- "Infra Hub", subtitle "Kotlin / React · main"; "#147 · В очереди"; running commit "a8b4f21"; server "dev-01" and domain "hub.example".
The distinction between latest build state and running version state MUST be clear: a running new build never implies production is down.
Below table show an expanded selected project section "Aukni / окружения" with two clear environment rows:
"production" / "prod-01" / "c2ef9a2" / "Проверка пройдена" / secondary action "История выпусков".
"development" / "dev-01" / "b3d7a12" / "Работает" / secondary action "Открыть".
Include project tabs "Приложения", "Pipeline", "Переменные", "История", with "Приложения" active, and a useful action "Запустить сборку".
Right rail: "Очередь сборок", one active entry "Aukni #148" / "builder-01" / "01:42" with an action "Шаги и логи"; queued "Infra Hub #147" / "Ожидает исполнителя".
Then "Сборочный узел" / "builder-01" / "8 vCPU · 32 GB", simple CPU bar "48%" and memory "11.4 / 32 GB". Explicit concurrency "Тяжёлые сборки: 1 / 1". A small link "Управлять узлами".
No marketing text and no invented savings in dollars. Comfortable, visually confident and distinctively branded app that an engineer would want to use every day.
```

## 02 / Сборка и деплой

```text
Use case: ui-mockup.
Generate a high-fidelity DESKTOP APPLICATION SCREENSHOT for infra-hub, the open-source self-hosted build, deployment and infrastructure platform. This is a functional application mockup, not a brand board, not a landing page, not a marketing poster. ONE complete screen per image, landscape about 3:2, at high resolution with exceptionally crisp, legible Russian interface text. Flat orthographic screen, no monitor, no browser frame, no perspective, no photographic texture.
REFERENCE: The attached image is the APPROVED brand identity, direction 01 "СВОЯ МОЩНОСТЬ". Preserve its electric blue custom emblem of two offset interlocking angular ribbons and white negative-space opening. Preserve lowercase infra-hub wordmark, cobalt #3155E7, graphite #202323, warm off-white #F5F3EC, pale periwinkle #DEE5FF. Never switch to the muted teal bracket identity or moss arch identity. Unbounded-inspired expressive headings only, Onest-like highly legible normal-size UI text, IBM Plex Mono-like technical text. Do not use oversized heavy text for table data. Selectively clipped corners on main blue buttons, refined 4–6px corner radii on ordinary controls, thin neutral separators, generous but efficient information density.
COMMON APP SHELL, use this same shell consistently: a compact horizontal white masthead across the top with the small blue approved emblem and black wordmark at left; primary navigation "Проекты", "Сборки", "Инфраструктура", "MCP"; a small global search field "Поиск по инфраструктуре" and an owner avatar at right. Active section has blue underline. No permanent dark sidebar. A thin footer says "Личная установка / демонстрационные данные". Interior sidebar is allowed only as specific local navigation. Show restrained functional icons, native-looking readable fields, consistent button styles. Page title about 28–32px, ordinary UI text about 14–16px. Clear primary action, explicit states and timestamps. Avoid irrelevant charts, giant vanity counters, slogans, gradient cards, glow, decorative abstract logos blown up behind content. All panels and table columns must align and fit the frame. Text should be short, meaningful, spelled correctly. Fictional demonstration data only.
Additional reference: the second attached image is SCREEN 01 already generated for this same app. Reuse its exact compact masthead, original blue logo, navigation placement, type proportions, electric blue buttons, pale blue selection and warm white surfaces, so this looks like another page of the SAME application. The second reference is a visual-shell reference, not content to copy. Keep the common masthead horizontally positioned at the same height and the owner avatar at right. All key user actions described below must be visibly present, with practical UI controls.
SCREEN 02: BUILD DETAIL WITH DEPLOYMENT SETUP. Active top navigation "Сборки".
Use a main work area about 68% width and a right deployment panel about 32%, so both build evidence and deployment choices are readable.
Breadcrumb "Сборки / Aukni / #148". Title "Aukni / сборка #148". Success badge "Успешно". Readable compact metadata "main", commit "b7e3a91", worker "builder-01", duration "02:18". Secondary buttons "Повторить", "Скачать лог".
A horizontal four-step pipeline "Исходники", "Тесты", "Образ", "Публикация", all completed with green checks. This screenshot is a later moment than the reference screen: build #148 has NOW completed, so no contradictory running state.
Below: tab strip "Логи", "Тесты", "Артефакты", with "Логи" active. A vertical list of steps at left of a wide dark terminal. Selected step "Публикация". Terminal shows short believable lines in readable mono:
"$ docker push registry.example/aukni:b7e3a91"
"Layer already exists"
"Application layer pushed"
"digest: sha256:1e58a0...9bdc"
"Image published successfully"
A compact test summary below: "84 теста пройдено / 0 ошибок".
An artifact row "aukni:b7e3a91", "sha256:1e58a0...9bdc", a secondary "Копировать digest" action. Small explanation "Собрать один раз — выложить тот же образ."
Below main build panel, a useful concise release-history table "История выпусков": current production "c2ef9a2" / "prod-01" / "Работает", previous release "9f3d812" / "prod-01" / "Предыдущий". A restrained visible action "Вернуть версию" on the previous entry. Do not claim database rollback is automatic.
Right deployment panel title "Выложить готовый образ". State "Ожидает подтверждения".
Fields: "Окружение" dropdown "production"; "Целевой сервер" dropdown "prod-01"; "Образ" readonly "aukni:b7e3a91"; "Проверка" text "GET /actuator/health"; "Таймаут" value "60 с". A selected checkbox "Подтвердить выпуск в production".
A short clear comparison line "Сейчас: c2ef9a2 → Новая: b7e3a91". Large primary blue clipped-corner button "Подтвердить деплой". Secondary "Отмена".
The panel makes it clear that image build succeeded, but the new image is NOT YET deployed to production.
Add a small compact entry under history "Следующая сборка #149 / Выполняется" and secondary action "Отменить сборку", to show cancellation without contradicting selected successful build #148. Keep all content within the frame, no modal obscuring the main functionality.
```

## 03 / Инфраструктура

```text
Use case: ui-mockup.
Generate a high-fidelity DESKTOP APPLICATION SCREENSHOT for infra-hub, the open-source self-hosted build, deployment and infrastructure platform. This is a functional application mockup, not a brand board, not a landing page, not a marketing poster. ONE complete screen per image, landscape about 3:2, at high resolution with exceptionally crisp, legible Russian interface text. Flat orthographic screen, no monitor, no browser frame, no perspective, no photographic texture.
REFERENCE: The attached image is the APPROVED brand identity, direction 01 "СВОЯ МОЩНОСТЬ". Preserve its electric blue custom emblem of two offset interlocking angular ribbons and white negative-space opening. Preserve lowercase infra-hub wordmark, cobalt #3155E7, graphite #202323, warm off-white #F5F3EC, pale periwinkle #DEE5FF. Never switch to the muted teal bracket identity or moss arch identity. Unbounded-inspired expressive headings only, Onest-like highly legible normal-size UI text, IBM Plex Mono-like technical text. Do not use oversized heavy text for table data. Selectively clipped corners on main blue buttons, refined 4–6px corner radii on ordinary controls, thin neutral separators, generous but efficient information density.
COMMON APP SHELL, use this same shell consistently: a compact horizontal white masthead across the top with the small blue approved emblem and black wordmark at left; primary navigation "Проекты", "Сборки", "Инфраструктура", "MCP"; a small global search field "Поиск по инфраструктуре" and an owner avatar at right. Active section has blue underline. No permanent dark sidebar. A thin footer says "Личная установка / демонстрационные данные". Interior sidebar is allowed only as specific local navigation. Show restrained functional icons, native-looking readable fields, consistent button styles. Page title about 28–32px, ordinary UI text about 14–16px. Clear primary action, explicit states and timestamps. Avoid irrelevant charts, giant vanity counters, slogans, gradient cards, glow, decorative abstract logos blown up behind content. All panels and table columns must align and fit the frame. Text should be short, meaningful, spelled correctly. Fictional demonstration data only.
Additional reference: the second attached image is SCREEN 01 already generated for this same app. Reuse its exact compact masthead, original blue logo, navigation placement, type proportions, electric blue buttons, pale blue selection and warm white surfaces, so this looks like another page of the SAME application. The second reference is a visual-shell reference, not content to copy. Keep the common masthead horizontally positioned at the same height and the owner avatar at right. All key user actions described below must be visibly present, with practical UI controls.
SCREEN 03: INFRASTRUCTURE INVENTORY AND SERVER DETAIL. Active top navigation "Инфраструктура".
Title "Инфраструктура". Subtext "Серверы, приложения, домены и базы — со связями.". Primary button "+ Подключить сервер".
Show a compact local category navigation "Серверы", "Домены", "PostgreSQL", "Xray", with "Серверы" active; include a real infrastructure search field "Имя, IP, домен или проект".
Layout with a server list in a narrow column and detailed selected server occupying the rest. Server list: "prod-01" / "Приложения", "builder-01" / "Сборки", "dev-01" / "Разработка". Selected "prod-01" has a restrained pale-blue selection.
Main detail header "prod-01", green text badge "На связи", secondary action "Настроить опрос". Metadata "Ubuntu / x86_64", "192.0.2.10", "Последний опрос: 14:35". Small grey editable ownership details "Провайдер: свой VDS", "Стоимость: 2 400 ₽ / мес", "Оплата: 20 ноября". All are fictional demo values.
Compact resource row "CPU 36%", "RAM 4.8 / 8 GB", "Диск 42 / 120 GB", with proportionate small bars, not three giant cards.
Then a clear selected server inventory in TWO well-aligned functional sections, showing actual infrastructure objects rather than only tabs:
- "Приложения и домены" semantic table: "Aukni" / "aukni.example" / "c2ef9a2" / "Работает"; "Tuk Tuk Bot" / "—" / "70e0ce0" / "Работает". Clear DNS relationship under domain "A → 192.0.2.10" and HTTPS status "TLS до 08.02.2027". Action "Открыть проект".
- "PostgreSQL" subsection: "PostgreSQL 17" / "aukni-db" / "Базы: aukni, metrics"; extension chips "pg_trgm", "pgcrypto"; backup state "Последний бэкап: сегодня, 03:00". Action "Подробнее".
- "Службы" subsection: "Xray" / "systemd: xray" / "Запущена" / "Проверено 14:35". Next service "Docker" / "Работает". Do NOT show any full Xray configuration, keys, user UUIDs or secrets.
A visible neutral collection note "Сбой опроса сохраняет последние данные и отмечает их устаревшими." Add a small unselected dev-01 warning in list "Нет свежих данных / 2 ч", demonstrating stale status rather than marking everything green.
Right or bottom mini linkage strip clearly expresses "aukni.example → prod-01 → Aukni → aukni-db" with simple text arrows, demonstrating the infrastructure map without filling screen with a network graph.
The interface must convey the catalog and search functionality, health, ownership, backups and domain/app/database relationships in one coherent server page. Do not crowd content or invent elaborate analytics.
```

## 04 / MCP

```text
Use case: ui-mockup.
Generate a high-fidelity DESKTOP APPLICATION SCREENSHOT for infra-hub, the open-source self-hosted build, deployment and infrastructure platform. This is a functional application mockup, not a brand board, not a landing page, not a marketing poster. ONE complete screen per image, landscape about 3:2, at high resolution with exceptionally crisp, legible Russian interface text. Flat orthographic screen, no monitor, no browser frame, no perspective, no photographic texture.
REFERENCE: The attached image is the APPROVED brand identity, direction 01 "СВОЯ МОЩНОСТЬ". Preserve its electric blue custom emblem of two offset interlocking angular ribbons and white negative-space opening. Preserve lowercase infra-hub wordmark, cobalt #3155E7, graphite #202323, warm off-white #F5F3EC, pale periwinkle #DEE5FF. Never switch to the muted teal bracket identity or moss arch identity. Unbounded-inspired expressive headings only, Onest-like highly legible normal-size UI text, IBM Plex Mono-like technical text. Do not use oversized heavy text for table data. Selectively clipped corners on main blue buttons, refined 4–6px corner radii on ordinary controls, thin neutral separators, generous but efficient information density.
COMMON APP SHELL, use this same shell consistently: a compact horizontal white masthead across the top with the small blue approved emblem and black wordmark at left; primary navigation "Проекты", "Сборки", "Инфраструктура", "MCP"; a small global search field "Поиск по инфраструктуре" and an owner avatar at right. Active section has blue underline. No permanent dark sidebar. A thin footer says "Личная установка / демонстрационные данные". Interior sidebar is allowed only as specific local navigation. Show restrained functional icons, native-looking readable fields, consistent button styles. Page title about 28–32px, ordinary UI text about 14–16px. Clear primary action, explicit states and timestamps. Avoid irrelevant charts, giant vanity counters, slogans, gradient cards, glow, decorative abstract logos blown up behind content. All panels and table columns must align and fit the frame. Text should be short, meaningful, spelled correctly. Fictional demonstration data only.
Additional reference: the second attached image is SCREEN 01 already generated for this same app. Reuse its exact compact masthead, original blue logo, navigation placement, type proportions, electric blue buttons, pale blue selection and warm white surfaces, so this looks like another page of the SAME application. The second reference is a visual-shell reference, not content to copy. Keep the common masthead horizontally positioned at the same height and the owner avatar at right. All key user actions described below must be visibly present, with practical UI controls.
SCREEN 04: MCP SETTINGS, CLIENT ACCESS AND AUDIT. Active top navigation "MCP".
Title "MCP и ассистенты". Subtext "Доступ к infra-hub и подключения внешних инструментов."
Readable local tabs "Доступ ассистентов", "Внешние MCP", "Журнал". "Доступ ассистентов" active. The active page shows the full core configuration, with a right contextual rail for external connections and audit so those functions are visible too.
Main content about 62% width: a settings form with label column and value column.
"MCP-сервер" toggle enabled, short transport label "Streamable HTTP".
"Адрес" readonly "https://hub.example/mcp", adjacent copy icon button.
"Проекты" multi-select "Aukni, Infra Hub".
"Разрешения": CHECKED "Читать состояние и логи"; CHECKED "Запускать и отменять сборки"; UNCHECKED "Выкладывать приложения".
"Production" dropdown "Только с подтверждением владельца". Small label "Правила проверяются сервером".
"Авторизация" row "Отдельный доступ для каждого клиента" and button "Параметры подключения". Never display a real token or ask for a raw credential.
Primary blue button "Сохранить настройки".
Below: table heading "Клиенты", action "+ Добавить клиент". Columns "Клиент", "Права", "Срок", "Действие". Two rows: "Рабочий ассистент" / "Чтение и сборки" / "30 дней" / "Отозвать"; "Личный ноутбук" / "Только чтение" / "7 дней" / "Отозвать". This makes access revocation explicit.
Right contextual area:
section "Внешние MCP", button "+ Подключить". Connection "GitHub" / "Секрет: GITHUB_MCP_TOKEN" / "Не проверен" / secondary "Проверить". Connection "Документация" / "Секрет: DOCS_MCP_TOKEN" / "Отключён" / secondary "Включить". These are secret references, not actual credential values. This subsection represents MCP tools infra-hub may connect to, clearly distinct from assistant clients calling infra-hub.
section "Последние вызовы": readable audit rows
"14:32 / build.logs / Aukni #148 / Разрешён"
"14:31 / build.start / Infra Hub / Разрешён"
"14:29 / deployment.start / production / Отклонён"
The denied production call has red icon and a concise reason "Нет права на деплой". A small "Весь журнал" action.
The page visibly demonstrates configuration, project scoping, capabilities, confirmation policy, per-client credentials lifecycle, outbound MCP connections, test status, and audit. It should remain a usable settings screen with comfortable spacing, not a dense permissions spreadsheet. Keep a visible "Демонстрационные данные" footer.
```

## 05 / Настройка pipeline

```text
Use case: ui-mockup.
Generate a high-fidelity DESKTOP APPLICATION SCREENSHOT for infra-hub, the open-source self-hosted build, deployment and infrastructure platform. This is a functional application mockup, not a brand board, not a landing page, not a marketing poster. ONE complete screen per image, landscape about 3:2, at high resolution with exceptionally crisp, legible Russian interface text. Flat orthographic screen, no monitor, no browser frame, no perspective, no photographic texture.
REFERENCE: The attached image is the APPROVED brand identity, direction 01 "СВОЯ МОЩНОСТЬ". Preserve its electric blue custom emblem of two offset interlocking angular ribbons and white negative-space opening. Preserve lowercase infra-hub wordmark, cobalt #3155E7, graphite #202323, warm off-white #F5F3EC, pale periwinkle #DEE5FF. Never switch to the muted teal bracket identity or moss arch identity. Unbounded-inspired expressive headings only, Onest-like highly legible normal-size UI text, IBM Plex Mono-like technical text. Do not use oversized heavy text for table data. Selectively clipped corners on main blue buttons, refined 4–6px corner radii on ordinary controls, thin neutral separators, generous but efficient information density.
COMMON APP SHELL, use this same shell consistently: a compact horizontal white masthead across the top with the small blue approved emblem and black wordmark at left; primary navigation "Проекты", "Сборки", "Инфраструктура", "MCP"; a small global search field "Поиск по инфраструктуре" and an owner avatar at right. Active section has blue underline. No permanent dark sidebar. A thin footer says "Личная установка / демонстрационные данные". Interior sidebar is allowed only as specific local navigation. Show restrained functional icons, native-looking readable fields, consistent button styles. Page title about 28–32px, ordinary UI text about 14–16px. Clear primary action, explicit states and timestamps. Avoid irrelevant charts, giant vanity counters, slogans, gradient cards, glow, decorative abstract logos blown up behind content. All panels and table columns must align and fit the frame. Text should be short, meaningful, spelled correctly. Fictional demonstration data only.
The second attached reference is an already generated projects screen of this app. Keep its exact masthead, same logo, navigation positions, font hierarchy and UI component styling. Match this SAME application, not a different website.
SCREEN 05: PROJECT SETUP AND PIPELINE CONFIGURATION. Active top navigation "Проекты". One complete desktop app screenshot, clean and highly legible, no poster.
Breadcrumb "Проекты / Aukni / Настройки". Title "Настройка сборки". Subtitle "От репозитория до готового образа."
A concise local tab strip "Репозиторий", "Pipeline", "Переменные", "Окружения", with "Pipeline" selected.
A slim upper summary row shows connected repository "github.com/demo/aukni", branch "main", connection status "Доступ настроен", secondary action "Изменить". All are fictional demo.
Main area split about 62/38.
Left configuration form:
section "Как собирать": radio choices "Dockerfile" and "Команды", with "Команды" selected.
"Исполнитель" dropdown "builder-01 / Linux x86_64".
"Рабочая папка" input "./".
"Команды pipeline": a readable small editable code area with three numbered named steps:
"Тесты" with command "./gradlew test --build-cache"
"Образ" with command "docker buildx build --tag $IMAGE_TAG ."
"Публикация" with command "docker push $IMAGE_TAG"
Each step has a short name and command; visible "+ Добавить шаг", up/down or drag handle controls. Step text is monospace, controls are practical. Do not show broken Kotlin snippets or long YAML.
section "Кеш и ресурсы": checked "Кеш Gradle"; checked "Кеш Docker-слоёв"; fields "CPU" value "6 vCPU", "Память" value "20 GB", "Таймаут" value "20 мин". Short line "Одна тяжёлая сборка одновременно." No claim these are measured minima.
section "Запуск": checked "После push в main"; unchecked "По расписанию"; primary blue clipped-corner "Сохранить и запустить", secondary "Сохранить".
Right context:
"Registry": address "registry.example", image name "aukni", tag "commit SHA".
"Секреты pipeline": two secret-reference rows "REGISTRY_TOKEN" / "Настроен", "DEPLOY_SSH_KEY" / "Настроен". Never show raw values. Secondary "+ Добавить секрет". Small text "Значения скрыты в логах".
"Окружения": "development / dev-01" and "production / prod-01". Production note "Выпуск с подтверждением". Secondary "Настроить деплой".
A small neutral boxed summary "Следующий запуск" with "Aukni / main", "builder-01", "Тесты → Образ → Публикация". This is a meaningful validation preview, not a decorative metric card.
Keep all sections spacious and aligned. Show actual editable setup and the path to first build, rather than an empty settings screen or menu names only. Do not use success badges for an execution that hasn't happened. Brand blue remains selected state and actions, ordinary text graphite. All content should fit the full frame.

```
