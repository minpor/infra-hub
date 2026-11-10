import { useEffect, useRef, useState } from 'react'
import { api, type Build, type Project } from './api'

type App = { id: string; projectId: string; serverId: string; name: string; hostPort: number; containerPort: number; healthPath: string; environmentKeys: string[]; currentDeployment: string | null; kind?: string; configuration?: Record<string,string> }
type Deployment = { id: string; applicationId: string; buildId: string; imageReference: string; status: string; message: string | null; createdAt: string }
type Server = { id: string; name: string; status: string }
const labels: Record<string, string> = { QUEUED: 'В очереди', RUNNING: 'Выполняется', SUCCEEDED: 'Успешно', FAILED: 'Ошибка', INTERRUPTED: 'Нужна сверка', CANCEL_REQUESTED: 'Отменяется', CANCELLED: 'Отменён' }
function acceptsBuild(app: App, build: Build) {
  return build.projectId === app.projectId && build.status === 'SUCCEEDED' && !!build.image &&
    (!app.kind || app.kind === 'STANDALONE' || (build.source?.ref === app.configuration?.ref &&
      (app.kind === 'ANDROID' ? build.source?.artifactPath === 'android-release.jar' : build.source?.artifactPath !== 'android-release.jar')))
}

export function Applications() {
  const [apps, setApps] = useState<App[]>([])
  const [releases, setReleases] = useState<Deployment[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [builds, setBuilds] = useState<Build[]>([])
  const [servers, setServers] = useState<Server[]>([])
  const [pipelines, setPipelines] = useState<{ id: string; application_id: string; ref: string; enabled: boolean; last_sha: string | null; build_id: string | null; deployment_id: string | null; message: string | null }[]>([])
  const [artifacts, setArtifacts] = useState<{ name: string; size: number }[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [projectId, setProjectId] = useState('')
  const [serverId, setServerId] = useState('')
  const [hostPort, setHostPort] = useState('18090')
  const [containerPort, setContainerPort] = useState('8080')
  const [healthPath, setHealthPath] = useState('/')
  const [environment, setEnvironment] = useState('')
  const [choices, setChoices] = useState<Record<string, string>>({})
  const [selected, setSelected] = useState('')
  const [logs, setLogs] = useState('')
  const createId = useRef(crypto.randomUUID())
  const requests = useRef(new Map<string, string>())
  async function refresh() {
    const [a, d, p, b, s, pipelineList] = await Promise.all([api<App[]>('/applications'), api<Deployment[]>('/deployments'), api<Project[]>('/projects'), api<Build[]>('/builds'), api<Server[]>('/servers'), api<typeof pipelines>('/pipelines')])
    setApps(a); setReleases(d); setProjects(p); setBuilds(b); setServers(s); setPipelines(pipelineList); setLoaded(true)
  }
  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try { if (!stopped) await refresh() } catch (e) { if (!stopped) setError((e as Error).message) }
      if (!stopped) timer = setTimeout(poll, 2000)
    }
    void poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [])
  useEffect(() => {
    if (!selected) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    setLogs(''); setArtifacts([])
    async function poll() {
      try {
        const entries = await api<{ text: string }[]>(`/deployments/${selected}/logs`)
        if (!stopped) setLogs(entries.map(e => e.text).join(''))
        const files = await api<{name: string; size: number}[]>(`/deployments/${selected}/artifacts`)
        if (!stopped) setArtifacts(files)
      } catch (e) { if (!stopped) setError((e as Error).message) }
      if (!stopped) timer = setTimeout(poll, 2000)
    }
    void poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [selected])
  async function action(task: () => Promise<void>) {
    setBusy(true); setError('')
    try { await task(); await refresh() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }
  async function launch(app: App, rollback: boolean) {
    const buildId = choices[app.id] || builds.find(b => acceptsBuild(app, b))?.id
    if (!rollback && !buildId) throw new Error('Выберите сборку с опубликованным образом')
    const key = `${app.id}:${rollback ? 'rollback' : buildId}`
    const requestId = requests.current.get(key) || crypto.randomUUID()
    requests.current.set(key, requestId)
    const result = await api<Deployment>(`/applications/${app.id}/${rollback ? 'rollback' : 'deployments'}`, rollback ? { requestId } : { requestId, buildId })
    requests.current.delete(key); setSelected(result.id)
  }
  const selectedRelease = releases.find(d => d.id === selected)
  return <>
    <div className="page-heading"><div className="title"><p className="eyebrow">ВЫПУСКИ</p><h1>Приложения</h1><p className="muted">Выберите собранный образ и разверните его на своём сервере.</p></div><button onClick={() => setOpen(!open)}>＋ Новое приложение</button></div>
    {error && <p role="alert" className="error">{error}</p>}
    {open && <form className="panel project-form" onSubmit={e => { e.preventDefault(); void action(async () => {
      const vars: Record<string, string> = Object.create(null)
      for (const line of environment.split('\n').filter(line => line.trim())) {
        const split = line.indexOf('=')
        if (split < 1) throw new Error('Введите переменные в формате ИМЯ=значение, по одной на строку')
        const key = line.slice(0, split)
        if (Object.hasOwn(vars, key)) throw new Error('Имена переменных не должны повторяться')
        vars[key] = line.slice(split + 1)
      }
      await api('/applications', { id: createId.current, name, projectId, serverId, hostPort: Number(hostPort), containerPort: Number(containerPort), healthPath, environment: vars })
      createId.current = crypto.randomUUID(); setName(''); setEnvironment(''); setOpen(false)
    }) }}>
      <h2>Новое приложение</h2>
      <label>Название приложения<input value={name} onChange={e => setName(e.target.value)} required maxLength={80}/></label>
      <label>Проект<select value={projectId} onChange={e => setProjectId(e.target.value)} required><option value="">Выберите проект</option>{projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
      <label>Сервер приложения<select value={serverId} onChange={e => setServerId(e.target.value)} required><option value="">Выберите сервер</option>{servers.filter(s => s.status === 'READY').map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
      <div className="server-fields"><label>Порт на сервере<input type="number" min={1024} max={65535} required value={hostPort} onChange={e => setHostPort(e.target.value)}/></label><label>Порт контейнера<input type="number" min={1} max={65535} required value={containerPort} onChange={e => setContainerPort(e.target.value)}/></label></div>
      <label>HTTP-путь проверки здоровья<input value={healthPath} onChange={e => setHealthPath(e.target.value)} required maxLength={200} placeholder="/actuator/health"/></label>
      <label>Переменные окружения<textarea value={environment} onChange={e => setEnvironment(e.target.value)} rows={4} placeholder={'SERVER_PORT=8080\nSPRING_PROFILES_ACTIVE=dev'}/></label>
      <p className="muted">Порт доступен на 127.0.0.1 сервера. Проверка ожидает HTTP 2xx за 60 секунд. Настройки сохраняются для всех выпусков этого приложения; значения переменных после создания не отображаются.</p>
      <p className="note">Переключение кратковременно останавливает прежний контейнер. При ошибке Infra Hub запускает его снова. Миграции базы должны быть совместимы с возвратом версии.</p>
      <div className="actions"><button disabled={busy}>Создать приложение</button><button type="button" className="secondary" disabled={busy} onClick={() => setOpen(false)}>Закрыть</button></div>
    </form>}
    {!loaded ? <p className="empty">Загружаем приложения…</p> : apps.length === 0 ? <section className="panel empty"><h2>Первый деплой</h2><p>Опубликуйте Java-образ, подключите сервер и создайте приложение.</p></section> : apps.map(app => {
      const available = builds.filter(b => acceptsBuild(app, b))
      const pipeline = pipelines.find(p => p.application_id === app.id)
      const pipelineRelease = releases.find(d => d.id === pipeline?.deployment_id)
      const current = releases.find(d => d.id === app.currentDeployment)
      const unresolved = releases.some(d => d.applicationId === app.id && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'INTERRUPTED'].includes(d.status))
      const previous = releases.some(d => d.applicationId === app.id && d.status === 'SUCCEEDED' && d.buildId !== current?.buildId)
      return <section className="panel server-card" key={app.id}><div className="panel-heading"><h2>{app.name}</h2><span className="muted">{servers.find(s => s.id === app.serverId)?.name} · {app.kind === 'COMPOSE' ? 'существующий Compose' : app.kind === 'ANDROID' ? 'Android' : `127.0.0.1:${app.hostPort}`}</span></div><div className="server-body">
        <p className="muted">{projects.find(p => p.id === app.projectId)?.name} · контейнер :{app.containerPort} · проверка {app.healthPath}</p>
        {app.kind === 'COMPOSE' && <p className="muted">{app.configuration?.root} · {app.configuration?.url}</p>}
        {app.kind === 'ANDROID' && <p className="muted">Подписанные APK и AAB · {app.configuration?.target} · {app.configuration?.url}</p>}
        {pipeline && <div className="note"><strong>GitHub → Infra Hub · {pipeline.ref}</strong><p>{pipeline.enabled ? 'Автоматическая сборка и выпуск при изменении ветки' : 'Автоматический выпуск приостановлен'} · {pipeline.last_sha?.slice(0,12) || 'ожидаем первый запуск'}</p><p>{pipelineRelease?.message || pipeline.message}</p>{pipeline.build_id && <p><a href={`#builds/${pipeline.build_id}`}>Сборка {pipeline.build_id.slice(0,8)} · {builds.find(b => b.id === pipeline.build_id)?.status || 'история'} — открыть логи</a></p>}<div className="actions"><button disabled={busy || unresolved} onClick={() => void action(async () => { await api(`/pipelines/${pipeline.id}/run`, {}) })}>{pipeline.enabled ? 'Собрать и выпустить' : 'Собрать без выкладки'}</button><button className="secondary" disabled={busy} onClick={() => void action(async () => { await api(`/pipelines/${pipeline.id}/enabled`, { enabled: !pipeline.enabled }) })}>{pipeline.enabled ? 'Приостановить автоматизацию' : 'Включить автоматизацию'}</button></div></div>}
        <p>Текущий выпуск: <code>{app.currentDeployment?.slice(0, 8) || (app.kind === 'COMPOSE' ? 'существующая версия до Infra Hub' : app.kind === 'ANDROID' ? 'существующий APK до Infra Hub' : 'ещё не развёрнут')}</code></p>
        {current && <p style={{ overflowWrap: 'anywhere' }}><code>{current.imageReference}</code></p>}
        {!!app.environmentKeys.length && <p className="muted">Переменные: {app.environmentKeys.join(', ')}</p>}
        <label>Образ для деплоя<select value={choices[app.id] || available[0]?.id || ''} onChange={e => setChoices(old => ({ ...old, [app.id]: e.target.value }))}><option value="" disabled>Нет опубликованных сборок</option>{available.map(b => <option key={b.id} value={b.id}>{b.source?.sha.slice(0, 12)} · {b.id.slice(0, 8)}</option>)}</select></label>
        <div className="actions"><button disabled={busy || unresolved || !available.length} onClick={() => void action(() => launch(app, false))}>Развернуть</button><button className="secondary" disabled={busy || unresolved || !previous || !current || app.kind === 'ANDROID'} onClick={() => void action(() => launch(app, true))}>Вернуть предыдущую версию</button></div>
        <ul className="build-list">{releases.filter(d => d.applicationId === app.id).map(d => <li key={d.id}><button className="build-row" onClick={() => setSelected(d.id)}><span>{d.id.slice(0, 8)} · {new Date(d.createdAt).toLocaleString('ru-RU')}</span><span className={`badge ${d.status.toLowerCase()}`}>{labels[d.status]}</span></button>{['QUEUED', 'RUNNING'].includes(d.status) && <button className="secondary" disabled={busy} onClick={() => void action(async () => { await api(`/deployments/${d.id}/cancel`, {}); setSelected(d.id) })}>Отменить выпуск</button>}{d.status === 'INTERRUPTED' && <button className="secondary" disabled={busy} onClick={() => void action(async () => { await api(`/deployments/${d.id}/retry`, {}); setSelected(d.id) })}>Сверить и повторить</button>}</li>)}</ul>
      </div></section>
    })}
    {selectedRelease && <section className="panel server-card"><div className="panel-heading"><h2>Деплой {selected.slice(0, 8)}</h2><span className={`badge ${selectedRelease.status.toLowerCase()}`}>{labels[selectedRelease.status]}</span></div><div className="server-body"><p style={{ overflowWrap: 'anywhere' }}><code>{selectedRelease.imageReference}</code></p><p role="status">{selectedRelease.message}</p><div className="actions">{artifacts.map(file => <a key={file.name} href={`/api/deployments/${selected}/artifacts/${file.name}`}>{file.name} ({Math.ceil(file.size/1024)} KiB)</a>)}</div><div className="terminal"><pre aria-label="Логи деплоя">{logs || 'Ожидаем исполнитель…'}</pre></div></div></section>}
  </>
}
