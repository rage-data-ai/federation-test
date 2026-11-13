import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearAccessToken,
  fetchOidcDiscovery,
  getAccessToken,
  getRefreshToken,
  setAccessToken,
  setRefreshToken
} from './auth'

describe('access token storage', () => {
  beforeEach(() => {
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

  it('stores and clears bearer and refresh tokens', () => {
    setAccessToken('  abc  ')
    setRefreshToken(' r1 ')
    expect(getAccessToken()).toBe('abc')
    expect(getRefreshToken()).toBe('r1')
    clearAccessToken()
    expect(getAccessToken()).toBe('')
    expect(getRefreshToken()).toBe('')
  })
})

describe('oidc discovery', () => {
  it('reads authorization and token endpoints from well-known', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => ({
          authorization_endpoint: 'https://idp.example/auth',
          token_endpoint: 'https://idp.example/token',
          end_session_endpoint: 'https://idp.example/logout'
        })
      }))
    )
    const discovery = await fetchOidcDiscovery('https://idp.example/realms/demo-' + Math.random())
    expect(discovery?.authorization_endpoint).toBe('https://idp.example/auth')
    expect(discovery?.token_endpoint).toBe('https://idp.example/token')
  })
})
