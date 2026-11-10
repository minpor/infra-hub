import { test, expect } from '@playwright/test'
import { compactBuildLogs } from '../src/buildLogs'

test('legacy diagnostic output is readable without hiding distinct errors or corrupt records', () => {
  const finish = { kind: 'finish', exit: 1, peak_memory: 1024 ** 3, peak_pids: 73, diagnostic_lines: ['Ошибка загрузки', 'Ошибка загрузки', 'not ok 111 - birth screen', "error: 'b-birth'"] }
  const raw = ['$ ' + 'encoded-command'.repeat(100), 'DIAG2 ' + JSON.stringify({ kind: 'sample', resources: { memory: 123 } }), 'DIAG2 ' + JSON.stringify(finish), 'DIAG2 {broken', 'another important failure'].join('\n')
  const compact = compactBuildLogs(raw)
  expect(compact).toContain('код завершения 1')
  expect(compact).toContain('1.00 GiB')
  expect(compact).toContain('Ошибка загрузки\n[повторено 2 раз]')
  expect(compact).toContain("error: 'b-birth'")
  expect(compact).toContain('DIAG2 {broken')
  expect(compact).toContain('another important failure')
  expect(compact).not.toContain('encoded-command')
  expect(compact).not.toContain('"sample"')
  expect(compactBuildLogs('normal output\nError: actual error\n')).toBe('normal output\nError: actual error\n')
})
