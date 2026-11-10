import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { t, useLanguage } from './i18n'

export type SavedSource = { path: string; origin: 'REPOSITORY'; content: string; sha256: string; sha: string; references: { path: string; origin: 'REPOSITORY'; stage: string }[] }
export type SourceDraft = { path: string; content: string; sha: string | null; create: boolean }
const shellTemplate = '#!/usr/bin/env bash\nset -euo pipefail\n\n# Add your build or deployment commands here.\n'
const yamlTemplate = 'version: 1\nname: Custom release\nbuild:\n  steps:\n    - name: Build\n      run: bash .infra-hub/scripts/build.sh\ndeploy:\n  directory: /opt/your-app\n  steps:\n    - name: Deploy\n      run: |\n        echo "Configure deployment commands first" >&2\n        exit 1\n  verify:\n    - name: Verify\n      run: |\n        echo "Configure a health check first" >&2\n        exit 1\n'

export function SourceFileEditor({ pipelineId, branch, automated, initial, onClose, onSaved }: {
  pipelineId: string; branch: string; automated: boolean; initial: SourceDraft; onClose: () => void; onSaved: (source: SavedSource) => void
}) {
  useLanguage()
  const [path, setPath] = useState(initial.path || '.infra-hub/scripts/custom.sh')
  const [content, setContent] = useState(initial.content || shellTemplate)
  const [sha, setSha] = useState(initial.sha)
  const [busy, setBusy] = useState(!initial.sha)
  const [error, setError] = useState('')
  const [validated, setValidated] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [accessAttempt, setAccessAttempt] = useState(0)
  const [access, setAccess] = useState<{ contentsWrite: boolean; workflowsWrite: boolean; settingsUrl: string; appName: string } | null>(null)
  const [accessError, setAccessError] = useState('')
  const element = useRef<HTMLElement>(null)
  const fingerprint = JSON.stringify([path, content])
  const dirty = initial.create || content !== initial.content
  const canWrite = access?.contentsWrite && (!path.startsWith('.github/workflows/') || access.workflowsWrite)
  useEffect(() => {
    let stopped = false
    setAccess(null); setAccessError('')
    api<NonNullable<typeof access>>(`/pipelines/${pipelineId}/source-access`).then(value => { if (!stopped) setAccess(value) })
      .catch(() => { if (!stopped) setAccessError('Не удалось проверить право записи GitHub App.') })
    return () => { stopped = true }
  }, [pipelineId, accessAttempt])
  useEffect(() => {
    if (initial.sha) return
    let stopped = false
    setBusy(true); setError('')
    api<{ sha: string }>(`/pipelines/${pipelineId}/source-base`).then(value => { if (!stopped) setSha(value.sha) })
      .catch(e => { if (!stopped) setError((e as Error).message) }).finally(() => { if (!stopped) setBusy(false) })
    return () => { stopped = true }
  }, [pipelineId, initial.sha, attempt])
  useEffect(() => { element.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }) }, [])
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  async function action(task: () => Promise<void>) {
    setBusy(true); setError('')
    try { await task() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }
  return <section className="source-editor yaml-editor" aria-label={t('Редактор файла')} ref={element}>
    <div className="yaml-editor-heading"><div><span className="eyebrow">{t(initial.create ? 'НОВЫЙ ФАЙЛ В РЕПОЗИТОРИИ' : 'РЕДАКТИРОВАНИЕ ФАЙЛА')}</span><h3>{initial.create ? t('Новый скрипт или YAML') : path}</h3><p className="muted">{branch} · {sha?.slice(0, 12) || t('Загружаем ветку…')}</p></div><button className="secondary" type="button" disabled={busy} onClick={() => { if (!dirty || window.confirm(t('Отбросить несохранённые изменения файла?'))) onClose() }}>{t('Закрыть')}</button></div>
    {initial.create && <div className="source-new-fields"><label>{t('Шаблон файла')}<select disabled={busy} defaultValue="" onChange={e => {
      const type = e.target.value
      if (type === 'shell') { setPath('.infra-hub/scripts/custom.sh'); setContent(shellTemplate) }
      if (type === 'python') { setPath('.infra-hub/scripts/custom.py'); setContent('#!/usr/bin/env python3\n\n# Add your build or deployment commands here.\n') }
      if (type === 'node') { setPath('.infra-hub/scripts/custom.mjs'); setContent('// Add your build or deployment commands here.\n') }
      if (type === 'yaml') { setPath('.infra-hub/release.yaml'); setContent(yamlTemplate) }
      setValidated('')
    }}><option value="" disabled>{t('Выберите при необходимости')}</option><option value="shell">Shell · .sh</option><option value="python">Python · .py</option><option value="node">Node.js · .mjs</option><option value="yaml">YAML · .yaml</option></select></label><label>{t('Путь нового файла')}<input required maxLength={300} value={path} disabled={busy} onChange={e => { setPath(e.target.value); setValidated('') }} placeholder=".infra-hub/scripts/custom.sh"/></label></div>}
    {error && <p className="error" role="alert">{t(error)}</p>}
    {(access && !canWrite || accessError) && <div className="source-access note" role="status"><p>{accessError ? t(accessError) : t(!access!.contentsWrite ? 'GitHub App сейчас имеет только чтение. Для сохранения файлов нужно Contents: write.' : 'Для редактирования .github/workflows нужно право GitHub App Workflows: write.')}</p>{access && <a href={access.settingsUrl} target="_blank" rel="noreferrer">{t('Права приложения {0}', [access.appName])} ↗</a>}<button type="button" className="text-button" onClick={() => setAccessAttempt(accessAttempt + 1)}>{t('Проверить права снова')}</button></div>}
    <label className="yaml-code-label"><span className="sr-only">{t('Содержимое файла')}</span><textarea className="yaml-code" aria-label={t('Содержимое файла')} value={content} disabled={busy} rows={22} spellCheck={false} autoCapitalize="off" autoCorrect="off" onChange={e => { setContent(e.target.value); setValidated('') }} onKeyDown={e => {
      if (e.key === 'Tab') { e.preventDefault(); const input = e.currentTarget, start = input.selectionStart, end = input.selectionEnd; setContent(content.slice(0, start) + '  ' + content.slice(end)); setValidated(''); requestAnimationFrame(() => { input.selectionStart = input.selectionEnd = start + 2 }) }
    }}/></label>
    <div className="yaml-code-footer"><span>{new TextEncoder().encode(content).length.toLocaleString()} / 131,072 {t('байт')}</span><span>{branch}</span></div>
    <p className="note">{t('Сохранение создаст коммит в указанной ветке GitHub. Скрипт станет частью репозитория; его вызов задаётся в команде сборки или YAML.')}{automated && <> <strong>{t('Автоматизация включена: новый коммит может запустить выпуск.')}</strong></>}</p>
    {validated === fingerprint && <p className="yaml-validation" role="status">{t('Файл готов к сохранению. YAML и JSON проверены на синтаксис; скрипты при проверке не выполняются.')}</p>}
    <div className="actions yaml-editor-actions"><button type="button" className="secondary" disabled={busy || !sha} onClick={() => void action(async () => { await api(`/pipelines/${pipelineId}/source/validate`, { path, content, create: initial.create }); setValidated(fingerprint) })}>{t('Проверить файл')}</button><button type="button" disabled={busy || !sha || !dirty || !canWrite || validated !== fingerprint} onClick={() => void action(async () => { const saved = await api<SavedSource>(`/pipelines/${pipelineId}/source/save`, { path, content, expectedSha: sha, create: initial.create }); onSaved(saved) })}>{busy ? t('Сохраняем…') : t(initial.create ? 'Создать в ветке {0}' : 'Сохранить в ветку {0}', [branch])}</button>{!sha && !busy && <button className="secondary" type="button" onClick={() => setAttempt(attempt + 1)}>{t('Повторить загрузку')}</button>}</div>
  </section>
}

