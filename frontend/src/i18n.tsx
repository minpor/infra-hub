import { useSyncExternalStore } from 'react'
import english from './en.json'

export type Language = 'en' | 'ru'
const dictionary: Record<string, string> = english
const key = 'infra-hub-language'
function initialLanguage(): Language {
  try { const saved = localStorage.getItem(key); if (saved === 'en' || saved === 'ru') return saved } catch { /* In-memory choice remains available. */ }
  return navigator.language.toLowerCase().startsWith('ru') ? 'ru' : 'en'
}
let language = initialLanguage()
const listeners = new Set<() => void>()
function updateDocument() {
  document.documentElement.lang = language
  document.title = language === 'en' ? 'Infra Hub · Your hardware. Your pace.' : 'Infra Hub · Своя мощность'
}
updateDocument()
export function setLanguage(value: Language) {
  language = value
  try { localStorage.setItem(key, value) } catch { /* Storage is optional. */ }
  updateDocument()
  listeners.forEach(listener => listener())
}
window.addEventListener('storage', event => {
  if (event.key === key && (event.newValue === 'en' || event.newValue === 'ru')) setLanguage(event.newValue)
})
export function useLanguage() {
  return useSyncExternalStore(listener => { listeners.add(listener); return () => { listeners.delete(listener) } }, () => language)
}
export const locale = () => language === 'ru' ? 'ru-RU' : 'en-US'
export function t(source: string | null | undefined, values: unknown[] = []): string {
  if (!source) return ''
  let result = source
  if (language === 'en') {
    result = dictionary[source] || source
    if (!dictionary[source] && source.includes('\n')) {
      const split = source.indexOf('\n')
      result = t(source.slice(0, split)) + source.slice(split)
    }
    if (!values.length && result === source) {
      for (const [key, translation] of Object.entries(dictionary)) {
        if (!key.includes('{0}')) continue
        const parts = key.split(/(\{\d+\})/)
        const pattern = parts.map(part => /^\{\d+\}$/.test(part) ? '(.+?)' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('')
        const match = new RegExp('^' + pattern + '$').exec(source)
        if (match) { result = translation; match.slice(1).forEach((value, i) => { result = result.replaceAll(`{${i}}`, value) }); break }
      }
    }
  }
  values.forEach((value, index) => { result = result.replaceAll(`{${index}}`, String(value ?? '')) })
  return result
}
export function LanguageSwitch() {
  const current = useLanguage()
  return <label className="language-switch"><span>Language / Язык</span><select aria-label="Language / Язык" value={current} onChange={e => setLanguage(e.target.value as Language)}><option value="en">English</option><option value="ru">Русский</option></select></label>
}
export const number = (value: number, digits = 1) => new Intl.NumberFormat(locale(), { maximumFractionDigits: digits }).format(value)
export const minutes = (millis: number | null | undefined) => millis == null ? '—' : `${number(millis / 60000, 2)} ${t('мин')}`
