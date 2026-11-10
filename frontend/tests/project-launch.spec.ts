import { test, expect } from '@playwright/test'

const project = { id: 'launch-project', name: 'Orbit', repositoryUrl: 'https://github.com/owner/orbit' }
const apps = [
  { id: 'dev-web', projectId: project.id, serverId: 'dev-server', name: 'Orbit development' },
  { id: 'prod-web', projectId: project.id, serverId: 'prod-server', name: 'Orbit production' },
  { id: 'prod-mobile', projectId: project.id, serverId: 'prod-server', name: 'Android production' }
]
const pipelines = [
  { id: 'dev-pipeline', application_id: 'dev-web', ref: 'develop', environment: 'dev', enabled: false, config_path: '.infra-hub/dev.yaml' },
  { id: 'prod-pipeline', application_id: 'prod-web', ref: 'trunk', environment: 'prod', enabled: false },
  { id: 'mobile-pipeline', application_id: 'prod-mobile', ref: 'trunk', environment: 'prod', enabled: true, config_path: '.infra-hub/android.yaml' }
]

test.beforeEach(async ({ page }) => {
  await page.route('**/api/csrf', route => route.fulfill({ status: 404, json: {} }))
  await page.route('**/api/session', route => route.fulfill({ json: { login: 'owner' } }))
  await page.route('**/api/projects', route => route.fulfill({ json: [project] }))
  await page.route('**/api/builds', route => route.fulfill({ json: [] }))
  await page.route('**/api/overview', route => route.fulfill({ json: { projects: 1, running: 0, queued: 0, buildMillis: 0, deploymentMillis: 0 } }))
  await page.route('**/api/applications', route => route.fulfill({ json: apps }))
  await page.route('**/api/pipelines', route => route.fulfill({ json: pipelines }))
  await page.route('**/api/servers', route => route.fulfill({ json: [{ id: 'dev-server', name: 'dev-01' }, { id: 'prod-server', name: 'prod-01' }] }))
  await page.route('**/api/projects/*/branches', route => route.fulfill({ json: { defaultBranch: 'trunk', branches: ['trunk', 'develop', 'feature/new-ui'].map(name => ({ name, sha: 'a'.repeat(40) })), hasMore: false } }))
  await page.goto('/')
  await page.getByLabel('Language / Язык').selectOption('en')
  await page.getByRole('button', { name: 'Open project' }).click()
  await page.getByRole('tab', { name: 'Run', exact: true }).click()
})

test('environment selects the matching branch and target and mismatched branches cannot deploy', async ({ page }) => {
  const launch = page.getByRole('region', { name: 'Project launch' })
  await expect(launch.getByRole('textbox', { name: 'Build command' })).not.toBeVisible()
  await launch.getByRole('radio', { name: /DEV/ }).check()
  await expect(launch.getByRole('combobox', { name: 'Branch', exact: true })).toHaveValue('develop')
  await expect(launch.getByLabel('Run summary')).toContainText('Orbit development → dev-01')
  await launch.getByRole('combobox', { name: 'Branch', exact: true }).selectOption('feature/new-ui')
  await expect(launch.getByRole('button', { name: 'Build and release to DEV' })).toBeDisabled()
  await expect(launch).toContainText('No pipeline for this environment and branch yet')
  await launch.getByRole('radio', { name: /PROD/ }).check()
  await expect(launch.getByRole('combobox', { name: 'Branch', exact: true })).toHaveValue('trunk')
  await expect(launch.getByLabel('Run summary')).toContainText('Orbit production → prod-01')
  await launch.getByLabel('Application and pipeline').selectOption('mobile-pipeline')
  await expect(launch.getByLabel('Run summary')).toContainText('Android production → prod-01')
  await page.screenshot({ path: '../.local/checks/project-launch-desktop.png', fullPage: true })
  await page.getByLabel('Language / Язык').selectOption('ru')
  const russianLaunch = page.getByRole('region', { name: 'Запуск проекта' })
  await russianLaunch.screenshot({ path: '../.local/checks/project-launch-panel.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/project-launch-mobile.png', fullPage: true })
  let requested = false
  await page.route('**/api/pipelines/mobile-pipeline/release', async route => {
    expect(route.request().postDataJSON()).toMatchObject({ expectedRef: 'trunk', expectedEnvironment: 'prod' })
    requested = true
    await route.fulfill({ status: 409, json: { message: 'Сценарий изменился. Откройте запуск заново и проверьте окружение и ветку.' } })
  })
  await russianLaunch.getByRole('button', { name: 'Собрать и выпустить в PROD' }).click()
  await expect.poll(() => requested).toBe(true)
  await expect(russianLaunch.getByRole('alert')).toContainText('Сценарий изменился')
})

