import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import { t, useLanguage } from './i18n'
import { SourceFileEditor, NativeBuildEditor, type SourceDraft, type SavedSource } from './SourceFileEditor'

type SourceFile = { path: string; origin: 'REPOSITORY' | 'BUILDER' | 'DEPLOYER'; stage: string }
type Configuration = { yaml: string | null; repositoryYaml: string | null; ref: string; files: SourceFile[]; command?: string | null; artifactPath?: string | null }
type Source = { path: string; origin: string; content: string; sha256: string; sha: string | null; references: SourceFile[] }
export type ConfigurationTarget = { id: string; application_id: string; ref: string; enabled: boolean; environment?: 'dev' | 'prod' | null; config_path?: string | null; name: string }
const sourceKey = (file: SourceFile) => `${file.origin}:${file.path}`

function ConfigurationFiles({ target, onEditing }: { target: ConfigurationTarget; onEditing: (editing: boolean) => void }) {
  useLanguage()
  const [configuration, setConfiguration] = useState<Configuration | null>(null)
  const [files, setFiles] = useState<SourceFile[]>([])
  const [selected, setSelected] = useState('settings')
  const [source, setSource] = useState<Source | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [editor, setEditor] = useState<SourceDraft | 'build' | null>(null)
  const editing = editor !== null
  const [reload, setReload] = useState(0)
  const [sourceAttempt, setSourceAttempt] = useState(0)
  const [message, setMessage] = useState('')
  const [catalog, setCatalog] = useState<SourceFile[] | null>(null)
  const [catalogBusy, setCatalogBusy] = useState(false)
  const [catalogError, setCatalogError] = useState('')
  const [openPath, setOpenPath] = useState('')
  const [catalogTruncated, setCatalogTruncated] = useState(false)
  const [buildCommandDraft, setBuildCommandDraft] = useState<string | undefined>()
  const generation = useRef(0)
  const repositorySha = useRef<string | null>(null)
  useEffect(() => {
    let stopped = false
    setLoading(true); setError(''); setConfiguration(null); repositorySha.current = null
    api<Configuration>(`/pipelines/${target.id}/configuration`).then(value => {
      if (stopped) return
      setConfiguration(value); setFiles(value.files)
      setSelected(value.repositoryYaml ? `REPOSITORY:${value.repositoryYaml}` : 'settings')
    }).catch(e => { if (!stopped) setError((e as Error).message) }).finally(() => { if (!stopped) setLoading(false) })
    return () => { stopped = true }
  }, [target.id, reload])
  useEffect(() => {
    const request = ++generation.current
    if (!configuration || selected === 'settings') { setSource(null); return }
    const file = files.find(f => sourceKey(f) === selected)
    if (!file) return
    setLoading(true); setSource(null); setError('')
    const params = new URLSearchParams({ origin: file.origin, path: file.path })
    if (file.origin === 'REPOSITORY' && repositorySha.current) params.set('sha', repositorySha.current)
    api<Source>(`/pipelines/${target.id}/source?${params}`).then(value => {
      if (request !== generation.current) return
      if (value.sha) repositorySha.current = value.sha
      setSource(value)
      setFiles(old => [...old, ...value.references.filter(next => !old.some(existing => sourceKey(existing) === sourceKey(next)))])
    }).catch(e => { if (request === generation.current) setError((e as Error).message) }).finally(() => { if (request === generation.current) setLoading(false) })
    return () => { generation.current++ }
  }, [selected, configuration, target.id, reload, sourceAttempt])
  const current = files.find(file => sourceKey(file) === selected)
  const content = selected === 'settings' ? configuration?.yaml : source?.content
  function edit(value: SourceDraft | 'build' | null) { setEditor(value); onEditing(value !== null) }
  function openFile(file: SourceFile) {
    setFiles(old => old.some(existing => sourceKey(existing) === sourceKey(file)) ? old : [...old, file])
    setSelected(sourceKey(file)); setSource(null); setSourceAttempt(value => value + 1); setError(''); setMessage('')
  }
  function saved(value: SavedSource) {
    repositorySha.current = value.sha
    setFiles(old => [...new Map([...old, { path: value.path, origin: 'REPOSITORY' as const, stage: 'source' }, ...value.references].map(file => [sourceKey(file), file])).values()])
    setSelected(`REPOSITORY:${value.path}`); setSource(value); edit(null); setMessage(t('Файл сохранён в ветке {0}. Коммит: {1}', [target.ref, value.sha.slice(0, 12)]))
    if (catalog) setCatalog(old => old?.some(file => file.path === value.path) ? old : [...old || [], { path: value.path, origin: 'REPOSITORY', stage: 'source' }])
  }
  return <>
    {configuration && !configuration.repositoryYaml && <p className="configuration-notice">{t('Этот сценарий сохранён в Infra Hub. YAML-файл репозитория не подключён. Ниже — действующие настройки и исходники скриптов.')}</p>}
    <div className="configuration-actions actions"><button type="button" disabled={editing || !configuration} onClick={() => edit({ path: '', content: '', sha: repositorySha.current, create: true })}>{t('＋ Новый файл')}</button><button type="button" className="secondary" disabled={editing || catalogBusy} onClick={async () => {
      if (catalog) { setCatalog(null); return }
      setCatalogBusy(true); setCatalogError('')
      try { const value = await api<{ sha: string; files: SourceFile[]; truncated: boolean }>(`/pipelines/${target.id}/repository-files`); repositorySha.current = value.sha; setCatalog(value.files); setCatalogTruncated(value.truncated) } catch (e) { setCatalogError((e as Error).message) } finally { setCatalogBusy(false) }
    }}>{catalogBusy ? t('Загружаем файлы…') : t('Открыть файл репозитория')}</button></div>
    {catalogError && <p className="error" role="alert">{t(catalogError)}</p>}
    {catalog && <div className="repository-file-picker"><label>{t('Файлы репозитория')}<select aria-label={t('Файлы репозитория')} value="" disabled={editing} onChange={e => { const file = catalog.find(file => file.path === e.target.value); if (file) openFile(file) }}><option value="">{t('Выберите YAML или скрипт')}</option>{catalog.map(file => <option key={file.path} value={file.path}>{file.path}</option>)}</select></label><form onSubmit={e => { e.preventDefault(); if (openPath.trim()) openFile({ path: openPath.trim(), origin: 'REPOSITORY', stage: 'source' }) }}><label>{t('Открыть по пути')}<input value={openPath} onChange={e => setOpenPath(e.target.value)} disabled={editing} maxLength={300} placeholder="deploy/script.sh"/></label><button type="submit" className="secondary" disabled={editing || !openPath.trim()}>{t('Открыть файл')}</button></form>{catalogTruncated && <p className="muted">{t('Показана часть файлов. Остальные можно открыть по точному пути.')}</p>}</div>}
    {message && <p className="yaml-success" role="status">{message}</p>}
    <div className="configuration-browser">
      <nav className="configuration-files" aria-label={t('Файлы конфигурации')}>
        {configuration?.yaml && <button type="button" className={selected === 'settings' ? 'selected' : ''} aria-pressed={selected === 'settings'} disabled={editing} onClick={() => { setSelected('settings'); setError(''); setLoading(false) }}><span aria-hidden="true">≡</span><span>{t('Настройки сценария')}<small>{t('Сохранено в Infra Hub')}</small></span></button>}
        {files.map(file => <button type="button" key={sourceKey(file)} title={file.path} className={selected === sourceKey(file) ? 'selected' : ''} aria-pressed={selected === sourceKey(file)} disabled={editing} onClick={() => setSelected(sourceKey(file))}><span aria-hidden="true">{file.path.endsWith('.yml') || file.path.endsWith('.yaml') ? 'Y' : '⌘'}</span><span>{file.path.split('/').at(-1)}<small>{t(file.origin === 'REPOSITORY' ? 'Из репозитория' : file.origin === 'BUILDER' ? 'Скрипт сборки' : 'Скрипт деплоя')}</small></span></button>)}
      </nav>
      <div className="configuration-document">
        <div className="configuration-document-heading"><div><strong>{selected === 'settings' ? t('Действующие настройки') : current?.path}</strong><p>{selected === 'settings' ? t('Представление сохранённых настроек в YAML') : source?.sha ? `${target.ref} · ${source.sha.slice(0, 12)}` : t(current?.origin === 'REPOSITORY' ? 'Файл выбранной ветки' : 'Исходник из установленной версии Infra Hub')}</p></div>
          {!editing && !loading && !error && <div className="actions">{selected === 'settings' && configuration?.command != null ? <button className="secondary" type="button" onClick={() => { setBuildCommandDraft(undefined); edit('build') }}>{t('Редактировать настройки')}</button> : current?.origin === 'REPOSITORY' && source ? <><button className="secondary" type="button" onClick={() => edit({ path: source.path, content: source.content, sha: source.sha, create: false })}>{t('Редактировать файл')}</button>{configuration?.command != null && /\.(sh|py|mjs|cjs|js)$/.test(source.path) && <button type="button" className="secondary" onClick={() => {
            const builtin = `/opt/hub/${source.path.split('/').at(-1)}`
            const interpreter = source.path.endsWith('.sh') ? 'bash' : source.path.endsWith('.py') ? 'python3' : 'node'
            setBuildCommandDraft(configuration.command!.includes(builtin) ? configuration.command!.replaceAll(builtin, source.path) : `${interpreter} ${source.path}`); edit('build')
          }}>{t('Использовать для сборки')}</button>}</> : source && current && <button className="secondary" type="button" onClick={() => edit({ path: `.infra-hub/scripts/${current.path.split('/').at(-1)}`, content: source.content, sha: repositorySha.current, create: true })}>{t('Скопировать в проект')}</button>}</div>}
        </div>
        {loading && <p className="configuration-loading" role="status">{t('Загружаем содержимое…')}</p>}
        {error && <div className="configuration-loading"><p className="error" role="alert">{t(error)}</p><button className="secondary" type="button" onClick={() => { if (configuration) setSourceAttempt(sourceAttempt + 1); else setReload(reload + 1) }}>{t('Повторить загрузку')}</button></div>}
        {!loading && !error && content != null && <pre className="configuration-code" aria-label={t('Содержимое конфигурации')}><code>{content}</code></pre>}
        {source && <div className="configuration-checksum">SHA-256 · {source.sha256}</div>}
      </div>
    </div>
    {editor && editor !== 'build' && <SourceFileEditor pipelineId={target.id} branch={target.ref} automated={target.enabled} initial={editor} onClose={() => edit(null)} onSaved={saved}/>}
    {editor === 'build' && configuration?.command != null && <NativeBuildEditor pipelineId={target.id} command={configuration.command} initialCommand={buildCommandDraft} artifactPath={configuration.artifactPath || ''} onClose={() => edit(null)} onSaved={() => { edit(null); setMessage(t('Настройки сборки сохранены.')); setReload(value => value + 1) }}/>}
  </>
}

