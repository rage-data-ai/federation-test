import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
  addAskSuggestion,
  dispatchSseChunk,
  listAskSuggestions,
  listCatalogs,
  rateAskRun,
  updateAskSuggestion
} from './api'

describe('dispatchSseChunk', () => {
  it('parses event name and JSON data', () => {
    const events: Array<{ name: string; data: unknown }> = []
    dispatchSseChunk('event: stage\ndata: {"name":"ontology"}', (name, data) => events.push({ name, data }))
    expect(events).toEqual([{ name: 'stage', data: { name: 'ontology' } }])
  })

  it('keeps non-json payload as text', () => {
    const events: Array<{ name: string; data: unknown }> = []
    dispatchSseChunk('event: error\ndata: boom', (name, data) => events.push({ name, data }))
    expect(events).toEqual([{ name: 'error', data: 'boom' }])
  })
})

describe('listCatalogs', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, value)
      },
      removeItem: (key: string) => {
        store.delete(key)
      }
    })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns parsed json on success', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      text: async () => JSON.stringify([{ name: 'mysql' }])
    } as Response)
    await expect(listCatalogs()).resolves.toEqual([{ name: 'mysql' }])
  })

  it('reads RFC7807 detail as message', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      text: async () => JSON.stringify({ title: 'Unauthorized', detail: '无效的访问令牌', message: '无效的访问令牌' })
    } as Response)
    await expect(listCatalogs()).rejects.toThrow('无效的访问令牌')
  })
})

describe('ask feedback requests', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, text: async () => '{}' } as Response)
    )
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function lastCall() {
    const [path, init] = vi.mocked(fetch).mock.calls.at(-1) as [string, RequestInit]
    return { path, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined }
  }

  it('rates a run, and sends null to withdraw', async () => {
    await rateAskRun('run/1', 'up')
    expect(lastCall()).toEqual({ path: '/api/v1/ask/runs/run%2F1/rating', method: 'PUT', body: { rating: 'up' } })

    await rateAskRun('run-1', null)
    expect(lastCall().body).toEqual({ rating: null })
  })

  it('lists suggestions with only the filters that are set', async () => {
    await listAskSuggestions()
    expect(lastCall().path).toBe('/api/v1/ask/suggestions')

    await listAskSuggestions({ status: 'adopted', mine: true })
    expect(lastCall().path).toBe('/api/v1/ask/suggestions?status=adopted&mine=true')

    await listAskSuggestions({ status: '', mine: false })
    expect(lastCall().path).toBe('/api/v1/ask/suggestions')
  })

  it('creates a suggestion with or without a run, and patches one', async () => {
    await addAskSuggestion('希望能导出结果')
    expect(lastCall()).toEqual({
      path: '/api/v1/ask/suggestions',
      method: 'POST',
      body: { content: '希望能导出结果', runId: null }
    })

    await addAskSuggestion('高危应该按 level >= 4 算', 'run-1')
    expect(lastCall().body).toEqual({ content: '高危应该按 level >= 4 算', runId: 'run-1' })

    await updateAskSuggestion(7, { status: 'adopted', disposition: 'ontology' })
    expect(lastCall()).toEqual({
      path: '/api/v1/ask/suggestions/7',
      method: 'PUT',
      body: { status: 'adopted', disposition: 'ontology' }
    })
  })
})
