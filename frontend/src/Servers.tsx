import { t, useLanguage, locale } from './i18n'
import { useEffect, useRef, useState } from 'react'
import { ServerResources } from './ServerResources'
import { api } from './api'

type Server = { id: string; name: string; host: string; port: number; username: string; publicKey: string; hostKey: string | null; fingerprint: string | null; status: 'PENDING' | 'READY' | 'SSH_ONLY' | 'FAILED'; message: string | null; checkedAt: string | null }
type Identity = { key: string; fingerprint: string }
const labels = { PENDING: 'Ожидает подключения', READY: 'SSH и Docker доступны', SSH_ONLY: 'SSH доступен', FAILED: 'Ошибка подключения' }

function ServerCard({ server, onUpdate }: { server: Server; onUpdate: (server: Server) => void }) {
  useLanguage()
  const [identity, setIdentity] = useState<Identity | null>(null)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [expanded, setExpanded] = useState(server.status === 'PENDING' || server.status === 'FAILED')
  const command = `mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && (grep -qF '${server.publicKey}' ~/.ssh/authorized_keys || printf '%s\\n' 'restrict ${server.publicKey}' >> ~/.ssh/authorized_keys)`
  async function action(name: string, task: () => Promise<void>) {
    setBusy(name); setError('')
    try { await task() } catch (e) { setError((e as Error).message) } finally { setBusy('') }
  }
  return <article className="panel server-card" aria-label={t("Сервер {0}", [server.name])}>
    <div className="panel-heading"><div><h2>{server.name}</h2><p className="muted">{server.username}@{server.host}:{server.port}</p></div><span className={`badge ${server.status === 'READY' ? 'succeeded' : server.status === 'FAILED' ? 'failed' : 'queued'}`}>{t(labels[server.status])}</span></div>
    <div className="server-body">
      <div className="actions"><button className="secondary" onClick={() => setExpanded(!expanded)} aria-expanded={expanded}>{expanded ? t("Скрыть настройку") : t("Настройка SSH")}</button><button disabled={!!busy || !server.hostKey} onClick={() => void action('check', async () => onUpdate(await api<Server>(`/servers/${server.id}/check`, {})))}>{busy === 'check' ? t("Проверяем SSH и Docker…") : t("Проверить подключение")}</button></div>
      <ServerResources id={server.id} trusted={!!server.hostKey}/>
      {error && <p role="alert" className="error">{t(error)}</p>}
      {expanded && <div className="server-setup">
        <section><h3>{t("1. Разрешите доступ Infra Hub")}</h3><p className="muted">{t("Откройте свой терминал и войдите на сервер как")} <strong>{server.username}</strong>{t(". Выполните команду ниже: она добавит отдельный публичный ключ Infra Hub. Приватный ключ остаётся на сервере панели.")}</p>
          <p className="note">{t("Ключ даёт доступ с правами пользователя")} {server.username}{t(". Для подключения используйте выделенную учётную запись, если не нужен полный доступ владельца сервера.")}</p>
          <label>{t("Команда добавления ключа")}<textarea readOnly value={command} rows={4}/></label><button className="secondary" onClick={() => void action('copy', async () => { await navigator.clipboard.writeText(command); setCopied(true) })}>{copied ? t("Скопировано") : t("Скопировать команду")}</button>
        </section>
        <section><h3>{t("2. Подтвердите сервер")}</h3>{server.fingerprint ? <><p className="muted">{t("Ключ сервера закреплён. При его изменении SSH-проверка остановится.")}</p><code className="fingerprint">{server.fingerprint}</code></> : <>
          <p className="muted">{t("Получите отпечаток и сравните его с результатом этой команды в уже доверенном SSH-сеансе сервера:")}</p><code className="fingerprint">ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256</code>
          <button className="secondary" disabled={!!busy} onClick={() => void action('scan', async () => setIdentity(await api<Identity>(`/servers/${server.id}/host-key`, {})))}>{busy === 'scan' ? t("Получаем отпечаток…") : t("Получить отпечаток")}</button>
          {identity && <div className="host-confirm"><code className="fingerprint">{identity.fingerprint}</code><p className="muted">{t("Подтверждайте только если отпечатки совпадают.")}</p><button disabled={!!busy} onClick={() => void action('trust', async () => { onUpdate(await api<Server>(`/servers/${server.id}/trust`, { key: identity.key })); setIdentity(null) })}>{t("Отпечаток совпадает — доверять")}</button></div>}
        </>}</section>
        <section><h3>{t("3. Проверьте подключение")}</h3><p className="muted">{t("Кнопка «Проверить подключение» проверит SSH, Docker и Compose с сервера Infra Hub. Результат сохранится в карточке. Для выкладки опубликованного образа создайте приложение в разделе «Приложения».")}</p></section>
      </div>}
      {server.checkedAt && <div className="server-result" role="status"><p className="muted">{t("Последняя проверка:")} {new Date(server.checkedAt).toLocaleString(locale())}</p><pre>{server.status === 'READY' ? t("Вход по SSH выполнен. Docker Engine и Docker Compose доступны.") : t(server.message?.replace(/^INFRA_HUB_.*$/gm, ''))}</pre></div>}
    </div>
  </article>
}

