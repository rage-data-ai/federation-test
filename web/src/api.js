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
