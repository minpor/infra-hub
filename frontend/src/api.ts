export type Status = 'QUEUED' | 'RUNNING' | 'CANCEL_REQUESTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export type Project = { id: string; name: string; repositoryUrl: string | null; createdAt: string }
export type Build = { id: string; projectId: string; status: Status; recipe: string; createdAt: string; startedAt: string | null; finishedAt: string | null; message: string | null; source: { repository: string; ref: string; sha: string; command: string; artifactPath: string | null; yamlRecipe?: { pipelineId: string; applicationId: string; path: string; sha256: string; yaml?: string } | null } | null; image: { reference: string; artifactSha256: string; artifactSize: number } | null; runnerExecution?: { cpus: number; memoryGiB: number; cacheStatus: string; cacheVolume: string | null } | null }
export type Step = { position: number; name: string; status: Status; exitCode: number | null }
export type Detail = { build: Build; steps: Step[] }
export type Log = { id: number; stepPosition: number; text: string; createdAt: string }
export const active = (status: Status) => ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(status)
export class ApiError extends Error { constructor(message: string, readonly status: number) { super(message) } }

export async function csrfToken(): Promise<string> {
  const response = await fetch('/api/csrf', { signal: AbortSignal.timeout(10000) })
  if (response.status === 404) return ''
  if (!response.ok) throw new ApiError('Не удалось получить защитный токен', response.status)
  return (await response.json()).token
}

export async function api<T>(path: string, body?: unknown): Promise<T> {
  const token = body === undefined ? '' : await csrfToken()
  const response = await fetch(`/api${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json', 'X-Infra-Hub': 'local-ui', ...(token ? { 'X-XSRF-TOKEN': token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(120000),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new ApiError(error?.message || `Запрос не выполнен (${response.status})`, response.status)
  }
  return response.json()
}
