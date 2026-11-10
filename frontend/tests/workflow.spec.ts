import { test, expect } from '@playwright/test'

test('project, real build, persisted logs, responsive layout', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/')
  await page.getByRole('button', { name: '＋ Новый проект' }).click()
  const name = `Browser ${Date.now()}`
  await page.getByLabel('Название', { exact: true }).fill(name)
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click()
  const row = page.locator('article').filter({ hasText: name })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Запустить проверку' }).click()
  const detail = page.getByRole('region', { name: 'Выбранная сборка' })
  await expect(detail.getByRole('status')).toContainText('тесты пройдены', { timeout: 30000 })
  await expect(detail.getByLabel('Логи сборки')).toContainText('PASS: greeting')
  await page.screenshot({ path: '../.local/checks/desktop.png', fullPage: true })
  await page.reload()
  await expect(page.locator('article').filter({ hasText: name })).toBeVisible()
  await page.locator('.build-row').filter({ hasText: name }).first().click()
  await expect(detail.getByLabel('Логи сборки')).toContainText('PASS: greeting')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('h1')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/mobile.png', fullPage: true })
  expect(errors).toEqual([])
})


test('repository command and ref are submitted and access errors stay visible', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '＋ Новый проект' }).click()
  const name = `Repository ${Date.now()}`
  await page.getByLabel('Название', { exact: true }).fill(name)
  await page.getByLabel('Репозиторий GitHub').fill('git@github.com:minpor/aukni.git')
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click()
  const row = page.locator('article').filter({ hasText: name })
  await expect(row).toContainText('https://github.com/minpor/aukni')
  await row.getByRole('button', { name: 'Собрать репозиторий' }).click()
  await page.getByLabel('Ветка, тег или commit').fill('dev')
  await page.getByLabel('Команда сборки').fill('cd backend && sh ./gradlew --no-daemon build')
  await page.getByLabel('JAR для Docker-образа').fill('backend/build/libs/app.jar')
  await page.route('**/api/projects/*/builds', async route => {
    const input = route.request().postDataJSON()
    expect(input.ref).toBe('dev')
    expect(input.artifactPath).toBe('backend/build/libs/app.jar')
    expect(input.command).toBe('cd backend && sh ./gradlew --no-daemon build')
    await route.fulfill({ status: 502, json: { message: 'Предоставьте GitHub App доступ к репозиторию.' } })
  })
  await page.getByRole('button', { name: 'Поставить в очередь' }).click()
  await expect(page.getByRole('alert')).toContainText('GitHub App')
  await expect(page.getByLabel('Команда сборки')).toBeVisible()
  await page.screenshot({ path: '../.local/checks/repository.png', fullPage: true })
})

