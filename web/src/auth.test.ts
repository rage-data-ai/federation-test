import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearAccessToken,
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
