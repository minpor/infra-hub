import { test, expect } from '@playwright/test'

test('project resources and cache override defaults and can return to inheritance', async ({ page }) => {
  const defaults = { cpus: 4, memoryGiB: 8, dependencyCacheEnabled: true, maxCpus: 20, maxMemoryGiB: 18, inherited: false }
  const overrides = new Map<string, typeof defaults>()
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: 'android', name: 'Android app' }, { id: 'api', name: 'Small API' }] }))
  await page.route('**/api/runner-settings', route => route.fulfill({ json: defaults }))
  await page.route(/\/api\/projects\/[^/]+\/runner-settings(?:\/reset)?$/, async route => {
    const id = route.request().url().split('/projects/')[1].split('/')[0]
    if (route.request().method() === 'POST') {
      if (route.request().url().endsWith('/reset')) overrides.delete(id)
      else overrides.set(id, { ...defaults, ...route.request().postDataJSON(), inherited: false })
    }
    await route.fulfill({ json: overrides.get(id) || { ...defaults, inherited: true } })
  })
  await page.goto('/#runner')
  await page.getByLabel('Language / Язык').selectOption('en')
  await page.getByLabel('Settings for').selectOption('android')
  await expect(page.getByText('Inherits defaults', { exact: true })).toBeVisible()
  await page.getByLabel('CPU', { exact: true }).fill('12')
  await page.getByLabel('Memory, GiB').fill('18')
  await page.getByRole('button', { name: 'Save settings' }).click()
  await expect(page.getByRole('status')).toContainText('12 CPU')
  await page.getByLabel('Settings for').selectOption('api')
  await expect(page.getByLabel('CPU', { exact: true })).toHaveValue('4')
  await page.getByLabel('CPU', { exact: true }).fill('2')
  await page.getByLabel('Memory, GiB').fill('4')
  await page.getByRole('switch', { name: 'Dependency cache' }).uncheck()
  await page.getByRole('button', { name: 'Save settings' }).click()
  await expect(page.getByRole('status')).toContainText('2 CPU')
  await page.getByLabel('Settings for').selectOption('android')
  await expect(page.getByLabel('CPU', { exact: true })).toHaveValue('12')
  await expect(page.getByRole('switch', { name: 'Dependency cache' })).toBeChecked()
  await page.getByRole('button', { name: 'Use global defaults' }).click()
  await expect(page.getByLabel('CPU', { exact: true })).toHaveValue('4')
  await expect(page.getByText('Inherits defaults', { exact: true })).toBeVisible()
  await page.getByLabel('Settings for').selectOption('api')
  await expect(page.getByLabel('CPU', { exact: true })).toHaveValue('2')
  await expect(page.getByRole('switch', { name: 'Dependency cache' })).not.toBeChecked()
  await page.screenshot({ path: '../.local/checks/project-runner-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/project-runner-mobile.png', fullPage: true })
})

test('build details expose actual resources, cache state and immutable YAML', async ({ page }) => {
  const id = '11111111-1111-1111-1111-111111111111'
  const build = { id, projectId: 'project', status: 'SUCCEEDED', recipe: 'REPOSITORY_V1', createdAt: new Date().toISOString(), startedAt: null, finishedAt: null, message: null, image: null, source: { repository: 'owner/app', ref: 'main', sha: 'a'.repeat(40), yamlRecipe: { path: '.infra-hub/android.yaml', sha256: 'b'.repeat(64), yaml: '# frozen YAML\nbuild:\n  cache: true' } }, runnerExecution: { cpus: 12, memoryGiB: 18, cacheStatus: 'AVAILABLE', cacheVolume: 'infra-hub-dependencies-v1-project' } }
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: 'project', name: 'Android' }] }))
  await page.route('**/api/builds', route => route.fulfill({ json: [build] }))
  await page.route(`**/api/builds/${id}`, route => route.fulfill({ json: { build, steps: [] } }))
  await page.route(`**/api/builds/${id}/logs?after=0`, route => route.fulfill({ json: [] }))
  await page.goto(`/#builds/${id}`)
  await page.getByLabel('Language / Язык').selectOption('en')
  const detail = page.getByRole('region', { name: 'Selected build' })
  await expect(detail).toContainText('12 CPU · 18 GiB')
  await expect(detail).toContainText('Connected · saved files available')
  await expect(detail).toContainText('infra-hub-dependencies-v1-project')
  await detail.getByText('View this build’s YAML').click()
  await expect(detail).toContainText('# frozen YAML')
  for (const [state, label] of [['DISABLED', 'Disabled · clean build'], ['EMPTY', 'Empty · first run']]) {
    build.runnerExecution.cacheStatus = state
    await page.reload()
    await expect(detail).toContainText(label)
  }
})
