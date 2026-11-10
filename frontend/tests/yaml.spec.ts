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

test('YAML editor preserves drafts, validates and handles concurrent repository changes', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const app = { id: 'editor-app', name: 'Android production', projectId: 'editor-project', serverId: 's', kind: 'SCRIPT', configuration: {}, environmentKeys: [], currentDeployment: null }
  const pipeline = { id: 'editor-pipeline', application_id: app.id, ref: 'main', config_path: '.infra-hub/android.yaml', enabled: true, last_sha: 'a'.repeat(40) }
  let document = { sha: 'a'.repeat(40), path: pipeline.config_path, sha256: 'b'.repeat(64), yaml: '# Preserve my comments\nversion: 1\nname: Android\nbuild:\n  cache: true\n' }
  let conflict = true
  let saves = 0
  await page.route('**/api/applications', route => route.fulfill({ json: [app] }))
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: app.projectId, name: 'Android' }] }))
  await page.route('**/api/servers', route => route.fulfill({ json: [] }))
  await page.route('**/api/deployments', route => route.fulfill({ json: [] }))
  await page.route('**/api/builds', route => route.fulfill({ json: [] }))
  await page.route('**/api/pipelines', route => route.fulfill({ json: [pipeline] }))
  await page.route('**/api/deployment-yaml/document', route => route.fulfill({ json: document }))
  await page.route('**/api/deployment-yaml/validate', async route => {
    if (route.request().postDataJSON().yaml.includes('broken')) return route.fulfill({ status: 400, json: { message: 'Invalid deployment YAML' } })
    await route.fulfill({ json: { name: 'Android', build: { cache: false, steps: [{ name: 'Build APK' }] }, deploy: { steps: [], verify: [], rollback: [] } } })
  })
  await page.route('**/api/deployment-yaml/save', async route => {
    const input = route.request().postDataJSON()
    expect(input.expectedSha).toBe(document.sha)
    expect(input).toMatchObject({ applicationId: app.id, path: pipeline.config_path, ref: 'main' })
    saves++
    if (conflict) return route.fulfill({ status: 409, json: { message: 'Ветка изменилась. Загрузите YAML заново; ваши правки остаются в редакторе.' } })
    document = { ...document, sha: 'c'.repeat(40), yaml: input.yaml }
    await route.fulfill({ json: document })
  })
  await page.goto('/#applications')
  await page.getByLabel('Language / Язык').selectOption('en')
  await page.getByRole('button', { name: 'View YAML', exact: true }).click()
  const editor = page.getByRole('region', { name: 'YAML editor' })
  await expect(editor.getByLabel('YAML content')).toContainText('# Preserve my comments')
  await editor.getByRole('button', { name: 'Edit', exact: true }).click()
  const content = editor.getByRole('textbox', { name: 'YAML content' })
  await content.fill('broken: [')
  const save = editor.getByRole('button', { name: 'Save to main' })
  await expect(save).toBeDisabled()
  await editor.getByRole('button', { name: 'Validate YAML' }).click()
  await expect(editor.getByRole('alert')).toContainText('Invalid deployment YAML')
  await expect(save).toBeDisabled()
  const draft = document.yaml.replace('cache: true', 'cache: false')
  await content.fill(draft)
  await editor.getByRole('button', { name: 'View', exact: true }).click()
  await expect(editor.getByLabel('YAML content')).toContainText('cache: false')
  await editor.getByRole('button', { name: 'Edit', exact: true }).click()
  await expect(content).toHaveValue(draft)
  await expect(editor).toContainText('Automation is enabled')
  await editor.getByRole('button', { name: 'Validate YAML' }).click()
  await expect(editor).toContainText('Cache: disabled in YAML')
  await save.click()
  await expect(editor.getByRole('alert')).toContainText('The branch changed')
  await expect(content).toHaveValue(draft)
  conflict = false
  await save.click()
  await expect(editor.getByRole('status').filter({ hasText: 'New commit' })).toContainText('New commit: cccccccccccc')
  expect(saves).toBe(2)
  expect(document.yaml).toContain('# Preserve my comments')
  await expect(editor.getByLabel('YAML content')).toContainText('cache: false')
  await editor.getByRole('button', { name: 'Edit', exact: true }).click()
  await content.fill(draft + '# unsaved\n')
  page.once('dialog', dialog => dialog.dismiss())
  await editor.getByRole('button', { name: 'Close', exact: true }).click()
  await expect(content).toHaveValue(draft + '# unsaved\n')
  await page.screenshot({ path: '../.local/checks/yaml-editor-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/yaml-editor-mobile.png', fullPage: true })
  expect(errors).toEqual([])
})
