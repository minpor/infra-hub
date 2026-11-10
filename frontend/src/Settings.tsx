import { t, useLanguage } from './i18n'
import { Runner } from './Runner'
import { Mcp } from './Mcp'

export function Settings({ mcp }: { mcp: boolean }) {
  useLanguage()
  return <>
    <div className="page-heading"><div className="title"><p className="eyebrow">{t("УПРАВЛЕНИЕ ПЛАТФОРМОЙ")}</p><h1>{t("Настройки")}</h1><p className="muted">{t("Ресурсы сборки и доступ агентов к Infra Hub.")}</p></div></div>
    <nav className="settings-tabs" aria-label={t("Разделы настроек")}><a href="#settings/runner" aria-current={!mcp ? 'page' : undefined}>{t("Раннер")}</a><a href="#settings/mcp" aria-current={mcp ? 'page' : undefined}>MCP</a></nav>
    {mcp ? <Mcp/> : <Runner embedded/>}
  </>
}
