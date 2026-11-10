import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, readOnly = false) {
  const state = { command: 'python3 /opt/hub/build-aukni-web.py', artifactPath: 'backend/app.jar', sha: 'a'.repeat(40), files: new Map([['.infra-hub/production.yaml', 'version: 1\nname: Native example\n'], ['.infra-hub/scripts/existing.sh', 'echo previous\n']]) }
  const sourceFiles = [{ path: '/opt/hub/build-aukni-web.py', origin: 'BUILDER', stage: 'build' }, ...[...state.files.keys()].map(path => ({ path, origin: 'REPOSITORY', stage: 'source' }))]
  await page.route('**/api/session', route => route.fulfill({ json: { login: 'owner' } }))
  await page.route('**/api/csrf', route => route.fulfill({ status: 404, json: {} }))
  await page.route('**/api/projects', route => route.fulfill({ json: [{ id: 'p', name: 'Example', repositoryUrl: 'https://github.com/owner/example' }] }))
  await page.route('**/api/builds', route => route.fulfill({ json: [] }))
  await page.route('**/api/overview', route => route.fulfill({ json: { projects: 1, running: 0, queued: 0, buildMillis: 0, deploymentMillis: 0 } }))
  await page.route('**/api/applications', route => route.fulfill({ json: [{ id: 'app', projectId: 'p', serverId: 's', name: 'Example prod' }] }))
  await page.route('**/api/servers', route => route.fulfill({ json: [{ id: 's', name: 'prod-01' }] }))
  await page.route('**/api/pipelines', route => route.fulfill({ json: [{ id: 'pipeline', application_id: 'app', ref: 'main', environment: 'prod', enabled: true, config_path: null }] }))
  await page.route('**/api/pipelines/pipeline/configuration', route => route.fulfill({ json: { yaml: `build:\n  command: ${state.command}\ndeploy:\n  server: prod-01\n`, repositoryYaml: null, ref: 'main', files: sourceFiles, command: state.command, artifactPath: state.artifactPath } }))
  await page.route('**/api/pipelines/pipeline/source-base', route => route.fulfill({ json: { sha: state.sha } }))
  await page.route('**/api/pipelines/pipeline/source-access', route => route.fulfill({ json: { contentsWrite: !readOnly, workflowsWrite: !readOnly, settingsUrl: 'https://github.com/settings/apps/example/permissions', appName: 'Example App' } }))
  await page.route('**/api/pipelines/pipeline/repository-files', route => route.fulfill({ json: { sha: state.sha, truncated: false, files: [...state.files.keys()].map(path => ({ path, origin: 'REPOSITORY', stage: 'source' })) } }))
  await page.route('**/api/pipelines/pipeline/source?*', route => {
    const q = new URL(route.request().url()).searchParams
    const path = q.get('path')!
    const origin = q.get('origin')!
    return route.fulfill({ json: { path, origin, content: origin === 'BUILDER' ? '# builder source\nprint("build")\n' : state.files.get(path), sha: origin === 'BUILDER' ? null : state.sha, sha256: 'b'.repeat(64), references: [] } })
  })
  await page.route('**/api/pipelines/pipeline/source/validate', route => route.fulfill({ json: { valid: true } }))
  await page.goto('/')
  await page.getByLabel('Language / Язык').selectOption('en')
  await page.getByRole('button', { name: 'Open project' }).click()
  await expect(page.getByLabel('Configuration contents')).toContainText('prod-01')
  return state
}

