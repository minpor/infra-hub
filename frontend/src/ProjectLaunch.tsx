import { useEffect, useRef, useState } from 'react'
import { api, type Build, type Project } from './api'
import { BranchSelect } from './BranchSelect'
import { t, useLanguage } from './i18n'
import { YamlEditor } from './YamlEditor'
import type { YamlPipeline } from './YamlDeployments'
import { ProjectConfiguration } from './ProjectConfiguration'

type Application = { id: string; projectId: string; serverId: string; name: string; configuration?: Record<string, string> }
type Server = { id: string; name: string }
type Mode = 'build' | 'dev' | 'prod' | 'other'
const environmentOf = (pipeline: YamlPipeline) => pipeline.environment || 'other'

export function ProjectLaunch({ project, previous, onClose, onStarted }: {
  project: Project; previous?: Build['source']; onClose: () => void; onStarted: (build: Build) => Promise<void>
}) {
  useLanguage()
  const [tab, setTab] = useState<'configuration' | 'launch'>('configuration')
  const [mode, setMode] = useState<Mode>('build')
  const [ref, setRef] = useState(previous?.ref || 'HEAD')
  const [command, setCommand] = useState(previous?.command || 'sh ./gradlew --no-daemon --max-workers=4 build')
  const [artifact, setArtifact] = useState(previous?.artifactPath || '')
  const [apps, setApps] = useState<Application[]>([])
  const [servers, setServers] = useState<Server[]>([])
  const [pipelines, setPipelines] = useState<YamlPipeline[]>([])
  const [pipelineId, setPipelineId] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const requests = useRef(new Map<string, string>())
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus() }, [])
  useEffect(() => {
    let stopped = false
    setLoading(true); setLoadError('')
    Promise.all([api<Application[]>('/applications'), api<YamlPipeline[]>('/pipelines'), api<Server[]>('/servers')]).then(([allApps, allPipelines, allServers]) => {
      if (stopped) return
      const projectApps = allApps.filter(app => app.projectId === project.id)
      setApps(projectApps); setPipelines(allPipelines.filter(p => projectApps.some(a => a.id === p.application_id))); setServers(allServers)
    }).catch(() => { if (!stopped) setLoadError('Не удалось загрузить сценарии деплоя. Повторите запрос.') }).finally(() => { if (!stopped) setLoading(false) })
    return () => { stopped = true }
  }, [project.id, attempt])
  const targets = pipelines.filter(p => environmentOf(p) === mode)
  const matching = targets.filter(p => p.ref === ref)
  const pipeline = matching.find(p => p.id === pipelineId) || matching[0]
  const app = apps.find(a => a.id === pipeline?.application_id)
  const server = servers.find(s => s.id === app?.serverId)
  function changeMode(next: Mode) {
    setMode(next); setError(''); setEditor(false)
    const first = pipelines.find(p => environmentOf(p) === next)
    setPipelineId(first?.id || '')
    setRef(next === 'build' ? previous?.ref || 'HEAD' : first?.ref || (next === 'dev' ? 'dev' : 'main'))
  }
  async function launch() {
    if (busy || (mode !== 'build' && (!pipeline || !server || loading || loadError))) return
    setBusy(true); setError('')
    const key = mode === 'build' ? JSON.stringify(['build', ref, command, artifact]) : pipeline!.id
    const requestId = requests.current.get(key) || crypto.randomUUID()
    requests.current.set(key, requestId)
    try {
      const build = mode === 'build'
        ? await api<Build>(`/projects/${project.id}/builds`, { requestId, ref, command, artifactPath: artifact.trim() || null })
        : await api<Build>(`/pipelines/${pipeline!.id}/release`, { requestId, expectedRef: ref, expectedEnvironment: pipeline!.environment || null })
      requests.current.delete(key)
      await onStarted(build)
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }
  return <section className={`panel project-launch ${mode === 'prod' ? 'production-launch' : ''}`} aria-label={t('Запуск проекта')}>
    <div className="launch-heading"><div><span className="eyebrow">{t(tab === 'configuration' ? 'ПРОЕКТ' : 'НОВЫЙ ЗАПУСК')}</span><h2 ref={heading} tabIndex={-1}>{project.name}</h2><p className="muted">{project.repositoryUrl}</p></div><button type="button" className="secondary" disabled={busy || editor} onClick={onClose}>{t('Закрыть')}</button></div>
    <div className="project-tabs" role="tablist" aria-label={t('Раздел проекта')}><button type="button" role="tab" aria-selected={tab === 'configuration'} disabled={editor} onClick={() => setTab('configuration')}>{t('Настройки и скрипты')}</button><button type="button" role="tab" aria-selected={tab === 'launch'} disabled={editor} onClick={() => setTab('launch')}>{t('Запуск')}</button></div>
    {tab === 'configuration' && <ProjectConfiguration targets={pipelines.map(p => ({ ...p, name: apps.find(a => a.id === p.application_id)?.name || p.id }))} loading={loading} error={loadError} onRetry={() => setAttempt(attempt + 1)} onEditing={setEditor} onLaunch={target => { changeMode((target.environment || 'other') as Mode); setRef(target.ref); setPipelineId(target.id); setTab('launch') }}/>}
    {tab === 'launch' && <form onSubmit={e => { e.preventDefault(); void launch() }}>
      <fieldset className="launch-modes" disabled={busy || editor}><legend>{t('Что запускаем?')}</legend>
        {(['build', 'dev', 'prod', ...(pipelines.some(p => !p.environment) ? ['other'] : [])] as Mode[]).map(value => <label key={value} className={`launch-mode ${mode === value ? 'selected' : ''}`}>
          <input type="radio" name={`launch-${project.id}`} value={value} checked={mode === value} disabled={value !== 'build' && loading} onChange={() => changeMode(value)}/>
          <span className={`mode-icon ${value}`} aria-hidden="true">{value === 'build' ? '◇' : value === 'dev' ? '⌘' : value === 'prod' ? '↗' : '○'}</span>
          <span><strong>{value === 'build' ? t('Только сборка') : value === 'other' ? t('Без окружения') : value.toUpperCase()}</strong><small>{t(({ build: 'Проверить код и собрать артефакт', dev: 'Выпустить в тестовое окружение', prod: 'Выпустить в рабочее окружение', other: 'Сценарии без метки dev/prod' })[value])}</small></span>
        </label>)}
      </fieldset>
      <div className="launch-fields"><BranchSelect projectId={project.id} value={ref} knownRefs={pipelines.map(p => p.ref)} allowDefault={mode === 'build'} disabled={busy || editor} onChange={next => { setRef(next); setPipelineId(''); setError('') }}/>
        {mode !== 'build' && <label>{t('Приложение и сценарий')}<select disabled={busy || editor || !matching.length || loading} value={pipeline?.id || ''} onChange={e => { setPipelineId(e.target.value); setError('') }}>
          {!matching.length && <option value="">{t('Нет сценария для выбранной ветки')}</option>}
          {matching.map(p => <option key={p.id} value={p.id}>{apps.find(a => a.id === p.application_id)?.name} · {p.config_path || t('Настроенная сборка')}</option>)}
        </select></label>}
      </div>
      {mode !== 'build' && loading && <p className="muted" role="status">{t('Загружаем сценарии…')}</p>}
      {mode !== 'build' && loadError && <p className="error" role="alert">{t(loadError)} <button type="button" className="text-button" onClick={() => setAttempt(attempt + 1)}>{t('Повторить')}</button></p>}
      {mode !== 'build' && !loading && !loadError && !pipeline && <div className="launch-empty"><strong>{t('Для этого окружения и ветки ещё нет сценария')}</strong><p>{t('Подключите YAML или задайте окружение у существующего сценария в приложениях.')}</p>{!!targets.length && <p>{t('Настроенные ветки')}: {targets.map(p => p.ref).filter((v, i, all) => all.indexOf(v) === i).join(', ')}</p>}<a href="#applications">{t('Настроить деплой')} →</a></div>}
      {(mode === 'build' || pipeline) && <div className="launch-summary" aria-label={t('Параметры запуска')}>
        <span className="summary-icon" aria-hidden="true">{mode === 'build' ? '◇' : '↗'}</span><div><strong>{mode === 'build' ? t('Сборка без выкладки') : `${app?.name} → ${server?.name || t('Сервер недоступен')}`}</strong><p>{ref === 'HEAD' ? t('Ветка по умолчанию') : ref} <span aria-hidden="true">→</span> {mode === 'build' ? t('Артефакт и логи сборки') : mode === 'other' ? t('Без окружения') : mode.toUpperCase()}</p></div>
        {mode !== 'build' && <span className={`badge ${pipeline?.enabled ? 'running' : ''}`}>{pipeline?.enabled ? t('Автоматизация включена') : t('Разовый выпуск')}</span>}
      </div>}
      {mode === 'prod' && pipeline && <p className="production-note">{t('Этот запуск обновит рабочее окружение после успешной сборки.')}</p>}
      {mode !== 'build' && pipeline && <p className="launch-hint">{t('Сборка → выкладка → проверка. Результат деплоя отслеживается в приложениях.')}</p>}
      <details className="launch-advanced"><summary>{t(mode === 'build' ? 'Настройки сборки' : 'Конфигурация сценария')}</summary><div>
        {mode === 'build' ? <><label>{t('Команда сборки')}<textarea value={command} onChange={e => setCommand(e.target.value)} required maxLength={2000} rows={3} disabled={busy}/></label><label>{t('JAR для Docker-образа')} <span className="muted">{t('· необязательно')}</span><input value={artifact} onChange={e => setArtifact(e.target.value)} maxLength={300} placeholder="backend/build/libs/app.jar" disabled={busy}/></label><p className="muted">{t('Точный путь от корня репозитория, без маски. При заполнении образ будет опубликован в настроенный registry.')}</p><p className="muted">{t('Java 21 · Node 22 · до 40 минут. CPU и память задаются в настройках раннера.')}</p></> : pipeline ? <><p><code>{pipeline.config_path || t('Настроенная сборка')}</code> · {pipeline.ref}</p>{pipeline.config_path && !editor && <button className="secondary" type="button" onClick={() => setEditor(true)} disabled={busy}>{t('Редактировать YAML')}</button>}<a href="#applications">{t('Открыть приложения')} →</a></> : <p className="muted">{t('Выберите настроенный сценарий.')}</p>}
      </div></details>
      {error && <p className="error" role="alert">{t(error)}</p>}
      <div className="launch-footer"><span className="muted">{t('Каждый запуск сохраняет точный commit и журнал выполнения.')}</span><button disabled={busy || editor || (mode !== 'build' && (!pipeline || !server || loading || !!loadError))}>{busy ? t('Ставим в очередь…') : mode === 'build' ? t('Поставить в очередь') : t('Собрать и выпустить в {0}', [mode === 'other' ? app?.name : mode.toUpperCase()])} <span aria-hidden="true">→</span></button></div>
    </form>}
    {tab === 'launch' && editor && pipeline?.config_path && <YamlEditor applicationId={pipeline.application_id} sourceRef={pipeline.ref} path={pipeline.config_path} automated={pipeline.enabled} initialMode="edit" onClose={() => setEditor(false)}/>}
  </section>
}
