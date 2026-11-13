import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { dispatchSseChunk, listCatalogs } from './api.js'

describe('dispatchSseChunk', () => {
  it('parses event name and JSON data', () => {
    const events = []
    dispatchSseChunk('event: stage\ndata: {"name":"ontology"}', (name, data) => events.push({ name, data }))
    expect(events).toEqual([{ name: 'stage', data: { name: 'ontology' } }])
  })

  it('keeps non-json payload as text', () => {
    const events = []
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
    fetch.mockResolvedValue({
      ok: true,
      text: async () => JSON.stringify([{ name: 'mysql' }])
    })
    await expect(listCatalogs()).resolves.toEqual([{ name: 'mysql' }])
  })

  it('surfaces gateway message on error', async () => {
    fetch.mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      text: async () => JSON.stringify({ message: '无效的访问令牌' })
    })
    await expect(listCatalogs()).rejects.toThrow('无效的访问令牌')
  })
})
