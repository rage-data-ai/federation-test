import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { dispatchSseChunk, listCatalogs } from './api'

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