export function Servers() {
  useLanguage()
  const [servers, setServers] = useState<Server[]>([])
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [name, setName] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState('22')
  const [username, setUsername] = useState('')
  const requestId = useRef(crypto.randomUUID())
  const input = useRef<HTMLInputElement>(null)
  async function refresh() {
    setError('')
    try { setServers(await api<Server[]>('/servers')); setLoaded(true) } catch (e) { setError((e as Error).message) }
  }
  useEffect(() => { void refresh() }, [])
  useEffect(() => { if (open) input.current?.focus() }, [open])
  return <>
    <div className="page-heading"><div className="title"><p className="eyebrow">{t("ИНФРАСТРУКТУРА")}</p><h1>{t("Серверы")}</h1><p className="muted">{t("Подключите свой Linux-сервер по SSH и проверьте доступ для выкладок приложений.")}</p></div><button onClick={() => setOpen(!open)} aria-expanded={open}>{t("＋ Подключить сервер")}</button></div>
    {error && <div className="error" role="alert">{t(error)} <button className="secondary" onClick={() => void refresh()}>{t("Обновить список")}</button></div>}
    {open && <form className="panel project-form" onSubmit={async e => {
      e.preventDefault(); setBusy(true); setError('')
      try {
        const server = await api<Server>('/servers', { id: requestId.current, name, host, port: Number(port), username })
        setServers(old => [server, ...old.filter(s => s.id !== server.id)]); setLoaded(true); setOpen(false)
        requestId.current = crypto.randomUUID(); setName(''); setHost(''); setUsername(''); setPort('22')
      } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
    }}>
      <h2>{t("Подключение по SSH")}</h2>
      <label>{t("Название сервера")}<input ref={input} value={name} onChange={e => setName(e.target.value)} placeholder={t("Например, minpor-dev")} maxLength={80} required/></label>
      <div className="server-fields"><label>{t("IP-адрес или DNS-имя")}<input value={host} onChange={e => setHost(e.target.value)} placeholder={t("203.0.113.10 или dev.example.com")} maxLength={253} required autoCapitalize="none" spellCheck={false}/></label><label>{t("SSH-порт")}<input type="number" min={1} max={65535} value={port} onChange={e => setPort(e.target.value)} required/></label><label>{t("SSH-пользователь")}<input value={username} onChange={e => setUsername(e.target.value)} placeholder="deploy" maxLength={64} required autoCapitalize="none" spellCheck={false}/></label></div>
      <p className="note">{t("Если вы подключаетесь командой")} <code>ssh minpor-dev</code>{t(", это может быть алиас на вашем компьютере. Выполните там")} <code>ssh -G minpor-dev</code>  {t("и перенесите значения")} <code>hostname</code>, <code>port</code>  {t("и")} <code>user</code>{t(". Панель подключается со своего сервера и не видит ваш SSH-конфиг.")}</p>
      <p className="muted">{t("После добавления появятся публичный ключ и инструкция. Пароль и ваш личный приватный ключ не нужны.")}</p>
      <div className="actions"><button disabled={busy}>{busy ? t("Создаём ключ…") : t("Добавить и настроить SSH")}</button><button className="secondary" type="button" disabled={busy} onClick={() => setOpen(false)}>{t("Закрыть")}</button></div>
    </form>}
    {!loaded ? <p className="empty">{error ? t("Не удалось загрузить серверы.") : t("Загружаем серверы…")}</p> : servers.length === 0 && !open ? <section className="panel empty"><span className="empty-mark">＋</span><h2>{t("Подключите тестовый стенд")}</h2><p>{t("Адрес и пользователь → публичный ключ → проверка SSH и Docker.")}</p><button onClick={() => setOpen(true)}>{t("Подключить первый сервер")}</button></section> : servers.map(server => <ServerCard key={server.id} server={server} onUpdate={updated => setServers(old => old.map(s => s.id === updated.id ? updated : s))}/>)}
  </>
}