export function NativeBuildEditor({ pipelineId, command, artifactPath, initialCommand, onClose, onSaved }: {
  pipelineId: string; command: string; artifactPath: string; initialCommand?: string; onClose: () => void; onSaved: () => void
}) {
  useLanguage()
  const [draftCommand, setDraftCommand] = useState(initialCommand || command)
  const [artifact, setArtifact] = useState(artifactPath)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const dirty = draftCommand !== command || artifact !== artifactPath
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  return <form className="native-build-editor project-form" aria-label={t('Редактор настроек сборки')} onSubmit={async e => {
    e.preventDefault(); setBusy(true); setError('')
    try { await api(`/pipelines/${pipelineId}/build-settings`, { command: draftCommand, artifactPath: artifact, expectedCommand: command, expectedArtifactPath: artifactPath }); onSaved() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }}><h3>{t('Настройки сборки сценария')}</h3><label>{t('Команда сборки')}<textarea required rows={5} maxLength={2000} value={draftCommand} disabled={busy} onChange={e => setDraftCommand(e.target.value)}/></label><label>{t('Путь артефакта')}<input required maxLength={300} value={artifact} disabled={busy} onChange={e => setArtifact(e.target.value)}/></label><p className="muted">{t('Изменения сохраняются в Infra Hub и применяются к следующим сборкам этого сценария.')}</p>{error && <p className="error" role="alert">{t(error)}</p>}<div className="actions"><button disabled={busy || !dirty}>{t('Сохранить настройки')}</button><button type="button" className="secondary" disabled={busy} onClick={() => { if (!dirty || window.confirm(t('Отбросить несохранённые изменения файла?'))) onClose() }}>{t('Закрыть')}</button></div></form>
}
