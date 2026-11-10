import { StrictMode, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, active, csrfToken, ApiError, type Project, type Build, type Detail, type Log, type Status } from './api'
import './style.css'
import { Servers } from './Servers'
import { Applications } from './Applications'
import { Settings } from './Settings'

const labels: Record<Status, string> = { QUEUED: 'В очереди', RUNNING: 'Выполняется', CANCEL_REQUESTED: 'Отменяется', SUCCEEDED: 'Успешно', FAILED: 'Ошибка', CANCELLED: 'Отменена', INTERRUPTED: 'Прервана' }
const date = (value: string) => new Date(value).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' })
function Badge({ status }: { status: Status }) { return <span className={`badge ${status.toLowerCase()}`}><span aria-hidden="true">●</span> {labels[status]}</span> }

function BuildView({ id, onCancel }: { id: string; onCancel: (id: string) => Promise<void> }) {
  const [detail, setDetail] = useState<Detail | null>(null)
  const [logs, setLogs] = useState<Log[]>([])
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
      } catch { if (!disposed) { setError('Не удалось обновить сборку. Повторяем подключение…'); timer = setTimeout(poll, 2000) } }
    }
    void poll()
    return () => { disposed = true; clearTimeout(timer) }
  }, [id])
  return <section className="detail panel" aria-label="Выбранная сборка">
    <div className="panel-heading"><div><span className="eyebrow">СБОРКА / {id.slice(0, 8)}</span><h2>{detail?.build.source ? "Сборка репозитория" : "Проверка исполнителя"}</h2></div>{detail && <Badge status={detail.build.status}/>}</div>
    <p className="muted">{detail?.build.source ? `${detail.build.source.repository} · ${detail.build.source.ref} · ${detail.build.source.sha.slice(0, 12)}` : "Встроенный Java-пример · компиляция → тесты → JAR"}</p>
    {error && <p role="alert" className="error">{error}</p>}
    {!detail && <p>Загружаем сборку…</p>}
    <ol className="steps">{detail?.steps.map(step => <li key={step.position}><span className="step-number">{step.position + 1}</span><strong>{step.name}</strong><Badge status={step.status}/></li>)}</ol>
    <div className="terminal"><div className="terminal-head"><span>ВЫВОД ПРОЦЕССОВ</span><span>live / stdout + stderr</span></div><pre aria-label="Логи сборки">{logs.length ? logs.map(log => log.text).join('') : 'Ожидаем вывод worker…'}</pre></div>
    {detail?.build.image && <div className="result"><strong>Опубликованный образ</strong><p style={{ overflowWrap: 'anywhere' }}><code>{detail.build.image.reference}</code></p><p className="muted">JAR: {Math.ceil(detail.build.image.artifactSize / 1024)} KiB · SHA-256: <code style={{ overflowWrap: 'anywhere' }}>{detail.build.image.artifactSha256}</code></p></div>}
    {detail?.build.message && <p className="result" role="status">{detail.build.message}</p>}
    {detail && active(detail.build.status) && <button className="secondary" disabled={busy || detail.build.status === 'CANCEL_REQUESTED'} onClick={async () => {
      setBusy(true)
      try { await onCancel(id); setDetail(await api<Detail>(`/builds/${id}`)) } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
    }}>{detail.build.status === 'CANCEL_REQUESTED' ? 'Останавливаем процессы…' : 'Отменить сборку'}</button>}
  </section>
}

