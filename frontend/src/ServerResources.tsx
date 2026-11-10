import { useEffect, useState } from 'react'
import { api } from './api'
import { t, number, locale, useLanguage } from './i18n'

type Resources = { hostname: string; os: string; architecture: string; cpuCount: number; cpuUsagePercent: number | null; memoryTotalBytes: number; memoryUsedBytes: number; diskTotalBytes: number; diskUsedBytes: number; loadAverage: number; uptimeSeconds: number }
type Observation = { value: Resources | null; observedAt: string | null; attemptedAt: string | null; error: string | null }
function Meter({ name, value, detail }: { name: string; value: number | null; detail: string }) {
  return <div className="resource-meter"><div><strong>{name}</strong><span>{detail}</span></div>{value == null ? <p className="muted">{t('Нет данных')}</p> : <progress aria-label={name} max={100} value={Math.min(100, Math.max(0, value))}/>}</div>
}
export function ServerResources({ id, trusted }: { id: string; trusted: boolean }) {
  useLanguage()
  const [observation, setObservation] = useState<Observation | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    let stopped = false
    api<Observation>(`/servers/${id}/resources`).then(value => { if (!stopped) setObservation(value) }).catch(() => { if (!stopped) setError('Не удалось загрузить ресурсы сервера.') })
    const timer = setInterval(() => setNow(Date.now()), 10000)
    return () => { stopped = true; clearInterval(timer) }
  }, [id])
  const value = observation?.value
  const stale = !!observation?.error || !!observation?.observedAt && now - Date.parse(observation.observedAt) > 5 * 60000
  const capacity = (used: number, total: number) => `${number(used / 2 ** 30)} / ${number(total / 2 ** 30)} GiB`
  return <section className="host-resources" aria-label={t('Ресурсы сервера')}>
    <div className="resource-heading"><h3>{t('Ресурсы сервера')}</h3><button type="button" className="secondary" disabled={busy || !trusted} onClick={async () => {
      setBusy(true); setError('')
      try { setObservation(await api<Observation>(`/servers/${id}/resources`, {})); setNow(Date.now()) } catch { setError('Не удалось обновить ресурсы сервера.') } finally { setBusy(false) }
    }}>{busy ? t('Измеряем…') : t('Обновить ресурсы')}</button></div>
    {!value ? <p className="muted">{t('Пока нет измерений. Подтвердите SSH-ключ и обновите ресурсы.')}</p> : <>
      <p className="host-meta">{value.os} · {value.architecture} · {value.hostname} · {value.cpuCount} CPU</p>
      <div className="resource-grid">
        <Meter name="CPU" value={value.cpuUsagePercent} detail={value.cpuUsagePercent == null ? '—' : `${number(value.cpuUsagePercent)}%`}/>
        <Meter name={t('Память')} value={value.memoryUsedBytes / value.memoryTotalBytes * 100} detail={capacity(value.memoryUsedBytes, value.memoryTotalBytes)}/>
        <Meter name={t('Диск /')} value={value.diskUsedBytes / value.diskTotalBytes * 100} detail={capacity(value.diskUsedBytes, value.diskTotalBytes)}/>
      </div>
      <p className="muted">{t('Нагрузка за 1 мин')}: {number(value.loadAverage, 2)} · {t('Работает')}: {number(value.uptimeSeconds / 3600)} {t('ч')}</p>
    </>}
    {observation?.observedAt && <p className={`observation ${stale ? 'stale' : ''}`} role="status">{stale ? t('Данные устарели') : t('Последнее измерение')}: {new Date(observation.observedAt).toLocaleString(locale())}</p>}
    {(error || observation?.error) && <p className="error" role="alert">{t(error || 'Не удалось получить новые данные. Последнее успешное измерение сохранено. Проверьте SSH и Python 3 на сервере.')}</p>}
    <p className="muted resource-note">{t('CPU — короткий замер; память — занятая без доступного кеша; диск — файловая система /. Обновление по запросу, данные старше 5 минут отмечаются устаревшими.')}</p>
  </section>
}
