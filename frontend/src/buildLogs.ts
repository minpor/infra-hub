// Format legacy diagnostic wrappers without changing persisted logs.
export function compactBuildLogs(raw: string): string {
  const lines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('$ ') && line.length > 500) {
      lines.push('$ [Длинная команда сборки — доступна в полном журнале]')
      continue
    }
    if (!line.startsWith('DIAG2 ')) { lines.push(line); continue }
    try {
      const value = JSON.parse(line.slice(6))
      if (value.kind === 'start') lines.push('Диагностика: сборка начата')
      else if (value.kind === 'finish') {
        lines.push(`Диагностика: код завершения ${value.exit}`)
        if (typeof value.peak_memory === 'number') lines.push(`Пиковая память: ${(value.peak_memory / 1024 ** 3).toFixed(2)} GiB · процессов: ${value.peak_pids ?? '—'}`)
        if (Array.isArray(value.diagnostic_lines)) lines.push(...value.diagnostic_lines.filter((item: unknown): item is string => typeof item === 'string'))
      } else if (value.kind !== 'sample') lines.push(line)
    } catch { lines.push(line) }
  }
  // Collapse repetitions only; retain every distinct error and unrecognized line.
  const result: string[] = []
  for (let i = 0; i < lines.length; i++) {
    let count = 1
    while (lines[i] && lines[i + 1] === lines[i]) { count++; i++ }
    result.push(lines[i])
    if (count > 1) result.push(`[повторено ${count} раз]`)
  }
  return result.join('\n')
}
