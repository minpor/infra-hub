import { t, useLanguage, locale, LanguageSwitch } from './i18n'
import { StrictMode, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, active, csrfToken, ApiError, type Project, type Build, type Detail, type Log, type Status } from './api'
import './style.css'
import { compactBuildLogs } from './buildLogs'
import { RunTiming } from './RunTiming'
import { minutes } from './i18n'
import { Servers } from './Servers'
import { Applications } from './Applications'
import { Settings } from './Settings'
import { ProjectLaunch } from './ProjectLaunch'

const labels: Record<Status, string> = { QUEUED: 'В очереди', RUNNING: 'Выполняется', CANCEL_REQUESTED: 'Отменяется', SUCCEEDED: 'Успешно', FAILED: 'Ошибка', CANCELLED: 'Отменена', INTERRUPTED: 'Прервана' }
const date = (value: string) => new Date(value).toLocaleString(locale(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' })
function Badge({ status }: { status: Status }) {
  useLanguage(); return <span className={`badge ${status.toLowerCase()}`}><span aria-hidden="true">●</span> {t(labels[status])}</span> }

function BuildView({ id, onCancel }: { id: string; onCancel: (id: string) => Promise<void> }) {
  useLanguage()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [logs, setLogs] = useState<Log[]>([])
  const [fullLog, setFullLog] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    let cursor = 0
    const poll = async () => {
      try {
        const next = await api<Detail>(`/builds/${id}`)
        const entries = await api<Log[]>(`/builds/${id}/logs?after=${cursor}`)
        if (disposed) return
        setDetail(next); setError('')
        if (entries.length) { cursor = entries.at(-1)!.id; setLogs(old => [...old, ...entries]) }
        // Continue after terminal state to drain paginated output as well.
        if (active(next.build.status) || entries.length === 200) timer = setTimeout(poll, 1000)
      } catch { if (!disposed) { setError("Не удалось обновить сборку. Повторяем подключение…"); timer = setTimeout(poll, 2000) } }
    }
    void poll()
    return () => { disposed = true; clearTimeout(timer) }
  }, [id])
  return <section className="detail panel" aria-label={t("Выбранная сборка")}>
    <div className="panel-heading"><div><span className="eyebrow">{t("СБОРКА /")} {id.slice(0, 8)}</span><h2>{detail?.build.source ? t("Сборка репозитория") : t("Проверка исполнителя")}</h2></div>{detail && <Badge status={detail.build.status}/>}</div>
    <p className="muted">{detail?.build.source ? `${detail.build.source.repository} · ${detail.build.source.ref} · ${detail.build.source.sha.slice(0, 12)}` : t("Встроенный Java-пример · компиляция → тесты → JAR")}</p>
    {detail && <RunTiming run={detail.build}/>}
    {detail?.build.runnerExecution && <div className="build-execution">
      <div><span className="eyebrow">{t('РЕСУРСЫ СБОРКИ')}</span><strong>{detail.build.runnerExecution.cpus} CPU · {detail.build.runnerExecution.memoryGiB} GiB</strong></div>
      <div><span className="eyebrow">{t('КЭШ ЗАВИСИМОСТЕЙ')}</span><strong>{t(({ DISABLED: 'Выключен · чистая сборка', PENDING: 'Подключается', EMPTY: 'Пустой · первый запуск', AVAILABLE: 'Подключён · есть сохранённые файлы' } as Record<string, string>)[detail.build.runnerExecution.cacheStatus] || detail.build.runnerExecution.cacheStatus)}</strong></div>
      {detail.build.runnerExecution.cacheVolume && <p className="muted">{t('Хранилище проекта')}: <code>{detail.build.runnerExecution.cacheVolume}</code><br/>{t('Gradle и npm проверяют, какие зависимости можно использовать повторно.')}</p>}
    </div>}
    {error && <p role="alert" className="error">{t(error)}</p>}
    {!detail && <p>{t("Загружаем сборку…")}</p>}
    <ol className="steps">{detail?.steps.map(step => <li key={step.position}><span className="step-number">{step.position + 1}</span><strong>{t(step.name)}</strong><Badge status={step.status}/></li>)}</ol>
    <div className="terminal"><div className="terminal-head"><span>{t("ВЫВОД ПРОЦЕССОВ")}</span><button className="log-mode" aria-pressed={fullLog} onClick={() => setFullLog(!fullLog)}>{fullLog ? t('Краткий журнал') : t('Полный журнал')}</button></div><pre aria-label={t("Логи сборки")}>{logs.length ? (fullLog ? logs.map(log => log.text).join('') : compactBuildLogs(logs.map(log => log.text).join(''))) : t("Ожидаем вывод worker…")}</pre></div>
    {detail?.build.source?.yamlRecipe && <div className="result"><strong>{t('Конфигурация YAML')}</strong><p><code>{detail.build.source.yamlRecipe.path}</code></p><p className="muted">SHA-256: <code>{detail.build.source.yamlRecipe.sha256}</code></p>{detail.build.source.yamlRecipe.yaml && <details><summary>{t('Просмотр YAML этой сборки')}</summary><pre className="config-preview">{detail.build.source.yamlRecipe.yaml}</pre></details>}</div>}
    {detail?.build.image && <div className="result"><strong>{t("Опубликованный образ")}</strong><p style={{ overflowWrap: 'anywhere' }}><code>{detail.build.image.reference}</code></p><p className="muted">JAR: {Math.ceil(detail.build.image.artifactSize / 1024)} KiB · SHA-256: <code style={{ overflowWrap: 'anywhere' }}>{detail.build.image.artifactSha256}</code></p></div>}
    {detail?.build.message && <p className="result" role="status">{t(detail.build.message)}</p>}
    {detail && active(detail.build.status) && <button className="secondary" disabled={busy || detail.build.status === 'CANCEL_REQUESTED'} onClick={async () => {
      setBusy(true)
      try { await onCancel(id); setDetail(await api<Detail>(`/builds/${id}`)) } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
    }}>{detail.build.status === 'CANCEL_REQUESTED' ? t("Останавливаем процессы…") : t("Отменить сборку")}</button>}
  </section>
}

