function headers() {
  return { 'Content-Type': 'application/json' }
}

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, {
      ...options,
      headers: { ...headers(), ...options.headers }
    })
  } catch {
    throw new Error('无法连接网关 localhost:18080，请先启动后端')
  }
  const text = await response.text()
  if (!text) {
    throw new Error(response.ok ? '网关返回空响应' : `网关不可用 (${response.status})，请先启动后端`)
  }
  let data
  try {
    data = JSON.parse(text)
  } catch {
    throw new Error(`网关返回非 JSON：${text.slice(0, 120)}`)
  }
  if (!response.ok) {
    throw new Error(data.message || response.statusText)
  }
  return data
}

export function listCatalogs() {
  return request('/api/v1/catalogs')
}

export function listExamples() {
  return request('/api/v1/examples')
}

export function createCatalog(body) {
  return request('/api/v1/catalogs', { method: 'POST', body: JSON.stringify(body) })
}

export function updateCatalog(name, body) {
  return request(`/api/v1/catalogs/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function deleteCatalog(name) {
  return request(`/api/v1/catalogs/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

export function testCatalog(body) {
  return request('/api/v1/catalogs/test', { method: 'POST', body: JSON.stringify(body) })
}

export function runQuery(body) {
  return request('/api/v1/query', { method: 'POST', body: JSON.stringify(body) })
}

export function explainQuery(body) {
  return request('/api/v1/explain', { method: 'POST', body: JSON.stringify(body) })
}

export function listMetadata() {
  return request('/api/v1/metadata')
}

export function collectMetadata(body) {
  return request('/api/v1/metadata/collect', { method: 'POST', body: JSON.stringify(body) })
}

export function deleteMetadata(catalog, schema, table) {
  return request(
    `/api/v1/metadata/${encodeURIComponent(catalog)}/${encodeURIComponent(schema)}/${encodeURIComponent(table)}`,
    { method: 'DELETE' }
  )
}

export function llmStatus() {
  return request('/api/v1/llm/status')
}

export function llmConfig() {
  return request('/api/v1/llm/config')
}

export function saveLlm(body) {
  return request('/api/v1/llm/config', { method: 'PUT', body: JSON.stringify(body) })
}

export function testLlm(body) {
  return request('/api/v1/llm/test', { method: 'POST', body: JSON.stringify(body) })
}

export function getOntology() {
  return request('/api/v1/ontology')
}

export async function getOntologyTurtle() {
  const response = await fetch('/api/v1/ontology/turtle')
  if (!response.ok) {
    throw new Error(`导出失败 (${response.status})`)
  }
  return response.text()
}

const ENTITY_PATHS = {
  concept: 'concepts',
  attribute: 'attributes',
  relation: 'relations',
  code: 'codes',
  defined: 'defined'
}

export function saveEntity(kind, name, body) {
  return request(`/api/v1/ontology/${ENTITY_PATHS[kind]}/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

export function deleteEntity(name) {
  return request(`/api/v1/ontology/entities/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

export function draftOntology(body, onEvent) {
  return postEvents('/api/v1/ontology/draft', body, onEvent)
}

/** POST /api/v1/ask，按 SSE 事件回调 onEvent(name, data)，流结束后 resolve。 */
export function askStream(body, onEvent, signal) {
  return postEvents('/api/v1/ask', body, onEvent, signal)
}

async function postEvents(path, body, onEvent, signal) {
  let response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { ...headers(), Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal
    })
  } catch (e) {
    if (e.name === 'AbortError') {
      return
    }
    throw new Error('无法连接网关 localhost:18080，请先启动后端')
  }
  if (!response.ok || !response.body) {
    const text = await response.text()
    let message = text
    try {
      message = JSON.parse(text).message || text
    } catch {
      // 非 JSON 错误体直接展示
    }
    throw new Error(message || `问数失败 (${response.status})`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const flush = (chunk) => {
    let name = 'message'
    const data = []
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
    let parsed = text
    try {
      parsed = JSON.parse(text)
    } catch {
      // 保留原文
    }
    onEvent(name, parsed)
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        flush(buffer.slice(0, index))
        buffer = buffer.slice(index + 2)
      }
    }
    if (buffer.trim()) {
      flush(buffer)
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      throw e
    }
  }
}
