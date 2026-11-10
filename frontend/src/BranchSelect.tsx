import { useEffect, useId, useRef, useState } from 'react'
import { api } from './api'
import { t, useLanguage } from './i18n'

type Branches = { defaultBranch: string; branches: { name: string; sha: string }[]; hasMore: boolean }

export function BranchSelect({ projectId, value, onChange, knownRefs = [], label = 'Ветка', disabled = false, allowDefault = true }: {
  projectId: string; value: string; onChange: (value: string) => void; knownRefs?: string[]; label?: string; disabled?: boolean; allowDefault?: boolean
}) {
  useLanguage()
  const id = useId()
  const [data, setData] = useState<Branches | null>(null)
  const [manual, setManual] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const generation = useRef(0)
  useEffect(() => {
    if (!projectId) return
    let stopped = false
    generation.current++
    setData(null); setError(''); setLoading(true)
    api<Branches>(`/projects/${projectId}/branches`).then(next => {
      if (!stopped) setData(next)
    }).catch(() => {
      if (!stopped) setError('Не удалось загрузить ветки. Можно указать ветку вручную или повторить запрос.')
    }).finally(() => { if (!stopped) setLoading(false) })
    return () => { stopped = true; generation.current++ }
  }, [projectId, attempt])
  const refs = [...new Set([...(data?.branches.map(b => b.name) || []), ...knownRefs, value].filter(ref => ref && ref !== 'HEAD'))]
  return <div className="branch-picker">
    <div className="field-heading"><label htmlFor={id}>{t(manual ? 'Ветка, тег или commit' : label)}</label><button type="button" className="text-button" disabled={disabled} onClick={() => setManual(!manual)}>{manual ? t('Выбрать из списка') : t('Ввести вручную')}</button></div>
    {manual ? <input id={id} value={value} onChange={e => onChange(e.target.value)} required maxLength={200} disabled={disabled}/> : <select id={id} value={value} onChange={e => onChange(e.target.value)} required disabled={disabled}>
      {allowDefault && <option value="HEAD">{data?.defaultBranch ? t('По умолчанию · {0}', [data.defaultBranch]) : t('Ветка по умолчанию')}</option>}
      {refs.map(ref => <option key={ref} value={ref}>{ref}{ref === data?.defaultBranch ? ` · ${t('основная')}` : ''}</option>)}
    </select>}
    {loading && <small className="muted" role="status">{t('Загружаем ветки из GitHub…')}</small>}
    {error && <p className="branch-error" role="status">{t(error)} <button type="button" className="text-button" onClick={() => setAttempt(attempt + 1)} disabled={loading || disabled}>{t('Повторить')}</button></p>}
    {data?.hasMore && <button type="button" className="text-button" disabled={loading || disabled} onClick={async () => {
      const requestGeneration = generation.current
      setLoading(true); setError('')
      try {
        const next = await api<Branches>(`/projects/${projectId}/branches?page=${Math.floor(data.branches.length / 100) + 1}`)
        if (generation.current === requestGeneration) setData({ ...next, branches: [...data.branches, ...next.branches] })
      } catch { if (generation.current === requestGeneration) setError('Не удалось загрузить ветки. Можно указать ветку вручную или повторить запрос.') }
      finally { if (generation.current === requestGeneration) setLoading(false) }
    }}>{t('Ещё ветки')}</button>}
  </div>
}
