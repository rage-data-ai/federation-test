import { getAccessToken } from './auth'
import type {
  AuthMe,
  CatalogRecord,
  DraftResult,
  ExampleItem,
  LlmConfig,
  LlmStatus,
  LlmTestResult,
  MetadataTable,
  OntologyBundle,
  QueryResult
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
    throw new Error(errorMessage(data, response.statusText))
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
