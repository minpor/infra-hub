import { useEffect, useState } from 'react'
import { api, type Project } from './api'

type Token = { id: string; name: string; permissions: string[]; projectIds: string[] | null; createdAt: string; expiresAt: string | null; lastUsedAt: string | null; revokedAt: string | null }
type Settings = { url: string; tokens: Token[]; audit: { request_id: string; client: string; tool: string; created_at: string }[] }
const permissions = [
  ['READ', 'Чтение', 'Проекты, окружения, точные SHA, статусы, логи и артефакты.'],
  ['RELEASE', 'Управление выпусками', 'Запуск, отмена сборок и деплоев, сверка прерванного выпуска и откат.'],
  ['CONFIGURE', 'Настройка', 'Включает управление выпусками. Pipeline, автоматизация и создание приложений; для всех проектов — также CPU/RAM и новые проекты.'],
]
export function Mcp() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [projects, setProjects] = useState<Project[]>([])
  const [name, setName] = useState('Hermes')
  const [rights, setRights] = useState(['READ'])
  const [allProjects, setAllProjects] = useState(true)
  const [selected, setSelected] = useState<string[]>([])
  const [days, setDays] = useState(90)
  const [created, setCreated] = useState<{id: string; token: string} | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  async function refresh() { const [value, list] = await Promise.all([api<Settings>('/mcp-settings'), api<Project[]>('/projects')]); setSettings(value); setProjects(list) }
  useEffect(() => { let stopped = false; Promise.all([api<Settings>('/mcp-settings'), api<Project[]>('/projects')]).then(([value, list]) => { if (!stopped) { setSettings(value); setProjects(list) } }).catch(e => { if (!stopped) setError((e as Error).message) }); return () => { stopped = true } }, [])
  async function copy(value: string, label: string) { try { await navigator.clipboard.writeText(value); setMessage(label) } catch { setError('Не удалось скопировать. Разрешите доступ к буферу обмена в браузере.') } }
  const config = (token: string) => `mcp_servers:\n  infra_hub:\n    url: ${JSON.stringify(settings?.url)}\n    headers:\n      Authorization: ${JSON.stringify('Bearer ' + token)}\n    supports_parallel_tool_calls: false\n    timeout: 120\n`
  return <>
    <section className="panel server-card"><div className="panel-heading"><h2>MCP для агентов</h2></div><div className="server-body">
      <p>Подключите Hermes или другой MCP-клиент к Infra Hub. Агент получит схему окружений и инструменты для работы с релизами в пределах выданных прав.</p>
      {settings ? <><label>Адрес MCP<input readOnly value={settings.url}/></label><button type="button" className="secondary" onClick={() => void copy(settings.url, 'Адрес MCP скопирован')}>Скопировать адрес</button></> : <p>Загружаем настройки…</p>}
      <p className="note">Агент передаёт точный SHA после push в GitHub. Уже запущенный автоматический выпуск переиспользуется. Результат сборки и результат деплоя выдаются отдельно. Начатое переключение завершается с проверкой фактического состояния.</p>
    </div></section>
    {error && <p role="alert" className="error">{error}</p>}
    {message && <p role="status">{message}</p>}
    {created && <section className="panel server-card"><div className="panel-heading"><h2>Ключ создан</h2></div><div className="server-body">
      <p>Скопируйте ключ или готовую конфигурацию сейчас. После закрытия или обновления страницы ключ повторно не показывается.</p>
      <div className="actions"><button onClick={() => void copy(created.token, 'Ключ MCP скопирован')}>Скопировать ключ</button><button onClick={() => void copy(config(created.token), 'Конфигурация Hermes скопирована')}>Скопировать для Hermes</button></div>
      <pre className="config-preview">{config('<MCP_TOKEN>')}</pre>
      <p className="muted">Вставьте конфигурацию в настройки MCP клиента. Сам Hermes и его конфигурация здесь не изменяются.</p>
      <button className="secondary" onClick={() => setCreated(null)}>Готово, скрыть ключ</button>
    </div></section>}
    <form className="panel project-form" onSubmit={async e => {
      e.preventDefault(); setBusy(true); setError(''); setMessage('')
      try { if (!allProjects && !selected.length) throw new Error('Выберите хотя бы один проект'); setCreated(await api('/mcp-settings/tokens', { name, permissions: rights, projectIds: allProjects ? null : selected, expiresInDays: days })); await refresh() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
    }}>
      <h2>Новый ключ доступа</h2>
      <label>Название клиента<input required maxLength={80} value={name} onChange={e => setName(e.target.value)} placeholder="Hermes"/></label>
      <fieldset className="permission-options"><legend>Права</legend>{permissions.map(([value, title, description]) => <label key={value}><input type="checkbox" checked={rights.includes(value)} disabled={value === 'READ'} onChange={e => setRights(old => e.target.checked ? [...new Set([...old, value, ...(value === 'CONFIGURE' ? ['RELEASE'] : [])])] : old.filter(p => p !== value && !(value === 'RELEASE' && p === 'CONFIGURE')))}/><span><strong>{title}</strong><small>{description}</small></span></label>)}</fieldset>
      <fieldset className="permission-options"><legend>Проекты</legend><label><input type="checkbox" checked={allProjects} onChange={e => setAllProjects(e.target.checked)}/><span>Все проекты, включая будущие</span></label>{!allProjects && projects.map(p => <label key={p.id}><input type="checkbox" checked={selected.includes(p.id)} onChange={e => setSelected(old => e.target.checked ? [...old, p.id] : old.filter(id => id !== p.id))}/><span>{p.name}</span></label>)}</fieldset>
      <label>Срок действия<select value={days} onChange={e => setDays(Number(e.target.value))}><option value={30}>30 дней</option><option value={90}>90 дней</option><option value={365}>1 год</option><option value={0}>Без срока</option></select></label>
      <p className="muted">Доступ действует самостоятельно, без входа владельца в браузере. Отзыв ключа сразу прекращает новые MCP-запросы. SSH-ключи и ключ подписи Android клиенту не выдаются.</p>
      <button disabled={busy || !settings}>{busy ? 'Создаём…' : 'Создать ключ'}</button>
    </form>
    <section className="panel server-card"><div className="panel-heading"><h2>Ключи доступа</h2></div><div className="server-body">{settings?.tokens.length === 0 ? <p className="muted">Пока нет ключей. Для Hermes выберите нужные права и создайте ключ выше.</p> : settings?.tokens.map(t => {
      const state = t.revokedAt ? 'Отозван' : t.expiresAt && new Date(t.expiresAt).getTime() <= Date.now() ? 'Истёк' : 'Активен'
      return <article className="mcp-token" key={t.id}><div><strong>{t.name}</strong><span className="badge">{state}</span></div><p className="muted">{t.permissions.map(p => permissions.find(r => r[0] === p)?.[1]).join(' · ')} · {t.projectIds ? t.projectIds.map(id => projects.find(p => p.id === id)?.name || id).join(', ') : 'Все проекты'}</p><p className="muted">Последнее обращение: {t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString('ru-RU') : 'ещё не было'} · {t.expiresAt ? `до ${new Date(t.expiresAt).toLocaleDateString('ru-RU')}` : 'без срока'}</p>{state === 'Активен' && <button className="secondary" disabled={busy} onClick={async () => { setBusy(true); setError(''); try { await api(`/mcp-settings/tokens/${t.id}/revoke`, {}); if (created?.id === t.id) setCreated(null); await refresh() } catch (e) { setError((e as Error).message) } finally { setBusy(false) } }}>Отозвать ключ {t.name}</button>}</article>
    })}</div></section>
    {!!settings?.audit.length && <section className="panel server-card"><div className="panel-heading"><h2>Изменения через MCP</h2></div><div className="server-body">{settings.audit.map(row => <p key={row.request_id}><strong>{row.client}</strong> · <code>{row.tool}</code> · {new Date(row.created_at).toLocaleString('ru-RU')}</p>)}</div></section>}
  </>
}