test('release retries retain the request ID and paused pipelines can release once', async ({ page }) => {
  const launch = page.getByRole('region', { name: 'Project launch' })
  const requests: string[] = []
  await page.route('**/api/pipelines/dev-pipeline/release', async route => {
    const input = route.request().postDataJSON()
    expect(input).toMatchObject({ expectedRef: 'develop', expectedEnvironment: 'dev' })
    requests.push(input.requestId)
    await route.fulfill({ status: 502, json: { message: 'Try again' } })
  })
  await launch.getByRole('radio', { name: /DEV/ }).check()
  await expect(launch.getByLabel('Run summary')).toContainText('One-time release')
  const run = launch.getByRole('button', { name: 'Build and release to DEV' })
  await run.click()
  await expect(launch.getByRole('alert')).toContainText('Try again')
  await run.click()
  await expect.poll(() => requests.length).toBe(2)
  expect(requests[0]).toMatch(/^[a-f0-9-]{36}$/)
  expect(requests[1]).toBe(requests[0])
})

test('successful YAML release opens its exact build without enabling automation', async ({ page }) => {
  const id = '22222222-2222-2222-2222-222222222222'
  const build = { id, projectId: project.id, status: 'QUEUED', createdAt: new Date().toISOString(), source: { repository: 'owner/orbit', ref: 'develop', sha: 'a'.repeat(40), command: 'npm run build', yamlRecipe: { path: '.infra-hub/dev.yaml', sha256: 'b'.repeat(64) } } }
  const mutations: string[] = []
  page.on('request', request => { if (request.method() === 'POST') mutations.push(request.url()) })
  await page.route('**/api/pipelines/dev-pipeline/release', route => route.fulfill({ json: build }))
  await page.route(`**/api/builds/${id}`, route => route.fulfill({ json: { build, steps: [] } }))
  await page.route(`**/api/builds/${id}/logs?after=0`, route => route.fulfill({ json: [] }))
  const launch = page.getByRole('region', { name: 'Project launch' })
  await launch.getByRole('radio', { name: /DEV/ }).check()
  await launch.getByRole('button', { name: 'Build and release to DEV' }).click()
  await expect(launch).toHaveCount(0)
  const detail = page.getByRole('region', { name: 'Selected build' })
  await expect(detail).toContainText('owner/orbit · develop · aaaaaaaaaaaa')
  await expect(detail).toContainText('.infra-hub/dev.yaml')
  expect(mutations.filter(url => url.includes('/pipelines/'))).toEqual([expect.stringContaining('/pipelines/dev-pipeline/release')])
})

test('branch pagination preserves configured refs and loads the next page', async ({ page }) => {
  const launch = page.getByRole('region', { name: 'Project launch' })
  await page.route('**/api/projects/*/branches', route => route.fulfill({ json: { defaultBranch: 'trunk', branches: Array.from({ length: 100 }, (_, i) => ({ name: `branch-${i}`, sha: 'a'.repeat(40) })), hasMore: true } }))
  await page.route('**/api/projects/*/branches?page=2', route => route.fulfill({ json: { defaultBranch: 'trunk', branches: [{ name: 'feature/last', sha: 'b'.repeat(40) }], hasMore: false } }))
  await page.reload()
  await page.getByRole('button', { name: 'Open project' }).click()
  await page.getByRole('tab', { name: 'Run', exact: true }).click()
  await launch.getByRole('button', { name: 'More branches' }).click()
  await launch.getByRole('combobox', { name: 'Branch', exact: true }).selectOption('feature/last')
  await expect(launch.getByRole('combobox', { name: 'Branch', exact: true })).toHaveValue('feature/last')
  await expect(launch.getByRole('button', { name: 'More branches' })).toHaveCount(0)
})

test('branch failure supports manual refs and build-only does not invoke a deployment', async ({ page }) => {
  await page.route('**/api/projects/*/branches', route => route.fulfill({ status: 502, json: {} }))
  await page.reload()
  await page.getByRole('button', { name: 'Open project' }).click()
  await page.getByRole('tab', { name: 'Run', exact: true }).click()
  const launch = page.getByRole('region', { name: 'Project launch' })
  await expect(launch).toContainText('Could not load branches')
  await launch.getByRole('button', { name: 'Enter manually' }).click()
  await launch.getByLabel('Branch, tag or commit').fill('release/v2')
  await launch.getByText('Build settings', { exact: true }).click()
  await launch.getByLabel('Build command').fill('npm ci && npm run build')
  let built = false
  await page.route('**/api/projects/*/builds', async route => {
    expect(route.request().postDataJSON()).toMatchObject({ ref: 'release/v2', command: 'npm ci && npm run build', artifactPath: null })
    built = true
    await route.fulfill({ status: 502, json: { message: 'Build request received' } })
  })
  await launch.getByRole('button', { name: 'Queue build' }).click()
  await expect.poll(() => built).toBe(true)
})

