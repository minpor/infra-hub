import { t, useLanguage } from './i18n'
import { useEffect, useState } from 'react'
import { api, type Project } from './api'

type Settings = { cpus: number; memoryGiB: number; dependencyCacheEnabled: boolean; maxCpus: number; maxMemoryGiB: number; inherited: boolean }

export function Runner({ embedded = false }: { embedded?: boolean }) {
  useLanguage()
  const [settings, setSettings] = useState<Settings | null>(null)
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const settingsPath = projectId ? `/projects/${projectId}/runner-settings` : '/runner-settings'
  useEffect(() => { let stopped = false; api<Project[]>('/projects').then(value => { if (!stopped) setProjects(value) }).catch(e => { if (!stopped) setError((e as Error).message) }); return () => { stopped = true } }, [])
  useEffect(() => {
    let stopped = false
    setSettings(null); setSaved(false); setError('')
    api<Settings>(settingsPath).then(value => { if (!stopped) setSettings(value) }).catch(e => { if (!stopped) setError((e as Error).message) })
    return () => { stopped = true }
  }, [settingsPath])
  return <>
    {!embedded && <div className="page-heading"><div className="title"><p className="eyebrow">{t('РЕСУРСЫ СБОРКИ')}</p><h1>{t('Раннер')}</h1></div></div>}
    <div className="panel runner-settings">
      <div className="panel-heading"><div><span className="eyebrow">{t('ПОД ВАШУ НАГРУЗКУ')}</span><h2>{t('Ресурсы и кэш сборки')}</h2><p className="muted">{t('Больше мощности для Android, меньше — для простых сервисов.')}</p></div></div>
      <div className="runner-scope"><label>{t('Настройки для')}<select value={projectId} disabled={busy} onChange={e => setProjectId(e.target.value)}><option value="">{t('Все проекты · по умолчанию')}</option>{projects.map(project => <option value={project.id} key={project.id}>{project.name}</option>)}</select></label>{settings && <span className="badge">{projectId ? settings.inherited ? t('Наследует общие настройки') : t('Собственные настройки проекта') : t('Общие настройки')}</span>}</div>
      {error && <p role="alert" className="error">{t(error)}</p>}
      {!settings ? <p className="muted runner-loading">{error ? t('Не удалось загрузить настройки.') : t('Загружаем настройки…')}</p> : <form className="project-form runner-form" onSubmit={async e => {
        e.preventDefault(); setBusy(true); setError(''); setSaved(false)
        try { setSettings(await api<Settings>(settingsPath, { cpus: settings.cpus, memoryGiB: settings.memoryGiB, dependencyCacheEnabled: settings.dependencyCacheEnabled })); setSaved(true) } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
      }}>
        <fieldset disabled={busy} className="runner-fields"><legend className="sr-only">{t('Ресурсы раннера')}</legend><div className="runner-resource-inputs">
          <label><span>CPU</span><input aria-label="CPU" type="number" min={1} max={settings.maxCpus} step={1} required value={settings.cpus} onChange={e => { setSaved(false); setSettings({ ...settings, cpus: Number(e.target.value) }) }}/><small>{t('До {0} CPU на сборку', [settings.maxCpus])}</small></label>
          <label><span>{t('Память, GiB')}</span><input aria-label={t('Память, GiB')} type="number" min={2} max={settings.maxMemoryGiB} step={1} required value={settings.memoryGiB} onChange={e => { setSaved(false); setSettings({ ...settings, memoryGiB: Number(e.target.value) }) }}/><small>{t('До {0} GiB на сборку', [settings.maxMemoryGiB])}</small></label>
        </div>
        <label className="cache-option"><input type="checkbox" role="switch" checked={settings.dependencyCacheEnabled ?? true} onChange={e => { setSaved(false); setSettings({ ...settings, dependencyCacheEnabled: e.target.checked }) }}/><span><strong>{t('Кэш зависимостей')}</strong><small>{t('Gradle и библиотеки сохраняются между запусками. У каждого проекта своё хранилище; npm-кэш тоже переиспользуется.')}</small></span><span className="badge">{settings.dependencyCacheEnabled ? t('Включён') : t('Выключен')}</span></label>
        </fieldset>
        <p className="muted">{t('Выключение кэша запускает чистую сборку и сохраняет прежний кэш для повторного включения. В YAML можно переопределить выбор через build.cache: true или false.')}</p>
        <p className="note">{t('Изменения применяются при запуске следующей сборки. Уже работающая сборка продолжает использовать выделенные ей ресурсы. Память включает Gradle, Kotlin, браузеры и временные файлы.')}</p>
        <div className="actions"><button disabled={busy}>{busy ? t('Сохраняем…') : t('Сохранить настройки')}</button>{projectId && !settings.inherited && <button type="button" className="secondary" disabled={busy} onClick={async () => {
          setBusy(true); setError(''); setSaved(false)
          try { setSettings(await api<Settings>(`${settingsPath}/reset`, {})); setSaved(true) } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
        }}>{t('Использовать общие настройки')}</button>}</div>
        {saved && <p className="yaml-success" role="status">{t('Настройки сохранены. Следующая сборка получит')} {settings.cpus} {t('CPU и')} {settings.memoryGiB} {t('GiB памяти.')}</p>}
      </form>}
    </div>
  </>
}
