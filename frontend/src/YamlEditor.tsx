import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { t, useLanguage } from './i18n'

type Document = { sha: string; path: string; sha256: string; yaml: string }
type Definition = { name: string; build: { steps: { name: string }[]; cache?: boolean | null }; deploy: { steps: { name: string }[]; verify: { name: string }[]; rollback: { name: string }[] } }

export function YamlEditor({ applicationId, sourceRef, path, automated, initialMode, onClose }: {
  applicationId: string; sourceRef: string; path: string; automated: boolean; initialMode: 'view' | 'edit'; onClose: () => void
}) {
  useLanguage()
  const [document, setDocument] = useState<Document | null>(null)
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState(initialMode)
  const [definition, setDefinition] = useState<Definition | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const container = useRef<HTMLDivElement>(null)
  const dirty = document !== null && draft !== document.yaml
  useEffect(() => {
    let stopped = false
    setBusy(true); setError('')
    api<Document>('/deployment-yaml/document', { applicationId, ref: sourceRef, path }).then(value => {
      if (!stopped) { setDocument(value); setDraft(value.yaml); setDefinition(null); setMessage('') }
    }).catch(e => { if (!stopped) setError((e as Error).message) }).finally(() => { if (!stopped) setBusy(false) })
    container.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    return () => { stopped = true }
  }, [applicationId, sourceRef, path, reload])
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  const canDiscard = () => !dirty || window.confirm(t('Отбросить несохранённые изменения YAML?'))
  async function action(task: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('')
    try { await task() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }
  return <div className="yaml-editor" ref={container} role="region" aria-label={t('Редактор YAML')}>
    <div className="yaml-editor-heading"><div><span className="eyebrow">{t('КОНФИГУРАЦИЯ В РЕПОЗИТОРИИ')}</span><h3><code>{path}</code></h3><p className="muted">{sourceRef} · {document?.sha.slice(0, 12) || t('Загружаем YAML…')}</p></div><button className="secondary" disabled={busy} onClick={() => { if (canDiscard()) onClose() }}>{t('Закрыть')}</button></div>
    <div className="yaml-toolbar"><div className="mode-switch" role="group" aria-label={t('Режим YAML')}><button className={mode === 'view' ? 'selected' : ''} aria-pressed={mode === 'view'} onClick={() => setMode('view')}>{t('Просмотр')}</button><button className={mode === 'edit' ? 'selected' : ''} aria-pressed={mode === 'edit'} onClick={() => setMode('edit')}>{t('Редактирование')}</button></div><span className={`yaml-state ${dirty ? 'changed' : ''}`}>{dirty ? t('Есть несохранённые изменения') : t('Версия из репозитория')}</span></div>
    {error && <p className="error" role="alert">{t(error)}</p>}
    {message && <p className="yaml-success" role="status">{t(message)}</p>}
    {document && <>
      {mode === 'edit' ? <label className="yaml-code-label"><span className="sr-only">{t('Содержимое YAML')}</span><textarea className="yaml-code" aria-label={t('Содержимое YAML')} spellCheck={false} autoCapitalize="off" autoCorrect="off" rows={22} value={draft} disabled={busy} onChange={e => { setDraft(e.target.value); setDefinition(null); setMessage('') }} onKeyDown={e => {
        if (e.key === 'Tab') {
          e.preventDefault()
          const input = e.currentTarget, start = input.selectionStart, end = input.selectionEnd
          setDraft(draft.slice(0, start) + '  ' + draft.slice(end)); setDefinition(null); setMessage('')
          requestAnimationFrame(() => { input.selectionStart = input.selectionEnd = start + 2 })
        }
      }}/></label> : <pre className="yaml-code yaml-readonly" aria-label={t('Содержимое YAML')}>{draft.split('\n').map((line, index) => <span className="yaml-line" key={index}><span className="yaml-line-number" aria-hidden="true">{index + 1}</span><code>{line || ' '}</code></span>)}</pre>}
      <div className="yaml-code-footer"><span>YAML · {new TextEncoder().encode(draft).length.toLocaleString()} / 65,536 {t('байт')}</span><span>{t('Отступ: 2 пробела')}</span></div>
      {definition && <div className="yaml-validation" role="status"><strong>✓ {t('YAML проверен')}: {definition.name}</strong><div className="yaml-stages">{[['Build', definition.build.steps], ['Deploy', definition.deploy.steps], ['Verify', definition.deploy.verify], ['Rollback', definition.deploy.rollback]].map(([label, steps]) => <span key={label as string}><b>{label as string}</b> {(steps as { name: string }[]).length}</span>)}</div><p>{definition.build.cache == null ? t('Кэш: по настройкам проекта') : definition.build.cache ? t('Кэш: включён в YAML') : t('Кэш: выключен в YAML')}</p></div>}
      {mode === 'edit' && <p className="note">{t('Сохранение создаст коммит в выбранной ветке репозитория. Для записи нужен GitHub App с правом Contents: read and write.')}{automated && <> <strong>{t('Автоматизация включена: новый коммит может запустить выпуск.')}</strong></>}</p>}
      <div className="actions yaml-editor-actions"><button className="secondary" disabled={busy} onClick={() => void action(async () => { setDefinition(null); setDefinition(await api<Definition>('/deployment-yaml/validate', { yaml: draft })) })}>{t('Проверить YAML')}</button>{mode === 'edit' && <button disabled={busy || !dirty || !definition} onClick={() => void action(async () => {
        const saved = await api<Document>('/deployment-yaml/save', { applicationId, ref: sourceRef, path, expectedSha: document.sha, yaml: draft })
        setDocument(saved); setDraft(saved.yaml); setMode('view'); setMessage('YAML сохранён в репозитории. Новый коммит: {0}'.replace('{0}', saved.sha.slice(0, 12)))
      })}>{busy ? t('Сохраняем…') : t('Сохранить в ветку {0}', [sourceRef])}</button>}<button className="secondary" disabled={busy} onClick={() => { if (canDiscard()) setReload(value => value + 1) }}>{t('Загрузить заново')}</button></div>
    </>}
    {!document && !busy && <button className="secondary" onClick={() => setReload(value => value + 1)}>{t('Повторить загрузку')}</button>}
  </div>
}
