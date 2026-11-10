import { useEffect, useState } from 'react'
import { minutes, t, useLanguage } from './i18n'

type Timing = { createdAt?: string; startedAt?: string | null; finishedAt?: string | null; executionMillis?: number | null; status: string }
export function RunTiming({ run, deployment = false }: { run: Timing; deployment?: boolean }) {
  useLanguage()
  const [now, setNow] = useState(Date.now())
  const live = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(run.status)
  useEffect(() => { if (!live) return; const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer) }, [live])
  const created = run.createdAt ? Date.parse(run.createdAt) : null
  const started = run.startedAt ? Date.parse(run.startedAt) : null
  const end = run.finishedAt ? Date.parse(run.finishedAt) : now
  const waiting = created == null ? null : started != null ? Math.max(0, started - created) : run.status === 'QUEUED' ? Math.max(0, now - created) : null
  const elapsed = deployment ? run.executionMillis : started == null ? (run.status === 'QUEUED' ? 0 : null) : Math.max(0, end - started)
  return <dl className="run-timing"><div><dt>{t('Ожидание')}</dt><dd>{minutes(waiting)}</dd></div><div><dt>{t('Выполнение')}</dt><dd>{minutes(elapsed)}</dd></div>{deployment && run.status === 'INTERRUPTED' && <p className="muted">{t('До потери lease; требуется сверка')}</p>}</dl>
}
