import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, getAccessToken, setAccessToken } from './auth'

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

  it('stores and clears bearer token', () => {
    setAccessToken('  abc  ')
    expect(getAccessToken()).toBe('abc')
    clearAccessToken()
    expect(getAccessToken()).toBe('')
  })
})