function App() {
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
  const [overview, setOverview] = useState({ projects: 0, running: 0, queued: 0 })
  const [selected, setSelected] = useState<string | null>(/^#builds\/([a-f0-9-]{36})$/.exec(window.location.hash)?.[1] || null)
  const [loaded, setLoaded] = useState(false)
  const [connectionError, setConnectionError] = useState('')
  const [actionError, setActionError] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [name, setName] = useState('')
  const [repositoryUrl, setRepositoryUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [launchProject, setLaunchProject] = useState<Project | null>(null)
  const [sourceRef, setSourceRef] = useState('HEAD')
  const [buildCommand, setBuildCommand] = useState('sh ./gradlew --no-daemon build')
  const [artifactPath, setArtifactPath] = useState('')
  const [appConfigured, setAppConfigured] = useState(false)
  const [filter, setFilter] = useState('')
  const requests = useRef(new Map<string, string>())
  const nameInput = useRef<HTMLInputElement>(null)
  useEffect(() => { if (formOpen) nameInput.current?.focus() }, [formOpen])
  useEffect(() => {
    let stopped = false
    api<{ appConfigured: boolean }>('/github').then(value => setAppConfigured(value.appConfigured)).catch(() => {})
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
      try { if (!stopped) await refresh() } catch { if (!stopped) setConnectionError('Нет связи с backend. Данные ниже могут быть устаревшими; повторяем подключение…') }
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
  if (authenticated === null) return <main><p>Проверяем доступ…</p></main>
  if (!authenticated) return <main className="login panel"><img src="/assets/mark.svg" alt="" width="64"/><h1>Infra Hub</h1><p>Войдите через GitHub, чтобы управлять проектами и сборками.</p><a className="login-button" href="/oauth2/authorization/github">Войти через GitHub</a></main>
  return <>
    <a className="skip" href="#main">Перейти к содержимому</a>
    <header><a className="brand" href="/"><img src="/assets/mark.svg" alt=""/>infra-hub</a><nav aria-label="Основная навигация"><a href="#projects" aria-current={section === 'projects' ? 'page' : undefined}>Проекты</a><a href="#builds">Сборки</a><a href="#applications" aria-current={section === 'applications' ? 'page' : undefined}>Приложения</a><a href="#servers" aria-current={section === 'servers' ? 'page' : undefined}>Серверы</a><a href="#settings/runner" aria-current={['settings', 'mcp'].includes(section) ? 'page' : undefined}>Настройки</a></nav><button className="secondary" style={{ marginLeft: 'auto' }} onClick={async () => { const token = await csrfToken(); await fetch('/logout', { method: 'POST', headers: token ? { 'X-XSRF-TOKEN': token } : {} }); window.location.assign('/') }}>Выйти</button></header>
    <main id="main">
      {section === 'servers' ? <Servers/> : section === 'applications' ? <Applications/> : ['settings', 'mcp'].includes(section) ? <Settings mcp={section === 'mcp'}/> : <>
      <div className="page-heading"><div className="title"><p className="eyebrow">СВОЯ МОЩНОСТЬ</p><h1>Проекты и сборки</h1><p className="muted">От первого задания — к собственной платформе выпуска.</p></div><button onClick={() => setFormOpen(!formOpen)} aria-expanded={formOpen}>＋ Новый проект</button></div>
      {connectionError && <div className="error" role="alert">{connectionError}</div>}
      {actionError && <div className="error" role="alert">{actionError}</div>}
      <div className="stats"><div><span>Проекты</span><strong>{loaded ? overview.projects : '—'}</strong></div><div><span>Выполняются</span><strong>{loaded ? overview.running : '—'}<small> / 1 слот</small></strong></div><div><span>В очереди</span><strong>{loaded ? overview.queued : '—'}</strong></div></div>
      {formOpen && <form className="panel project-form" onSubmit={async e => {
        e.preventDefault(); setBusy(true); setActionError('')
        try { await api<Project>('/projects', { name, repositoryUrl: repositoryUrl || null }); setName(''); setRepositoryUrl(''); setFormOpen(false); await refresh() }
        catch (error) { setActionError((error as Error).message) } finally { setBusy(false) }
      }}><h2>Новый проект</h2><label>Название<input ref={nameInput} value={name} onChange={e => setName(e.target.value)} maxLength={80} required placeholder="Мой первый проект"/></label><label>Репозиторий GitHub <span className="muted">· необязательно</span><input type="text" value={repositoryUrl} onChange={e => setRepositoryUrl(e.target.value)} maxLength={240} placeholder="https://github.com/owner/repository"/></label><p className="muted">Публичные репозитории доступны сразу. Для приватных нужен доступ GitHub App к выбранному репозиторию.</p><div className="actions"><button disabled={busy}>Создать проект</button><button type="button" className="secondary" onClick={() => setFormOpen(false)}>Закрыть</button></div></form>}
      {launchProject && <form className="panel project-form" onSubmit={e => { e.preventDefault(); void run(launchProject.id, sourceRef, buildCommand, artifactPath) }}>
        <h2>Сборка: {launchProject.name}</h2>
        <label>Ветка, тег или commit<input value={sourceRef} onChange={e => setSourceRef(e.target.value)} required maxLength={200}/></label>
        <label>Команда сборки<textarea value={buildCommand} onChange={e => setBuildCommand(e.target.value)} required maxLength={2000} rows={3}/></label>
        <p className="muted">Java 21 · Node 22 · 4 CPU · 6 GiB RAM · до 20 минут. Команда запускается из корня репозитория. Для публикации Java-образа укажите путь к готовому JAR.</p>
        <label>JAR для Docker-образа <span className="muted">· необязательно</span><input value={artifactPath} onChange={e => setArtifactPath(e.target.value)} maxLength={300} placeholder="backend/build/libs/app.jar"/></label>
        <p className="muted">Точный путь от корня репозитория, без маски. При заполнении образ будет опубликован в настроенный registry.</p>
        {!appConfigured && <p className="note">GitHub App ещё не настроен: доступны только публичные репозитории.</p>}
        <div className="actions"><button disabled={busy}>{busy ? 'Получаем commit…' : 'Поставить в очередь'}</button><button type="button" className="secondary" disabled={busy} onClick={() => setLaunchProject(null)}>Закрыть</button></div>
      </form>}
      <div className="workspace"><div>
        <section id="projects" className="panel"><div className="panel-heading"><h2>Ваши проекты</h2><span className="counter">{projects.length}</span></div><input className="search" aria-label="Поиск проектов" placeholder="Найти проект…" value={filter} onChange={e => setFilter(e.target.value)}/>
          {!loaded ? <p className="empty">{connectionError ? 'Ждём подключения к backend.' : 'Загружаем проекты…'}</p> : projects.length === 0 ? <div className="empty"><span className="empty-mark">＋</span><h3>Начните с первого проекта</h3><p>Создайте проект и запустите проверочную сборку.<br/>Результат и логи сохранятся после перезапуска.</p><button onClick={() => setFormOpen(true)}>Создать проект</button></div> : projects.filter(p => p.name.toLowerCase().includes(filter.toLowerCase())).map(p => <article className="project-row" key={p.id}><div className="project-icon" aria-hidden="true">{p.name.slice(0, 1).toUpperCase()}</div><div className="project-info"><h3>{p.name}</h3><p className="muted">{p.repositoryUrl || 'Без подключения GitHub'}</p></div><button className="secondary" disabled={busy || !!connectionError} onClick={() => { if (p.repositoryUrl) { const previous = builds.find(b => b.projectId === p.id && b.source)?.source; setLaunchProject(p); setSourceRef(previous?.ref || 'HEAD'); setBuildCommand(previous?.command || 'sh ./gradlew --no-daemon --max-workers=4 build'); setArtifactPath(previous?.artifactPath || '') } else { void run(p.id) } }}>{p.repositoryUrl ? 'Собрать репозиторий' : 'Запустить проверку'}</button></article>)}
          {loaded && projects.length > 0 && !projects.some(p => p.name.toLowerCase().includes(filter.toLowerCase())) && <p className="empty">Проекты не найдены.</p>}
        </section>
        <section id="builds" className="panel"><div className="panel-heading"><h2>Последние сборки</h2><span className="muted">до 100 записей</span></div>{builds.length === 0 ? <p className="empty">Здесь появятся очередь и история запусков.</p> : <ul className="build-list">{builds.map(b => <li key={b.id}><button className={`build-row ${selected === b.id ? 'selected' : ''}`} onClick={() => setSelected(b.id)} aria-pressed={selected === b.id}><div><strong>{projects.find(p => p.id === b.projectId)?.name || 'Проект'}</strong><span className="build-meta">{b.id.slice(0, 8)} · {date(b.createdAt)}</span></div><Badge status={b.status}/></button></li>)}</ul>}</section>
      </div><aside>{selected ? <BuildView key={selected} id={selected} onCancel={async id => { await api(`/builds/${id}/cancel`, {}); await refresh() }}/> : <section className="panel guide"><span className="eyebrow">ПЕРВЫЙ ЗАПУСК</span><h2>Проверим всю цепочку</h2><ol><li>Создайте проект</li><li>Запустите проверку</li><li>Откройте шаги и логи</li></ol><p className="muted">Укажите GitHub-репозиторий, выберите ветку и команду. Worker получит точный commit и выполнит сборку с ограничением ресурсов.</p><div className="note">Очередь, шаги и журналы хранятся в PostgreSQL. Для выполнения нужен запущенный worker.</div></section>}</aside></div>
      </>}
      <footer><span>infra-hub / своя мощность</span><span>Сборки · образы · деплой по SSH</span></footer>
    </main>
  </>
}

createRoot(document.getElementById('root')!).render(<StrictMode><App/></StrictMode>)
