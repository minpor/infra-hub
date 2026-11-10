import { t, useLanguage } from './i18n'
import { useEffect, useState } from 'react'
import { api } from './api'

type Settings = { cpus: number; memoryGiB: number; maxCpus: number; maxMemoryGiB: number }

export function Runner({ embedded = false }: { embedded?: boolean }) {
  useLanguage()
  const [settings, setSettings] = useState<Settings | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  useEffect(() => { let stopped = false; api<Settings>('/runner-settings').then(value => { if (!stopped) setSettings(value) }).catch(e => { if (!stopped) setError((e as Error).message) }); return () => { stopped = true } }, [])
  return <>
    {!embedded && <div className="page-heading"><div className="title"><p className="eyebrow">{t("РЕСУРСЫ СБОРКИ")}</p><h1>{t("Раннер")}</h1><p className="muted">{t("Лимиты CPU и памяти для одной сборки репозитория.")}</p></div></div>}
    {error && <p role="alert" className="error">{t(error)}</p>}
    {!settings ? <p>{t("Загружаем настройки…")}</p> : <form className="panel project-form" onSubmit={async e => {
      e.preventDefault(); setBusy(true); setError(''); setSaved(false)
      try { setSettings(await api<Settings>('/runner-settings', { cpus: settings.cpus, memoryGiB: settings.memoryGiB })); setSaved(true) } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
    }}>
      <h2>{t("Ресурсы раннера")}</h2>
      <div className="server-fields">
        <label>CPU<input type="number" min={1} max={settings.maxCpus} step={1} required value={settings.cpus} onChange={e => { setSaved(false); setSettings({ ...settings, cpus: Number(e.target.value) }) }}/></label>
        <label>{t("Память, GiB")}<input type="number" min={2} max={settings.maxMemoryGiB} step={1} required value={settings.memoryGiB} onChange={e => { setSaved(false); setSettings({ ...settings, memoryGiB: Number(e.target.value) }) }}/></label>
      </div>
      <p className="muted">{t("Доступно до")} {settings.maxCpus}  {t("CPU и")} {settings.maxMemoryGiB}  {t("GiB на сборку. Одновременно выполняется одна тяжёлая сборка; часть ресурсов сервера оставлена панели и служебным процессам.")}</p>
      <p className="note">{t("Изменения применяются при запуске следующей сборки. Уже работающая сборка продолжает использовать выделенные ей ресурсы. Память включает Gradle, Kotlin, браузеры и временные файлы.")}</p>
      <div className="actions"><button disabled={busy}>{busy ? t("Сохраняем…") : t("Сохранить настройки")}</button></div>
      {saved && <p role="status">{t("Настройки сохранены. Следующая сборка получит")} {settings.cpus}  {t("CPU и")} {settings.memoryGiB}  {t("GiB памяти.")}</p>}
    </form>}
  </>
}
