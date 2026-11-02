export interface DomainView {
  key: string
  name: string
  description: string
  cardCount: number | null
  taskCount: number
}

export interface CardSummaryView {
  planning: number | null
  debate: number | null
  general: number | null
  libraryVersion: string | null
  stale: boolean
}

export interface Constraint {
  cid: string
  type: string
  text: string
  params: unknown
}

export interface Stance {
  label: string
  position: string
}

export interface TaskView {
  taskId: string
  domain: string
  prompt: string
  constraints: Constraint[]
  stances: Stance[]
  acceptableCards: string[]
  split: string
  family: string
  meta: Record<string, unknown>
}

export interface TestRun {
  id: number
  taskId: string
  domain: string
  createdAt: string
  action: string
  cardId: string | null
  confidence: number
  libraryVersion: string
  routerMs: number
  baselineMs: number
  oursMs: number
  baselinePromptTokens: number | null
  oursPromptTokens: number | null
  completionTokens: number | null
  baselineText: string | null
  oursText: string | null
  error: string | null
}

/** 正在生成中的对比。不落库，来自服务端内存。 */
export interface ActiveRun {
  taskId: string
  domain: string
  phase: string
  startedAt: string
  elapsedMs: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init
  })
  if (!response.ok) {
    let message = `HTTP ${response.status}`
    try {
      const body = await response.json()
      message = body?.message ?? message
    } catch {
      // 忽略非 JSON 错误体
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

export const api = {
  domains: () => request<DomainView[]>('/api/domains'),
  tasks: (domain: string) => request<TaskView[]>(`/api/tasks?domain=${encodeURIComponent(domain)}`),
  run: (taskId: string) =>
    request<TestRun>('/api/runs', { method: 'POST', body: JSON.stringify({ taskId }) }),
  runs: (limit = 50) => request<TestRun[]>(`/api/runs?limit=${limit}`),
  activeRuns: () => request<ActiveRun[]>('/api/runs/active'),
  runDetail: (id: number | string) => request<TestRun>(`/api/runs/${id}`)
}
