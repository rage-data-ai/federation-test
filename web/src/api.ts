import { getAccessToken } from './auth'
import type {
  AskKnowledge,
  AskKnowledgeMatch,
  AskKnowledgePatch,
  AskKnowledgeStatus,
  AskPrompt,
  AskPromptPatch,
  AskPromptScope,
  AskRating,
  AskConversationSummary,
  AskRunView,
  AskSuggestion,
  AskSuggestionPatch,
  AskSuggestionStatus,
  AuthMe,
  AuthSession,
  CatalogRecord,
  ChatApp,
  DraftResult,
  ExampleItem,
  FlowDraft,
  FlowGraph,
  FlowTemplate,
  LlmConfig,
  LlmStatus,
  LlmTestResult,
  MetadataTable,
  OntologyBundle,
  PublishedApp,
  QueryResult,
  UserAccount,
  UserPatch
} from './types'

type Json = Record<string, unknown> | unknown[] | string | number | boolean | null

function headers(extra: HeadersInit = {}): HeadersInit {
  const result: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(extra as Record<string, string>)
  }
  const token = getAccessToken()
  if (token && !result.Authorization) {
    result.Authorization = `Bearer ${token}`
  }
  return result
}

function errorMessage(data: unknown, fallback: string): string {
  if (data && typeof data === 'object') {
    const body = data as Record<string, unknown>
    const message = body.message ?? body.detail ?? body.title
    if (typeof message === 'string' && message) {
      return message
    }
  }
  return fallback
}

/** 网关返回的非 2xx 响应；{@link status} 是 HTTP 状态码。 */
export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** 登录和注册本身返回的 401 是“密码不对”，不代表会话失效。 */
const SIGN_IN_PATHS = ['/api/v1/auth/login', '/api/v1/auth/register']

let unauthorizedHandler: (() => void) | null = null

/** 任一接口返回 401（令牌缺失、过期或被吊销）时回调，供外壳切到登录页。 */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

function notifyUnauthorized(path: string, status: number): void {
  if (status === 401 && !SIGN_IN_PATHS.includes(path)) {
    unauthorizedHandler?.()
  }
}

async function request<T = Json>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...options,
      headers: headers(options.headers)
    })
  } catch {
    throw new Error('无法连接网关 localhost:18080，请先启动后端')
  }
  notifyUnauthorized(path, response.status)
  const text = await response.text()
  if (!text) {
    throw new Error(response.ok ? '网关返回空响应' : `网关不可用 (${response.status})，请先启动后端`)
  }
  let data: unknown
  try {
    data = JSON.parse(text)
  } catch {
    throw new Error(`网关返回非 JSON：${text.slice(0, 120)}`)
  }
  if (!response.ok) {
    throw new ApiError(errorMessage(data, response.statusText), response.status)
  }
  return data as T
}

export function listCatalogs() {
  return request<CatalogRecord[]>('/api/v1/catalogs')
}

export function listExamples() {
  return request<ExampleItem[]>('/api/v1/examples')
}

export function createCatalog(body: unknown) {
  return request<CatalogRecord>('/api/v1/catalogs', { method: 'POST', body: JSON.stringify(body) })
}

