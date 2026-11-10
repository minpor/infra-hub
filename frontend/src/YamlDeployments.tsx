import { useRef, useState } from 'react'
import { api } from './api'
import { t, useLanguage } from './i18n'

export type YamlPipeline = { id: string; application_id: string; ref: string; enabled: boolean; config_path?: string | null; last_sha: string | null; build_id: string | null; deployment_id: string | null; message: string | null }
type App = { id: string; name: string; projectId: string }
type Preview = { sha: string; path: string; sha256: string; yaml: string; definition: { name: string; build: { steps: { name: string }[]; artifact: string | null }; deploy: { directory: string; steps: { name: string }[]; verify: { name: string }[]; rollback: { name: string }[] } } }

export function YamlDeployments({ apps, pipelines, onUpdate }: { apps: App[]; pipelines: YamlPipeline[]; onUpdate: () => Promise<void> }) {
  useLanguage()
  const [open, setOpen] = useState(false)
  const [applicationId, setApplicationId] = useState('')
  const [ref, setRef] = useState('main')
  const [path, setPath] = useState('.infra-hub/production.yaml')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const bindingId = useRef(crypto.randomUUID())
  const requests = useRef(new Map<string, string>())
  async function action(task: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('')
    try { await task() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }
  const bindings = pipelines.filter(p => p.config_path)
  return <section className="panel server-card yaml-pipelines" aria-label={t('Деплой через YAML')}>
    <div className="panel-heading"><div><h2>{t('Деплой через YAML')}</h2><p className="muted">{t('Несколько файлов на проект: отдельные окружения, сервисы и сценарии.')}</p></div><button className="secondary" onClick={() => setOpen(!open)} aria-expanded={open}>{t('Подключить YAML')}</button></div>
    <div className="server-body">
      <p className="muted">{t('Команды build, deploy, verify и rollback хранятся в репозитории. Сервер и секреты задаются в приложении. Каждый запуск фиксирует файл и commit SHA.')}</p>
      {error && <p className="error" role="alert">{t(error)}</p>}
      {message && <p role="status">{t(message)}</p>}
      {open && <form className="project-form yaml-form" onSubmit={e => { e.preventDefault(); void action(async () => { setPreview(null); setPreview(await api<Preview>('/deployment-yaml/preview', { applicationId, ref, path })) }) }}>
        <label>{t('Приложение для YAML')}<select required value={applicationId} onChange={e => { setApplicationId(e.target.value); setPreview(null) }}><option value="">{t('Выберите приложение')}</option>{apps.map(app => <option key={app.id} value={app.id}>{app.name}</option>)}</select></label>
        <label>{t('Ветка YAML')}<input required maxLength={200} value={ref} onChange={e => { setRef(e.target.value); setPreview(null) }}/></label>
        <label>{t('Путь YAML в репозитории')}<input required maxLength={300} value={path} onChange={e => { setPath(e.target.value); setPreview(null) }} placeholder=".infra-hub/production.yaml"/></label>
        <button disabled={busy}>{busy ? t('Загружаем YAML…') : t('Проверить YAML')}</button>
      </form>}
      {open && preview && <div className="yaml-preview">
        <h3>{preview.definition.name}</h3><p className="muted">{preview.path} · {preview.sha.slice(0, 12)}</p>
        <p>{t('Рабочий каталог')}: <code>{preview.definition.deploy.directory}</code></p>
        <p className="muted">build: {preview.definition.build.steps.map(s => s.name).join(' → ')} · deploy: {preview.definition.deploy.steps.map(s => s.name).join(' → ')} · verify: {preview.definition.deploy.verify.map(s => s.name).join(' → ')}</p>
        <pre className="config-preview">{preview.yaml}</pre>
        <p className="note">{t('Шаги deploy выполняются с правами выбранного SSH-пользователя. Подключение сохраняется с выключенной автоматизацией; запуск выпуска — отдельное действие.')}</p>
        <button disabled={busy} onClick={() => void action(async () => {
          await api('/deployment-yaml', { id: bindingId.current, applicationId, ref, path, expectedSha: preview.sha, enabled: false })
          bindingId.current = crypto.randomUUID(); setPreview(null); setOpen(false); await onUpdate(); setMessage('YAML подключён. Можно запустить выпуск или включить автоматизацию.')
        })}>{t('Сохранить YAML-привязку')}</button>
      </div>}
      {bindings.length === 0 && !open && <p className="empty">{t('YAML ещё не подключены. Создайте приложение и укажите файл из его репозитория.')}</p>}
      {bindings.map(p => <article className="yaml-binding" key={p.id}>
        <h3>{apps.find(a => a.id === p.application_id)?.name} · <code>{p.config_path}</code></h3>
        <p className="muted">{p.ref} · {p.enabled ? t('Автоматизация включена') : t('Ручной запуск')} · {p.last_sha?.slice(0, 12) || t('ещё не было')}</p>
        {p.message && <p role="status">{t(p.message)}</p>}
        {p.build_id && <a href={`#builds/${p.build_id}`}>{t('Открыть сборку')}</a>}
        <div className="actions"><button disabled={busy} onClick={() => void action(async () => {
          const requestId = requests.current.get(p.id) || crypto.randomUUID(); requests.current.set(p.id, requestId)
          const build = await api<{ id: string }>(`/pipelines/${p.id}/release`, { requestId })
          requests.current.delete(p.id); await onUpdate(); setMessage('Выпуск поставлен в очередь. Результат сборки и деплоя отслеживается отдельно.')
          if (!build.id) throw new Error('Build response is missing an ID')
        })}>{t('Запустить YAML-выпуск')}</button><button className="secondary" disabled={busy} onClick={() => void action(async () => { await api(`/pipelines/${p.id}/enabled`, { enabled: !p.enabled }); await onUpdate() })}>{p.enabled ? t('Приостановить автоматизацию') : t('Включить автоматизацию')}</button></div>
      </article>)}
    </div>
  </section>
}