test('server onboarding persists, explains aliases, pins identity and shows connection errors', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('link', { name: 'Серверы', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Серверы', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '＋ Подключить сервер' }).click()
  await expect(page.getByText('Панель подключается со своего сервера', { exact: false })).toBeVisible()
  const name = `SSH ${Date.now()}`
  await page.getByLabel('Название сервера', { exact: true }).fill(name)
  await page.getByLabel('IP-адрес или DNS-имя').fill('127.0.0.1')
  await page.getByLabel('SSH-порт', { exact: true }).fill('1')
  await page.getByLabel('SSH-пользователь').fill('deploy')
  await page.getByRole('button', { name: 'Добавить и настроить SSH' }).click()
  const card = page.getByRole('article', { name: `Сервер ${name}`, exact: true })
  await expect(card.getByLabel('Команда добавления ключа')).toHaveValue(/restrict ssh-ed25519/)
  await expect(card.getByRole('button', { name: 'Проверить подключение', exact: true })).toBeDisabled()
  await page.route('**/api/servers/*/host-key', route => route.fulfill({ json: { key: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPOof2RsedZ12zSQpIzZR+U1botYTngaMld5L0NRQ6Rr', fingerprint: 'SHA256:test-ui-scan' } }))
  await card.getByRole('button', { name: 'Получить отпечаток', exact: true }).click()
  await expect(card.getByText('SHA256:test-ui-scan', { exact: true })).toBeVisible()
  await card.getByRole('button', { name: 'Отпечаток совпадает — доверять' }).click()
  await card.getByRole('button', { name: 'Проверить подключение', exact: true }).click()
  await expect(card.getByRole('status')).toContainText('Не удалось подключиться по SSH')
  await page.reload()
  await expect(card).toContainText('Ошибка подключения')
  await expect(card.getByText('Ключ сервера закреплён.', { exact: false })).toBeVisible()
  await page.screenshot({ path: '../.local/checks/servers-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/servers-mobile.png', fullPage: true })
})

test('application configuration, digest selection, deployment history and retry', async ({ page }) => {
  const project = { id: 'project-1', name: 'Demo' }
  const server = { id: 'server-1', name: 'minpor-dev', status: 'READY' }
  const image = { reference: 'localhost:5000/demo@sha256:' + 'a'.repeat(64) }
  const app = { id: 'application-1', projectId: project.id, serverId: server.id, name: 'Demo application', hostPort: 18090, containerPort: 8080, healthPath: '/health', environmentKeys: ['TOKEN'], currentDeployment: null }
  const build = { id: 'build-1', projectId: project.id, status: 'SUCCEEDED', source: { sha: 'b'.repeat(40) }, image }
  let apps: unknown[] = []
  let deployments: unknown[] = []
  let retried = false
  await page.route('**/api/projects', route => route.fulfill({ json: [project] }))
  await page.route('**/api/servers', route => route.fulfill({ json: [server] }))
  await page.route('**/api/builds', route => route.fulfill({ json: [build] }))
  await page.route('**/api/pipelines', route => route.fulfill({ json: [] }))
  await page.route('**/api/deployments/release-1/artifacts', route => route.fulfill({ json: [] }))
  await page.route('**/api/applications', async route => {
    if (route.request().method() === 'POST') {
      const input = route.request().postDataJSON()
      expect(input.environment).toEqual({ TOKEN: 'private=value' })
      expect(input.hostPort).toBe(18090)
      apps = [app]
      await route.fulfill({ json: app })
    } else await route.fulfill({ json: apps })
  })
  await page.route('**/api/deployments', route => route.fulfill({ json: deployments }))
  await page.route('**/api/applications/application-1/deployments', async route => {
    expect(route.request().postDataJSON().buildId).toBe(build.id)
    const release = { id: 'release-1', applicationId: app.id, buildId: build.id, imageReference: image.reference, status: 'INTERRUPTED', message: 'Потерян ответ SSH', createdAt: '2026-11-10T08:00:00Z', startedAt: '2026-11-10T08:01:30Z', finishedAt: '2026-11-10T08:04:00Z', executionMillis: 150000 }
    deployments = [release]
    await route.fulfill({ json: release })
  })
  await page.route('**/api/deployments/release-1/logs', route => route.fulfill({ json: [{ text: 'SSH transfer' }] }))
  await page.route('**/api/deployments/release-1/retry', async route => { retried = true; await route.fulfill({ json: {} }) })
  await page.goto('/#applications')
  await page.getByRole('button', { name: '＋ Новое приложение' }).click()
  await page.getByLabel('Способ деплоя').selectOption('STANDALONE')
  await page.getByLabel('Название приложения').fill(app.name)
  await page.getByRole('combobox', { name: 'Проект', exact: true }).selectOption(project.id)
  await page.getByRole('combobox', { name: 'Сервер приложения', exact: true }).selectOption(server.id)
  await page.getByLabel('HTTP-путь проверки здоровья').fill('/health')
  await page.getByLabel('Переменные окружения').fill('TOKEN=private=value')
  await page.getByRole('button', { name: 'Создать приложение', exact: true }).click()
  await expect(page.getByText('Переменные: TOKEN')).toBeVisible()
  await expect(page.getByText('private=value', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Развернуть', exact: true }).click()
  await expect(page.getByLabel('Логи деплоя')).toContainText('SSH transfer')
  await expect(page.locator('.run-timing')).toContainText('1,5 мин')
  await expect(page.locator('.run-timing')).toContainText('2,5 мин')
  await expect(page.getByRole('button', { name: 'Развернуть', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: 'Сверить и повторить' }).click()
  await expect.poll(() => retried).toBe(true)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  await page.screenshot({ path: '../.local/checks/deployments.png', fullPage: true })
})

test('managed release uses the displayed branch and artifact when newest project build belongs to another target', async ({ page }) => {
  const image = { reference: 'localhost:5000/demo@sha256:' + 'a'.repeat(64) }
  const app = { id: 'managed-dev', projectId: 'shared-project', serverId: 'dev-server', name: 'Managed dev', kind: 'COMPOSE', configuration: { ref: 'dev', root: '/opt/existing', url: 'https://dev.example' }, containerPort: 8080, healthPath: '/api/health', environmentKeys: [], currentDeployment: null }
  const builds = [
    { id: 'newest-prod-android', projectId: app.projectId, status: 'SUCCEEDED', source: { ref: 'main', sha: 'c'.repeat(40), artifactPath: 'android-release.jar' }, image },
    { id: 'dev-android', projectId: app.projectId, status: 'SUCCEEDED', source: { ref: 'dev', sha: 'b'.repeat(40), artifactPath: 'android-release.jar' }, image },
    { id: 'dev-web', projectId: app.projectId, status: 'SUCCEEDED', source: { ref: 'dev', sha: 'b'.repeat(40), artifactPath: 'backend/app.jar' }, image },
  ]
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: app.projectId, name: 'Shared project' }] }))
  await page.route('**/api/servers', route => route.fulfill({ json: [{ id: app.serverId, name: 'Dev', status: 'READY' }] }))
  await page.route('**/api/builds', route => route.fulfill({ json: builds }))
  await page.route('**/api/applications', route => route.fulfill({ json: [app] }))
  await page.route('**/api/pipelines', route => route.fulfill({ json: [] }))
  await page.route('**/api/deployments', route => route.fulfill({ json: [] }))
  let submitted = ''
  await page.route('**/api/applications/managed-dev/deployments', async route => {
    submitted = route.request().postDataJSON().buildId
    await route.fulfill({ json: { id: 'managed-release' } })
  })
  await page.route('**/api/deployments/managed-release/logs', route => route.fulfill({ json: [] }))
  await page.route('**/api/deployments/managed-release/artifacts', route => route.fulfill({ json: [] }))
  await page.goto('/#applications')
  await expect(page.getByLabel('Образ для деплоя')).toHaveValue('dev-web')
  await expect(page.getByLabel('Образ для деплоя').locator('option')).toHaveCount(2)
  await page.getByRole('button', { name: 'Развернуть', exact: true }).click()
  await expect.poll(() => submitted).toBe('dev-web')
})

test('runner CPU and memory limits persist and explain when changes apply', async ({ page }) => {
  let settings = { cpus: 6, memoryGiB: 16, maxCpus: 20, maxMemoryGiB: 18 }
  await page.route('**/api/runner-settings', async route => {
    if (route.request().method() === 'POST') settings = { ...settings, ...route.request().postDataJSON() }
    await route.fulfill({ json: settings })
  })
  await page.goto('/#runner')
  await expect(page.getByRole('heading', { name: 'Настройки', exact: true })).toBeVisible()
  await page.getByLabel('CPU', { exact: true }).fill('8')
  await page.getByLabel('Память, GiB', { exact: true }).fill('14')
  await page.getByRole('button', { name: 'Сохранить настройки' }).click()
  await expect(page.getByRole('status')).toContainText('8 CPU и 14 GiB')
  await page.reload()
  await expect(page.getByLabel('CPU', { exact: true })).toHaveValue('8')
  await expect(page.getByLabel('Память, GiB', { exact: true })).toHaveValue('14')
  await expect(page.getByText('Изменения применяются при запуске следующей сборки.', { exact: false })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/runner-mobile.png', fullPage: true })
})

test('MCP settings create a scoped key once, copy Hermes configuration and revoke access', async ({ page }) => {
  const project = { id: 'fixture-project', name: 'Fixture project' }
  let tokens: unknown[] = []
  const token = { id: 'token-fixture', name: 'Hermes', permissions: ['READ', 'RELEASE', 'CONFIGURE'], projectIds: [project.id], createdAt: new Date().toISOString(), expiresAt: null, lastUsedAt: null, revokedAt: null as string | null }
  await page.route('**/api/projects', route => route.fulfill({ json: [project] }))
  await page.route('**/api/mcp-settings', route => route.fulfill({ json: { url: 'https://infra-hub.minpor.ru/mcp', tokens, audit: [] } }))
  await page.route('**/api/mcp-settings/tokens', async route => {
    expect(route.request().postDataJSON()).toMatchObject({ name: 'Hermes', permissions: ['READ', 'CONFIGURE', 'RELEASE'], projectIds: [project.id], expiresInDays: 90 })
    tokens = [token]
    await route.fulfill({ json: { id: token.id, name: token.name, token: 'ihm_test_only_not_a_real_credential' } })
  })
  await page.route('**/api/mcp-settings/tokens/token-fixture/revoke', async route => { token.revokedAt = new Date().toISOString(); await route.fulfill({ json: { revoked: true } }) })
  await page.goto('/#runner')
  await page.getByRole('link', { name: 'MCP', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'MCP для агентов' })).toBeVisible()
  await expect(page.getByLabel('Адрес MCP')).toHaveValue('https://infra-hub.minpor.ru/mcp')
  await page.getByRole('checkbox', { name: /^Настройка/ }).check()
  await expect(page.getByRole('checkbox', { name: /^Управление выпусками/ })).toBeChecked()
  await page.getByRole('checkbox', { name: 'Все проекты, включая будущие' }).uncheck()
  await page.getByRole('checkbox', { name: project.name, exact: true }).check()
  await page.getByRole('button', { name: 'Создать ключ', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Ключ создан' })).toBeVisible()
  await page.evaluate(() => { Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (value: string) => { (window as Window & { copied?: string }).copied = value } }, configurable: true }) })
  await page.getByRole('button', { name: 'Скопировать для Hermes' }).click()
  expect(await page.evaluate(() => (window as Window & { copied?: string }).copied)).toContain('Authorization: "Bearer ihm_test_only_not_a_real_credential"')
  await page.getByRole('button', { name: 'Готово, скрыть ключ' }).click()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Ключ создан' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отозвать ключ Hermes' })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/mcp-mobile.png', fullPage: true })
  await page.getByRole('button', { name: 'Отозвать ключ Hermes' }).click()
  await expect(page.getByText('Отозван', { exact: true })).toBeVisible()
})