export function updateCatalog(name: string, body: unknown) {
  return request<CatalogRecord>(`/api/v1/catalogs/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

export function deleteCatalog(name: string) {
  return request<Json>(`/api/v1/catalogs/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

export function testCatalog(body: unknown) {
  return request<{ success?: boolean; message?: string }>('/api/v1/catalogs/test', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function runQuery(body: unknown) {
  return request<QueryResult>('/api/v1/query', { method: 'POST', body: JSON.stringify(body) })
}

export function explainQuery(body: unknown) {
  return request<QueryResult>('/api/v1/explain', { method: 'POST', body: JSON.stringify(body) })
}

export function listMetadata() {
  return request<MetadataTable[]>('/api/v1/metadata')
}

export function collectMetadata(body: unknown) {
  return request<Json>('/api/v1/metadata/collect', { method: 'POST', body: JSON.stringify(body) })
}

export function deleteMetadata(catalog: string, schema: string, table: string) {
  return request<Json>(
    `/api/v1/metadata/${encodeURIComponent(catalog)}/${encodeURIComponent(schema)}/${encodeURIComponent(table)}`,
    { method: 'DELETE' }
  )
}

export function authLogout() {
  return request<{ success: boolean; revoked?: boolean }>('/api/v1/auth/logout', { method: 'POST', body: '{}' })
}

export function authMe() {
  return request<AuthMe>('/api/v1/auth/me')
}

export function authLogin(username: string, password: string) {
  return request<AuthSession>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
}

export function authRegister(username: string, password: string, displayName: string) {
  return request<AuthSession>('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName })
  })
}

export function authChangePassword(oldPassword: string, newPassword: string) {
  return request<{ success: boolean }>('/api/v1/auth/password', {
    method: 'POST',
    body: JSON.stringify({ oldPassword, newPassword })
  })
}

export function listUsers() {
  return request<UserAccount[]>('/api/v1/users')
}

export function updateUser(id: number, patch: UserPatch) {
  return request<UserAccount>(`/api/v1/users/${id}`, { method: 'PUT', body: JSON.stringify(patch) })
}

export function resetUserPassword(id: number, password: string) {
  return request<{ success: boolean }>(`/api/v1/users/${id}/password`, {
    method: 'POST',
    body: JSON.stringify({ password })
  })
}

export function llmStatus() {
  return request<LlmStatus>('/api/v1/llm/status')
}

export function llmConfig() {
  return request<LlmConfig>('/api/v1/llm/config')
}

export function saveLlm(body: unknown) {
  return request<LlmConfig>('/api/v1/llm/config', { method: 'PUT', body: JSON.stringify(body) })
}

export function testLlm(body: unknown) {
  return request<LlmTestResult>('/api/v1/llm/test', { method: 'POST', body: JSON.stringify(body) })
}

export function getOntology() {
  return request<OntologyBundle>('/api/v1/ontology')
}

export async function getOntologyTurtle(): Promise<string> {
  const response = await fetch('/api/v1/ontology/turtle', { headers: headers() })
  notifyUnauthorized('/api/v1/ontology/turtle', response.status)
  if (!response.ok) {
    throw new Error(`导出失败 (${response.status})`)
  }
  return response.text()
}

const ENTITY_PATHS: Record<string, string> = {
  concept: 'concepts',
  attribute: 'attributes',
  relation: 'relations',
  code: 'codes',
  defined: 'defined'
}

export function saveEntity(kind: string, name: string, body: unknown) {
  return request<OntologyBundle>(`/api/v1/ontology/${ENTITY_PATHS[kind]}/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

export function renameSubject(from: string, to: string) {
  return request<OntologyBundle>(`/api/v1/ontology/subjects/${encodeURIComponent(from)}`, {
    method: 'PUT',
    body: JSON.stringify({ name: to })
  })
}

export function deleteEntity(name: string) {
  return request<OntologyBundle>(`/api/v1/ontology/entities/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

export type OntologyVersionInfo = {
  id: string
  fileName?: string
  concepts?: number
  attributes?: number
  relations?: number
  codes?: number
  definedConcepts?: number
  note?: string
}

export function listOntologyVersions() {
  return request<OntologyVersionInfo[]>('/api/v1/ontology/versions')
}

export function createOntologyVersion(label?: string) {
  return request<OntologyVersionInfo>('/api/v1/ontology/versions', {
    method: 'POST',
    body: JSON.stringify(label ? { label } : {})
  })
}

export function restoreOntologyVersion(id: string) {
  return request<OntologyBundle>(`/api/v1/ontology/versions/${encodeURIComponent(id)}/restore`, {
    method: 'POST',
    body: '{}'
  })
}

export type { DraftResult }

export type SseHandler = (name: string, data: unknown) => void

export function draftOntology(body: unknown, onEvent: SseHandler) {
  return postEvents('/api/v1/ontology/draft', body, onEvent)
}

/** POST /api/v1/ask，按 SSE 事件回调 onEvent(name, data)，流结束后 resolve。 */
export function askStream(body: unknown, onEvent: SseHandler, signal?: AbortSignal) {
  return postEvents('/api/v1/ask', body, onEvent, signal)
}

/** 廉价可回答性预检（不跑 LLM / SQL）。 */
export function askAnswerable(question: string) {
  return request<{
    answerable: boolean
    coverage: number
    status: string
    matchedTerms: string[]
    gaps: string[]
    suggestion: string
  }>('/api/v1/ask/answerable', {
    method: 'POST',
    body: JSON.stringify({ question })
  })
}

export function listFlowTemplates() {
  return request<FlowTemplate[]>('/api/v1/flows/templates')
}

export function listFlows() {
  return request<FlowDraft[]>('/api/v1/flows')
}

export function getFlow(id: string) {
  return request<FlowDraft>(`/api/v1/flows/${encodeURIComponent(id)}`)
}

export function createFlow(body: { name?: string; template?: string }) {
  return request<FlowDraft>('/api/v1/flows', { method: 'POST', body: JSON.stringify(body) })
}

export function updateFlow(id: string, body: { name?: string; graph?: FlowGraph }) {
  return request<FlowDraft>(`/api/v1/flows/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

export function publishFlow(id: string) {
  return request<{ id: string; flowId: string; version: number }>(
    `/api/v1/flows/${encodeURIComponent(id)}/publish`,
    { method: 'POST', body: '{}' }
  )
}

export function listApps() {
  return request<ChatApp[]>('/api/v1/apps')
}

export function listPublishedApps() {
  return request<PublishedApp[]>('/api/v1/apps/published')
}

export function getApp(id: string) {
  return request<ChatApp>(`/api/v1/apps/${encodeURIComponent(id)}`)
}

export function createApp(body: { name?: string; description?: string; flowId?: string; template?: string }) {
  return request<ChatApp>('/api/v1/apps', { method: 'POST', body: JSON.stringify(body) })
}

export function updateApp(id: string, body: { name?: string; description?: string; flowId?: string }) {
  return request<ChatApp>(`/api/v1/apps/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

/** 软删除（归档）；内置「智能问数」会 400。 */
export function deleteApp(id: string) {
  return request<ChatApp>(`/api/v1/apps/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function publishApp(id: string, flowVersionId?: string) {
  return request<{ id: number; appId: string; flowVersionId: string; publishedAt: number }>(
    `/api/v1/apps/${encodeURIComponent(id)}/publish`,
    { method: 'POST', body: JSON.stringify({ flowVersionId: flowVersionId || null }) }
  )
}

export function listAskConversations(limit?: number) {
  const query = limit && limit > 0 ? `?limit=${limit}` : ''
  return request<AskConversationSummary[]>(`/api/v1/ask/conversations${query}`)
}

export function getAskConversation(conversationId: string) {
  return request<AskRunView[]>(`/api/v1/ask/conversations/${encodeURIComponent(conversationId)}`)
}

export function getAskRun(runId: string) {
  return request<AskRunView>(`/api/v1/ask/runs/${encodeURIComponent(runId)}`)
}

/** rating 传 null 撤销评价。 */
export function rateAskRun(runId: string, rating: AskRating | null) {
  return request<AskRunView>(`/api/v1/ask/runs/${encodeURIComponent(runId)}/rating`, {
    method: 'PUT',
    body: JSON.stringify({ rating })
  })
}

export function listAskSuggestions(filter: { status?: AskSuggestionStatus | ''; mine?: boolean } = {}) {
  const query = new URLSearchParams()
  if (filter.status) {
    query.set('status', filter.status)
  }
  if (filter.mine) {
    query.set('mine', 'true')
  }
  const suffix = query.toString()
  return request<AskSuggestion[]>(`/api/v1/ask/suggestions${suffix ? `?${suffix}` : ''}`)
}

export function addAskSuggestion(content: string, runId?: string | null) {
  return request<AskSuggestion>('/api/v1/ask/suggestions', {
    method: 'POST',
    body: JSON.stringify({ content, runId: runId || null })
  })
}

export function updateAskSuggestion(id: number, patch: AskSuggestionPatch) {
  return request<AskSuggestion>(`/api/v1/ask/suggestions/${id}`, { method: 'PUT', body: JSON.stringify(patch) })
}

export function deleteAskSuggestion(id: number) {
  return request<Json>(`/api/v1/ask/suggestions/${id}`, { method: 'DELETE' })
}

export function listAskKnowledge(status?: AskKnowledgeStatus | '', kind?: string) {
  const query = new URLSearchParams()
  if (status) {
    query.set('status', status)
  }
  if (kind) {
    query.set('kind', kind)
  }
  const suffix = query.toString()
  return request<AskKnowledge[]>(`/api/v1/ask/knowledge${suffix ? `?${suffix}` : ''}`)
}

export function getAskKnowledge(id: number) {
  return request<AskKnowledge>(`/api/v1/ask/knowledge/${id}`)
}

export function createAskKnowledge(body: {
  question: string
  answer: string
  sql?: string | null
  caliber?: Array<Record<string, unknown>>
  evidence?: Record<string, unknown>
  kind?: string
}) {
  return request<AskKnowledge>('/api/v1/ask/knowledge', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function updateAskKnowledge(id: number, patch: AskKnowledgePatch) {
  return request<AskKnowledge>(`/api/v1/ask/knowledge/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch)
  })
}

export function deleteAskKnowledge(id: number) {
  return request<Json>(`/api/v1/ask/knowledge/${id}`, { method: 'DELETE' })
}

export function searchAskKnowledge(question: string, topK?: number) {
  return request<AskKnowledgeMatch[]>('/api/v1/ask/knowledge/search', {
    method: 'POST',
    body: JSON.stringify({ question, topK })
  })
}

export function listAskPrompts(scope?: AskPromptScope | '') {
  const query = new URLSearchParams()
  if (scope) {
    query.set('scope', scope)
  }
  const suffix = query.toString()
  return request<AskPrompt[]>(`/api/v1/ask/prompts${suffix ? `?${suffix}` : ''}`)
}

export function createAskPrompt(body: {
  scope: AskPromptScope
  content: string
  source?: string
}) {
  return request<AskPrompt>('/api/v1/ask/prompts', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function updateAskPrompt(id: number, patch: AskPromptPatch) {
  return request<AskPrompt>(`/api/v1/ask/prompts/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch)
  })
}

export function activateAskPrompt(id: number) {
  return request<AskPrompt>(`/api/v1/ask/prompts/${id}/activate`, {
    method: 'POST',
    body: '{}'
  })
}

export function deleteAskPrompt(id: number) {
  return request<Json>(`/api/v1/ask/prompts/${id}`, { method: 'DELETE' })
}

/** 管理员：根据开放建议生成全局草稿提示词。 */
export function proposeAskPromptsFromFeedback() {
  return request<AskPrompt>('/api/v1/ask/prompts/proposals', {
    method: 'POST',
    body: '{}'
  })
}

async function postEvents(path: string, body: unknown, onEvent: SseHandler, signal?: AbortSignal) {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: headers({ Accept: 'text/event-stream' }),
      body: JSON.stringify(body),
      signal
    })
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      return
    }
    throw new Error('无法连接网关 localhost:18080，请先启动后端')
  }
  notifyUnauthorized(path, response.status)
  if (!response.ok || !response.body) {
    const text = await response.text()
    let message: unknown = text
    try {
      message = errorMessage(JSON.parse(text), text)
    } catch {
      // 非 JSON 错误体直接展示
    }
    throw new Error(String(message || `问数失败 (${response.status})`))
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        dispatchSseChunk(buffer.slice(0, index), onEvent)
        buffer = buffer.slice(index + 2)
      }
    }
    if (buffer.trim()) {
      dispatchSseChunk(buffer, onEvent)
    }
  } catch (e) {
    if (!(e instanceof Error && e.name === 'AbortError')) {
      throw e
    }
  }
}

export function dispatchSseChunk(chunk: string, onEvent: SseHandler) {
  let name = 'message'
  const data: string[] = []
  for (const line of chunk.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!data.length) {
    return
  }
  const text = data.join('\n')
  let parsed: unknown = text
  try {
    parsed = JSON.parse(text)
  } catch {
    // 保留原文
  }
  onEvent(name, parsed)
}