test('opening a project shows YAML and script contents first and can move to the selected launch', async ({ page }) => {
  const snapshot = 'b'.repeat(40)
  const yaml = 'version: 1\nname: Orbit dev\nbuild:\n  steps:\n    - name: Build\n      run: npm run build\ndeploy:\n  directory: /srv/orbit\n  steps:\n    - name: Deploy\n      run: bash deploy/release.sh\n'
  const files = [{ origin: 'REPOSITORY', path: '.infra-hub/dev.yaml', stage: 'configuration' }]
  await page.route('**/api/pipelines/dev-pipeline/configuration', route => route.fulfill({ json: { yaml: null, repositoryYaml: '.infra-hub/dev.yaml', ref: 'develop', files } }))
  const inspected: string[] = []
  await page.route('**/api/pipelines/dev-pipeline/source?*', route => {
    const params = new URL(route.request().url()).searchParams
    const path = params.get('path')!
    inspected.push(path)
    if (path === 'deploy/release.sh') expect(params.get('sha')).toBe(snapshot)
    return route.fulfill({ json: { path, origin: 'REPOSITORY', content: path.endsWith('.yaml') ? yaml : 'docker compose up -d app\n', sha: snapshot, sha256: 'c'.repeat(64), references: path.endsWith('.yaml') ? [{ origin: 'REPOSITORY', path: 'deploy/release.sh', stage: 'source' }] : [] } })
  })
  await page.reload()
  await page.getByRole('button', { name: 'Open project' }).click()
  const settings = page.getByRole('region', { name: 'Settings & scripts' })
  await expect(page.getByRole('tab', { name: 'Settings & scripts' })).toHaveAttribute('aria-selected', 'true')
  await expect(settings.getByLabel('Configuration contents')).toContainText('npm run build')
  await expect(settings.getByLabel('Configuration contents')).toContainText('/srv/orbit')
  await settings.getByRole('button', { name: /release.sh/ }).click()
  await expect(settings.getByLabel('Configuration contents')).toContainText('docker compose up -d app')
  expect(inspected).toEqual(['.infra-hub/dev.yaml', 'deploy/release.sh'])
  await settings.getByRole('button', { name: 'Go to launch' }).click()
  await expect(page.getByRole('radio', { name: /DEV/ })).toBeChecked()
  await expect(page.getByRole('combobox', { name: 'Branch', exact: true })).toHaveValue('develop')
})

test('native settings show their complete YAML representation and the called build and deployment scripts', async ({ page }) => {
  const files = [{ origin: 'BUILDER', path: '/opt/hub/build-aukni-web.py', stage: 'build' }, { origin: 'DEPLOYER', path: 'deploy/apply-compose.py', stage: 'deploy' }]
  await page.route('**/api/pipelines/prod-pipeline/configuration', route => route.fulfill({ json: { yaml: 'name: Orbit production\nbranch: trunk\nbuild:\n  command: python3 /opt/hub/build-aukni-web.py\ndeploy:\n  server: prod-01\n  settings:\n    root: /opt/orbit\n', repositoryYaml: null, ref: 'trunk', files } }))
  await page.route('**/api/pipelines/prod-pipeline/source?*', route => {
    const params = new URL(route.request().url()).searchParams
    const path = params.get('path')!
    return route.fulfill({ json: { path, origin: params.get('origin'), content: path.endsWith('build-aukni-web.py') ? "workflow = yaml.safe_load(Path('.github/workflows/ci.yml').read_text())\n" : "subprocess.run(['docker', 'compose', 'up', '-d'])\n", sha: null, sha256: 'd'.repeat(64), references: [] } })
  })
  await page.getByRole('tab', { name: 'Settings & scripts' }).click()
  const settings = page.getByRole('region', { name: 'Settings & scripts' })
  await settings.getByRole('combobox', { name: 'Deployment pipeline' }).selectOption('prod-pipeline')
  await expect(settings).toContainText('No repository YAML is attached')
  await expect(settings.getByLabel('Configuration contents')).toContainText('/opt/orbit')
  await settings.getByRole('button', { name: /build-aukni-web.py/ }).click()
  await expect(settings.getByLabel('Configuration contents')).toContainText('.github/workflows/ci.yml')
  await settings.getByRole('button', { name: /apply-compose.py/ }).click()
  await expect(settings.getByLabel('Configuration contents')).toContainText('subprocess.run')
  await page.getByLabel('Language / Язык').selectOption('ru')
  await page.getByRole('region', { name: 'Запуск проекта' }).screenshot({ path: '../.local/checks/configuration-desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/configuration-mobile.png', fullPage: true })
})
