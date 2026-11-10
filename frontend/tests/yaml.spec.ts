import { test, expect } from '@playwright/test'

test('attach two YAML files, preview exact SHA and explicitly release a paused binding', async ({ page }) => {
  const app = { id: 'app-yaml', projectId: 'project-yaml', serverId: 'server-yaml', name: 'Generic app', kind: 'SCRIPT', configuration: {}, environmentKeys: [], currentDeployment: null }
  let pipelines: object[] = []
  let released = false
  await page.route('**/api/applications', route => route.fulfill({ json: [app] }))
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: app.projectId, name: 'Project' }] }))
  await page.route('**/api/servers', route => route.fulfill({ json: [{ id: app.serverId, name: 'Target', status: 'SSH_ONLY' }] }))
  await page.route('**/api/deployments', route => route.fulfill({ json: [] }))
  await page.route('**/api/builds', route => route.fulfill({ json: [] }))
  await page.route('**/api/pipelines', route => route.fulfill({ json: pipelines }))
  await page.route('**/api/deployment-yaml/preview', async route => {
    const input = route.request().postDataJSON()
    expect(input.applicationId).toBe(app.id)
    await route.fulfill({ json: { sha: 'a'.repeat(40), path: input.path, sha256: 'b'.repeat(64), yaml: `version: 1\nname: ${input.path}`, definition: { name: 'Release', build: { steps: [{ name: 'Test' }], artifact: null }, deploy: { directory: '/srv/app', steps: [{ name: 'Deploy' }], verify: [{ name: 'Health' }], rollback: [] } } } })
  })
  await page.route('**/api/deployment-yaml', async route => {
    const input = route.request().postDataJSON()
    expect(input.expectedSha).toBe('a'.repeat(40))
    expect(input.enabled).toBe(false)
    const pipeline = { id: input.id, application_id: app.id, ref: input.ref, config_path: input.path, enabled: false, build_id: null }
    pipelines.push(pipeline)
    await route.fulfill({ json: pipeline })
  })
  await page.route('**/api/pipelines/*/release', async route => {
    expect(route.request().postDataJSON().requestId).toMatch(/^[a-f0-9-]{36}$/)
    released = true
    await route.fulfill({ json: { id: 'new-build' } })
  })
  await page.goto('/#applications')
  await page.getByLabel('Language / Язык').selectOption('en')
  const panel = page.getByRole('region', { name: 'YAML deployments' })
  for (const path of ['.infra-hub/dev.yaml', '.infra-hub/production.yaml']) {
    await panel.getByRole('button', { name: 'Attach YAML', exact: true }).click()
    await panel.getByLabel('YAML application').selectOption(app.id)
    await panel.getByLabel('Repository YAML path').fill(path)
    await panel.getByRole('button', { name: 'Validate YAML' }).click()
    await expect(panel.locator('.yaml-preview')).toContainText('aaaaaaaaaaaa')
    await panel.getByRole('button', { name: 'Save YAML binding' }).click()
    await expect(panel.locator('.yaml-binding').filter({ hasText: path })).toBeVisible()
  }
  await expect(panel.locator('.yaml-binding')).toHaveCount(2)
  await panel.locator('.yaml-binding').filter({ hasText: '.infra-hub/dev.yaml' }).getByRole('button', { name: 'Run YAML release' }).click()
  await expect.poll(() => released).toBe(true)
  await page.screenshot({ path: '../.local/checks/yaml-desktop.png', fullPage: true })
  await page.getByLabel('Language / Язык').selectOption('ru')
  await expect(page.getByRole('heading', { name: 'Деплой через YAML' })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/yaml-mobile.png', fullPage: true })
})

test('script application creation accepts a pinned SSH-only server without container ports', async ({ page }) => {
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: 'p', name: 'Project' }] }))
  await page.route('**/api/servers', route => route.fulfill({ json: [{ id: 's', name: 'SSH target', status: 'SSH_ONLY' }] }))
  let created = false
  await page.route('**/api/applications', async route => {
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toMatchObject({ kind: 'SCRIPT', hostPort: 0, projectId: 'p', serverId: 's' })
      created = true
      await route.fulfill({ json: {} })
    } else await route.fulfill({ json: [] })
  })
  await page.goto('/#applications')
  await page.getByRole('button', { name: '＋ Новое приложение' }).click()
  await page.getByLabel('Способ деплоя').selectOption('SCRIPT')
  await page.getByLabel('Название приложения').fill('Generic release')
  await page.getByRole('combobox', { name: 'Проект', exact: true }).selectOption('p')
  await page.getByLabel('Сервер приложения').selectOption('s')
  await expect(page.getByLabel('Порт на сервере')).toHaveCount(0)
  await page.getByRole('button', { name: 'Создать приложение', exact: true }).click()
  await expect.poll(() => created).toBe(true)
})