test('new scripts are created on the displayed branch, remain in the repository list, and can become the build command', async ({ page }) => {
  const state = await setup(page)
  const changes: unknown[] = []
  await page.route('**/api/pipelines/pipeline/source/save', route => {
    const input = route.request().postDataJSON()
    expect(input).toMatchObject({ create: true, expectedSha: 'a'.repeat(40), path: '.infra-hub/scripts/custom.sh' })
    changes.push(input)
    state.files.set(input.path, input.content); state.sha = 'c'.repeat(40)
    return route.fulfill({ json: { path: input.path, origin: 'REPOSITORY', content: input.content, sha: state.sha, sha256: 'd'.repeat(64), references: [] } })
  })
  await page.getByRole('button', { name: '+ New file' }).click()
  const editor = page.getByRole('region', { name: 'File editor' })
  await editor.getByLabel('File contents').fill('#!/bin/sh\necho custom\n')
  const create = editor.getByRole('button', { name: 'Create on main' })
  await expect(create).toBeDisabled()
  await editor.getByRole('button', { name: 'Validate file' }).click()
  await expect(create).toBeEnabled()
  await page.getByLabel('Language / Язык').selectOption('ru')
  await page.getByRole('region', { name: 'Редактор файла' }).screenshot({ path: '../.local/checks/source-editor-desktop.png' })
  await page.getByLabel('Language / Язык').selectOption('en')
  await create.click()
  await expect(editor).toHaveCount(0)
  await expect(page.getByRole('status').filter({ hasText: 'File saved to main' })).toBeVisible()
  await expect(page.getByLabel('Configuration contents')).toContainText('echo custom')
  await page.route('**/api/pipelines/pipeline/build-settings', route => {
    const input = route.request().postDataJSON()
    expect(input).toMatchObject({ expectedCommand: state.command, expectedArtifactPath: state.artifactPath, command: 'bash .infra-hub/scripts/custom.sh' })
    state.command = input.command; return route.fulfill({ json: {} })
  })
  await page.getByRole('button', { name: 'Use for build' }).click()
  const buildEditor = page.getByRole('form', { name: 'Build settings editor' })
  await expect(buildEditor.getByLabel('Build command')).toHaveValue('bash .infra-hub/scripts/custom.sh')
  await buildEditor.getByRole('button', { name: 'Save settings' }).click()
  await expect(page.getByLabel('Configuration contents')).toContainText('bash .infra-hub/scripts/custom.sh')
  expect(changes).toHaveLength(1)
  await page.reload(); await page.getByRole('button', { name: 'Open project' }).click()
  await page.getByRole('button', { name: 'Open repository file' }).click()
  await page.getByRole('combobox', { name: 'Repository files' }).selectOption('.infra-hub/scripts/custom.sh')
  await expect(page.getByLabel('Configuration contents')).toContainText('echo custom')
})

test('repository YAML editing preserves a draft on conflicts and requires revalidation after changes', async ({ page }) => {
  await setup(page)
  await page.getByRole('button', { name: /production.yaml/ }).click()
  await page.getByRole('button', { name: 'Edit file', exact: true }).click()
  const editor = page.getByRole('region', { name: 'File editor' })
  const content = editor.getByLabel('File contents')
  await content.fill('version: 1\nname: Updated\n')
  const save = editor.getByRole('button', { name: 'Save to main' })
  await editor.getByRole('button', { name: 'Validate file' }).click()
  await content.fill('version: 1\nname: Updated again\n')
  await expect(save).toBeDisabled()
  await editor.getByRole('button', { name: 'Validate file' }).click()
  await page.route('**/api/pipelines/pipeline/source/save', route => {
    expect(route.request().postDataJSON()).toMatchObject({ create: false, expectedSha: 'a'.repeat(40) })
    return route.fulfill({ status: 409, json: { message: 'The branch changed' } })
  })
  await save.click()
  await expect(editor.getByRole('alert')).toContainText('The branch changed')
  await expect(content).toHaveValue('version: 1\nname: Updated again\n')
  page.once('dialog', dialog => dialog.dismiss())
  await editor.getByRole('button', { name: 'Close', exact: true }).click()
  await expect(content).toHaveValue('version: 1\nname: Updated again\n')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/checks/source-editor-mobile.png', fullPage: true })
})

test('a shared platform helper is copied into the repository before it can be edited', async ({ page }) => {
  await setup(page)
  await page.getByRole('button', { name: /build-aukni-web.py/ }).click()
  await page.getByRole('button', { name: 'Copy to project' }).click()
  const editor = page.getByRole('region', { name: 'File editor' })
  await expect(editor.getByLabel('New file path')).toHaveValue('.infra-hub/scripts/build-aukni-web.py')
  await expect(editor.getByLabel('File contents')).toHaveValue('# builder source\nprint("build")\n')
})

test('read-only GitHub App access explains the required permission and prevents a futile save', async ({ page }) => {
  await setup(page, true)
  await page.getByRole('button', { name: '+ New file' }).click()
  const editor = page.getByRole('region', { name: 'File editor' })
  await expect(editor).toContainText('Saving files requires Contents: write')
  await expect(editor.getByRole('link', { name: 'Permissions for Example App' })).toHaveAttribute('href', 'https://github.com/settings/apps/example/permissions')
  await editor.getByRole('button', { name: 'Validate file' }).click()
  await expect(editor.getByRole('button', { name: 'Create on main' })).toBeDisabled()
})
