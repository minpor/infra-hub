import { Runner } from './Runner'
import { Mcp } from './Mcp'

export function Settings({ mcp }: { mcp: boolean }) {
  return <>
    <div className="page-heading"><div className="title"><p className="eyebrow">УПРАВЛЕНИЕ ПЛАТФОРМОЙ</p><h1>Настройки</h1><p className="muted">Ресурсы сборки и доступ агентов к Infra Hub.</p></div></div>
    <nav className="settings-tabs" aria-label="Разделы настроек"><a href="#settings/runner" aria-current={!mcp ? 'page' : undefined}>Раннер</a><a href="#settings/mcp" aria-current={mcp ? 'page' : undefined}>MCP</a></nav>
    {mcp ? <Mcp/> : <Runner embedded/>}
  </>
}