export function ProjectConfiguration({ targets, loading, error, onRetry, onLaunch, onEditing }: {
  targets: ConfigurationTarget[]; loading: boolean; error: string; onRetry: () => void; onLaunch: (target: ConfigurationTarget) => void; onEditing: (editing: boolean) => void
}) {
  useLanguage()
  const [selectedId, setSelectedId] = useState('')
  const [editing, setEditing] = useState(false)
  const ordered = [...targets].sort((a, b) => (a.environment === 'dev' ? 0 : a.environment === 'prod' ? 1 : 2) - (b.environment === 'dev' ? 0 : b.environment === 'prod' ? 1 : 2) || a.name.localeCompare(b.name))
  const target = ordered.find(p => p.id === selectedId) || ordered[0]
  return <section className="project-configuration" aria-label={t('Настройки и скрипты')}>
    <div className="configuration-intro"><div><h3>{t('Что выполняется при деплое')}</h3><p className="muted">{t('Выберите сценарий. Слева — настройки, YAML и скрипты, справа — их полное содержимое.')}</p></div>{target && <button type="button" disabled={editing} onClick={() => onLaunch(target)}>{t('Перейти к запуску')} →</button>}</div>
    {loading ? <p className="configuration-loading" role="status">{t('Загружаем сценарии…')}</p> : error ? <p className="error" role="alert">{t(error)} <button className="text-button" type="button" onClick={onRetry}>{t('Повторить')}</button></p> : !target ? <div className="launch-empty"><strong>{t('Сценарии деплоя ещё не подключены')}</strong><p>{t('Подключите YAML в приложениях, чтобы смотреть и редактировать его здесь.')}</p><a href="#applications">{t('Подключить YAML')} →</a></div> : <>
      <div className="configuration-target"><label>{t('Сценарий деплоя')}<select disabled={editing} value={target.id} onChange={e => setSelectedId(e.target.value)}>{ordered.map(p => <option key={p.id} value={p.id}>{p.environment?.toUpperCase() || t('Без окружения')} · {p.name} · {p.ref}</option>)}</select></label><span className="badge">{target.config_path ? t('YAML из репозитория') : t('Настройки Infra Hub')}</span></div>
      <ConfigurationFiles key={target.id} target={target} onEditing={value => { setEditing(value); onEditing(value) }}/>
    </>}
  </section>
}