function App() {
  useLanguage()
  const currentSection = () => window.location.hash === '#settings/mcp' ? 'mcp' : window.location.hash === '#runner' || window.location.hash.startsWith('#settings') ? 'settings' : ['servers', 'applications'].find(value => window.location.hash === '#' + value) || 'projects'
  const [section, setSection] = useState(currentSection)
  useEffect(() => {
    const change = () => { setSection(currentSection()); const build = /^#builds\/([a-f0-9-]{36})$/.exec(window.location.hash); if (build) setSelected(build[1]) }
    window.addEventListener('hashchange', change)
    return () => window.removeEventListener('hashchange', change)
  }, [])
  const [authenticated, setAuthenticated] = useState<boolean | null>(null)
  const [projects, setProjects] = useState<Project[]>([])
  const [builds, setBuilds] = useState<Build[]>([])
  const [overview, setOverview] = useState({ projects: 0, running: 0, queued: 0, buildMillis: 0, deploymentMillis: 0, unknownDeploymentTimes: 0 })
  const [selected, setSelected] = useState<string | null>(/^#builds\/([a-f0-9-]{36})$/.exec(window.location.hash)?.[1] || null)
  const [loaded, setLoaded] = useState(false)
  const [connectionError, setConnectionError] = useState('')
  const [actionError, setActionError] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [name, setName] = useState('')
  const [repositoryUrl, setRepositoryUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [launchProject, setLaunchProject] = useState<Project | null>(null)
  const [filter, setFilter] = useState('')
  const requests = useRef(new Map<string, string>())
  const nameInput = useRef<HTMLInputElement>(null)
  useEffect(() => { if (formOpen) nameInput.current?.focus() }, [formOpen])
  useEffect(() => {
    let stopped = false
    api<{ login: string }>('/session').then(() => { if (!stopped) setAuthenticated(true) }).catch(error => {
      if (!stopped) setAuthenticated(error instanceof ApiError && error.status === 404)
    })
    return () => { stopped = true }
  }, [])
  async function refresh() {
    const [p, b, counts] = await Promise.all([api<Project[]>('/projects'), api<Build[]>('/builds'), api<typeof overview>('/overview')])
    setProjects(p); setBuilds(b); setOverview(counts); setLoaded(true); setConnectionError('')
  }
  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      if (authenticated !== true) return
      try { if (!stopped) await refresh() } catch { if (!stopped) setConnectionError("Нет связи с backend. Данные ниже могут быть устаревшими; повторяем подключение…") }
      if (!stopped) timer = setTimeout(poll, 2000)
    }
    void poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [authenticated])
  async function run(projectId: string, ref = 'HEAD', command = 'sh ./gradlew --no-daemon build', artifactPath = '') {
    setBusy(true); setActionError('')
    const requestId = requests.current.get(projectId) || crypto.randomUUID()
    requests.current.set(projectId, requestId)
    try {
      const build = await api<Build>(`/projects/${projectId}/builds`, { requestId, ref, command, artifactPath: artifactPath.trim() || null })
      requests.current.delete(projectId); setLaunchProject(null); setSelected(build.id); await refresh()
    } catch (e) { setActionError((e as Error).message) } finally { setBusy(false) }
  }
  if (authenticated === null) return <><LanguageSwitch/><main><p>{t("Проверяем доступ…")}</p></main></>
  if (!authenticated) return <><div className="login-language"><LanguageSwitch/></div><main className="login panel"><img src="/assets/mark.svg" alt="" width="64"/><h1>Infra Hub</h1><p>{t("Войдите через GitHub, чтобы управлять проектами и сборками.")}</p><a className="login-button" href="/oauth2/authorization/github">{t("Войти через GitHub")}</a></main></>
  return <>
    <a className="skip" href="#main">{t("Перейти к содержимому")}</a>
    <header><a className="brand" href="/"><img src="/assets/mark.svg" alt=""/>infra-hub</a><nav aria-label={t("Основная навигация")}><a href="#projects" aria-current={section === 'projects' ? 'page' : undefined}>{t("Проекты")}</a><a href="#builds">{t("Сборки")}</a><a href="#applications" aria-current={section === 'applications' ? 'page' : undefined}>{t("Приложения")}</a><a href="#servers" aria-current={section === 'servers' ? 'page' : undefined}>{t("Серверы")}</a><a href="#settings/runner" aria-current={['settings', 'mcp'].includes(section) ? 'page' : undefined}>{t("Настройки")}</a></nav><LanguageSwitch/><button className="secondary"  onClick={async () => { const token = await csrfToken(); await fetch('/logout', { method: 'POST', headers: token ? { 'X-XSRF-TOKEN': token } : {} }); window.location.assign('/') }}><span className="logout-text">{t("Выйти")}</span><span className="logout-icon" aria-hidden="true">↪</span></button></header>
    <main id="main">
      {section === 'servers' ? <Servers/> : section === 'applications' ? <Applications/> : ['settings', 'mcp'].includes(section) ? <Settings mcp={section === 'mcp'}/> : <>
      <div className="page-heading"><div className="title"><p className="eyebrow">{t("СВОЯ МОЩНОСТЬ")}</p><h1>{t("Проекты и сборки")}</h1><p className="muted">{t("От первого задания — к собственной платформе выпуска.")}</p></div><button onClick={() => setFormOpen(!formOpen)} aria-expanded={formOpen}>{t("＋ Новый проект")}</button></div>
      {connectionError && <div className="error" role="alert">{t(connectionError)}</div>}
      {actionError && <div className="error" role="alert">{t(actionError)}</div>}
      <div className="stats"><div><span>{t("Проекты")}</span><strong>{loaded ? overview.projects : '—'}</strong></div><div><span>{t("Выполняются")}</span><strong>{loaded ? overview.running : '—'}<small>  {t("/ 1 слот")}</small></strong></div><div><span>{t("В очереди")}</span><strong>{loaded ? overview.queued : '—'}</strong></div></div>
      <div className="stats usage-stats" aria-label={t('Учтённое время за всё время')}><div><span>{t('Время сборок')}</span><strong>{loaded ? minutes(overview.buildMillis) : '—'}</strong></div><div><span>{t('Время деплоев')}</span><strong>{loaded ? minutes(overview.deploymentMillis) : '—'}</strong></div><div><span>{t('Учёт времени')}</span><p className="muted">{t('Выполнение без ожидания в очереди; не тарификация.')}{!!overview.unknownDeploymentTimes && <> {t('Без времени: {0}', [overview.unknownDeploymentTimes])}</>}</p></div></div>
      {formOpen && <form className="panel project-form" onSubmit={async e => {
        e.preventDefault(); setBusy(true); setActionError('')
        try { await api<Project>('/projects', { name, repositoryUrl: repositoryUrl || null }); setName(''); setRepositoryUrl(''); setFormOpen(false); await refresh() }
        catch (error) { setActionError((error as Error).message) } finally { setBusy(false) }
      }}><h2>{t("Новый проект")}</h2><label>{t("Название")}<input ref={nameInput} value={name} onChange={e => setName(e.target.value)} maxLength={80} required placeholder={t("Мой первый проект")}/></label><label>{t("Репозиторий GitHub")} <span className="muted">{t("· необязательно")}</span><input type="text" value={repositoryUrl} onChange={e => setRepositoryUrl(e.target.value)} maxLength={240} placeholder="https://github.com/owner/repository"/></label><p className="muted">{t("Публичные репозитории доступны сразу. Для приватных нужен доступ GitHub App к выбранному репозиторию.")}</p><div className="actions"><button disabled={busy}>{t("Создать проект")}</button><button type="button" className="secondary" onClick={() => setFormOpen(false)}>{t("Закрыть")}</button></div></form>}
      {launchProject && <ProjectLaunch key={launchProject.id} project={launchProject} previous={builds.find(b => b.projectId === launchProject.id && b.source && !b.source.yamlRecipe)?.source} onClose={() => setLaunchProject(null)} onStarted={async build => { setLaunchProject(null); setSelected(build.id); await refresh() }}/>}
      <div className="workspace"><div>
        <section id="projects" className="panel"><div className="panel-heading"><h2>{t("Ваши проекты")}</h2><span className="counter">{projects.length}</span></div><input className="search" aria-label={t("Поиск проектов")} placeholder={t("Найти проект…")} value={filter} onChange={e => setFilter(e.target.value)}/>
          {!loaded ? <p className="empty">{connectionError ? t("Ждём подключения к backend.") : t("Загружаем проекты…")}</p> : projects.length === 0 ? <div className="empty"><span className="empty-mark">＋</span><h3>{t("Начните с первого проекта")}</h3><p>{t("Создайте проект и запустите проверочную сборку.")}<br/>{t("Результат и логи сохранятся после перезапуска.")}</p><button onClick={() => setFormOpen(true)}>{t("Создать проект")}</button></div> : projects.filter(p => p.name.toLowerCase().includes(filter.toLowerCase())).map(p => <article className="project-row" key={p.id}><div className="project-icon" aria-hidden="true">{p.name.slice(0, 1).toUpperCase()}</div><div className="project-info"><h3>{p.name}</h3><p className="muted">{p.repositoryUrl || t("Без подключения GitHub")}</p></div><button className="secondary" disabled={busy || !!connectionError} onClick={() => { if (p.repositoryUrl) { setLaunchProject(p) } else { void run(p.id) } }}>{p.repositoryUrl ? t("Открыть проект") : t("Запустить проверку")}</button></article>)}
          {loaded && projects.length > 0 && !projects.some(p => p.name.toLowerCase().includes(filter.toLowerCase())) && <p className="empty">{t("Проекты не найдены.")}</p>}
        </section>
        <section id="builds" className="panel"><div className="panel-heading"><h2>{t("Последние сборки")}</h2><span className="muted">{t("до 100 записей")}</span></div>{builds.length === 0 ? <p className="empty">{t("Здесь появятся очередь и история запусков.")}</p> : <ul className="build-list">{builds.map(b => <li key={b.id}><button className={`build-row ${selected === b.id ? 'selected' : ''}`} onClick={() => setSelected(b.id)} aria-pressed={selected === b.id}><div><strong>{projects.find(p => p.id === b.projectId)?.name || t("Проект")}</strong><span className="build-meta">{b.id.slice(0, 8)} · {date(b.createdAt)} · {t('Выполнение')}: {minutes(b.startedAt ? Math.max(0, Date.parse(b.finishedAt || new Date().toISOString()) - Date.parse(b.startedAt)) : null)}</span></div><Badge status={b.status}/></button></li>)}</ul>}</section>
      </div><aside>{selected ? <BuildView key={selected} id={selected} onCancel={async id => { await api(`/builds/${id}/cancel`, {}); await refresh() }}/> : <section className="panel guide"><span className="eyebrow">{t("ПЕРВЫЙ ЗАПУСК")}</span><h2>{t("Проверим всю цепочку")}</h2><ol><li>{t("Создайте проект")}</li><li>{t("Запустите проверку")}</li><li>{t("Откройте шаги и логи")}</li></ol><p className="muted">{t("Укажите GitHub-репозиторий, выберите ветку и команду. Worker получит точный commit и выполнит сборку с ограничением ресурсов.")}</p><div className="note">{t("Очередь, шаги и журналы хранятся в PostgreSQL. Для выполнения нужен запущенный worker.")}</div></section>}</aside></div>
      </>}
      <footer><span>{t("infra-hub / своя мощность")}</span><span>{t("Сборки · образы · деплой по SSH")}</span></footer>
    </main>
  </>
}

createRoot(document.getElementById('root')!).render(<StrictMode><App/></StrictMode>)
