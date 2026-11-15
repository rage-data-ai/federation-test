import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
  ApiError,
  addAskSuggestion,
  authChangePassword,
  authLogin,
  authRegister,
  dispatchSseChunk,
  getAskConversation,
  listAskConversations,
  listAskSuggestions,
  listCatalogs,
  listUsers,
  onUnauthorized,
  rateAskRun,
  resetUserPassword,
  updateAskSuggestion,
  updateUser
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

  it('lists and loads conversations', async () => {
    await listAskConversations()
    expect(lastCall().path).toBe('/api/v1/ask/conversations')

    await listAskConversations(20)
    expect(lastCall().path).toBe('/api/v1/ask/conversations?limit=20')

    await getAskConversation('conv/1')
    expect(lastCall().path).toBe('/api/v1/ask/conversations/conv%2F1')
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

describe('accounts and session expiry', () => {
  const unauthorized = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    vi.stubGlobal('localStorage', { getItem: () => 'session-token', setItem: () => {}, removeItem: () => {} })
    unauthorized.mockReset()
    onUnauthorized(unauthorized)
  })
  afterEach(() => {
    onUnauthorized(null)
    vi.unstubAllGlobals()
  })

  function respond(status: number, body: unknown) {
    vi.mocked(fetch).mockResolvedValue({
      ok: status < 400,
      status,
      statusText: String(status),
      text: async () => JSON.stringify(body)
    } as Response)
  }

  function lastCall() {
    const [path, init] = vi.mocked(fetch).mock.calls.at(-1) as [string, RequestInit]
    return { path, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined }
  }

  it('posts credentials for login, register and password change', async () => {
    respond(200, { token: 't', expiresAt: 1, user: { username: 'alice' } })
    await expect(authLogin('alice', 'alice-pass-1')).resolves.toMatchObject({ token: 't' })
    expect(lastCall()).toEqual({
      path: '/api/v1/auth/login',
      method: 'POST',
      body: { username: 'alice', password: 'alice-pass-1' }
    })

    await authRegister('bob', 'bob-pass-11', '鲍勃')
    expect(lastCall()).toEqual({
      path: '/api/v1/auth/register',
      method: 'POST',
      body: { username: 'bob', password: 'bob-pass-11', displayName: '鲍勃' }
    })

    await authChangePassword('old-pass-11', 'new-pass-22')
    expect(lastCall()).toEqual({
      path: '/api/v1/auth/password',
      method: 'POST',
      body: { oldPassword: 'old-pass-11', newPassword: 'new-pass-22' }
    })
  })

  it('calls the admin user endpoints', async () => {
    respond(200, [])
    await listUsers()
    expect(lastCall().path).toBe('/api/v1/users')

    await updateUser(7, { status: 'disabled' })
    expect(lastCall()).toEqual({ path: '/api/v1/users/7', method: 'PUT', body: { status: 'disabled' } })

    await resetUserPassword(7, 'reset-pass-33')
    expect(lastCall()).toEqual({
      path: '/api/v1/users/7/password',
      method: 'POST',
      body: { password: 'reset-pass-33' }
    })
  })

  it('reports a 401 on any other endpoint as an expired session', async () => {
    respond(401, { message: '无效的访问令牌' })
    const error = await listCatalogs().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(401)
    expect((error as ApiError).message).toBe('无效的访问令牌')
    expect(unauthorized).toHaveBeenCalledTimes(1)
  })

  it('treats a 401 from login or register as wrong credentials, not an expired session', async () => {
    respond(401, { message: '用户名或密码错误' })
    await expect(authLogin('alice', 'wrong')).rejects.toThrow('用户名或密码错误')
    await expect(authRegister('alice', 'wrong', '')).rejects.toBeInstanceOf(ApiError)
    expect(unauthorized).not.toHaveBeenCalled()
  })

  it('leaves other failures alone', async () => {
    respond(403, { message: '需要角色 ADMIN' })
    await expect(listUsers()).rejects.toMatchObject({ status: 403 })
    expect(unauthorized).not.toHaveBeenCalled()
  })
})
