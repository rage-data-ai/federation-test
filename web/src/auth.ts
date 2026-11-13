const TOKEN_KEY = 'federation.access_token'
const PKCE_VERIFIER_KEY = 'federation.oidc.code_verifier'
const PKCE_STATE_KEY = 'federation.oidc.state'

export type OidcConfig = {
  authority: string
  clientId: string
  redirectUri: string
  scope: string
}

export function getAccessToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setAccessToken(token: string): void {
  const value = token.trim()
  if (value) {
    localStorage.setItem(TOKEN_KEY, value)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export function clearAccessToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function readOidcConfig(): OidcConfig | null {
  const authority = String(import.meta.env.VITE_OIDC_AUTHORITY || '').trim()
  const clientId = String(import.meta.env.VITE_OIDC_CLIENT_ID || '').trim()
  const redirectUri =
    String(import.meta.env.VITE_OIDC_REDIRECT_URI || '').trim() ||
    `${window.location.origin}${window.location.pathname}`
  const scope = String(import.meta.env.VITE_OIDC_SCOPE || '').trim() || 'openid profile roles'
  if (!authority || !clientId) {
    return null
  }
  return { authority: authority.replace(/\/$/, ''), clientId, redirectUri, scope }
}

function authorizeUrl(authority: string): string {
  if (authority.includes('/realms/')) {
    return `${authority}/protocol/openid-connect/auth`
  }
  return `${authority}/oauth2/v1/authorize`
}

function tokenUrl(authority: string): string {
  if (authority.includes('/realms/')) {
    return `${authority}/protocol/openid-connect/token`
  }
  return `${authority}/oauth2/v1/token`
}

function toBase64Url(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  let binary = ''
  for (const b of view) {
    binary += String.fromCharCode(b)
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function sha256Base64Url(input: string): Promise<string> {
  const data = new TextEncoder().encode(input)
  const digest = await crypto.subtle.digest('SHA-256', data)
  return toBase64Url(digest)
}

function randomString(bytes = 32): string {
  const buf = new Uint8Array(bytes)
  crypto.getRandomValues(buf)
  return toBase64Url(buf)
}

export async function beginOidcLogin(config: OidcConfig | null = readOidcConfig()): Promise<void> {
  if (!config) {
    throw new Error('未配置 VITE_OIDC_AUTHORITY / VITE_OIDC_CLIENT_ID')
  }
  const verifier = randomString(48)
  const state = randomString(16)
  const challenge = await sha256Base64Url(verifier)
  sessionStorage.setItem(PKCE_VERIFIER_KEY, verifier)
  sessionStorage.setItem(PKCE_STATE_KEY, state)
  const authorize = new URL(authorizeUrl(config.authority))
  authorize.searchParams.set('client_id', config.clientId)
  authorize.searchParams.set('redirect_uri', config.redirectUri)
  authorize.searchParams.set('response_type', 'code')
  authorize.searchParams.set('scope', config.scope)
  authorize.searchParams.set('state', state)
  authorize.searchParams.set('code_challenge', challenge)
  authorize.searchParams.set('code_challenge_method', 'S256')
  window.location.assign(authorize.toString())
}

export async function completeOidcCallback(
  config: OidcConfig | null = readOidcConfig()
): Promise<boolean> {
  if (!config) {
    return false
  }
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  const state = params.get('state')
  if (!code) {
    return false
  }
  const expected = sessionStorage.getItem(PKCE_STATE_KEY)
  if (!state || !expected || state !== expected) {
    throw new Error('OIDC state 校验失败')
  }
  const verifier = sessionStorage.getItem(PKCE_VERIFIER_KEY)
  if (!verifier) {
    throw new Error('缺少 PKCE code_verifier')
  }
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code,
    redirect_uri: config.redirectUri,
    code_verifier: verifier
  })
  const response = await fetch(tokenUrl(config.authority), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  })
  const text = await response.text()
  let data: Record<string, unknown>
  try {
    data = JSON.parse(text) as Record<string, unknown>
  } catch {
    throw new Error(`令牌端点返回非 JSON：${text.slice(0, 120)}`)
  }
  if (!response.ok) {
    throw new Error(String(data.error_description || data.error || `换取令牌失败 (${response.status})`))
  }
  const accessToken = data.access_token
  if (typeof accessToken !== 'string' || !accessToken) {
    throw new Error('令牌响应缺少 access_token')
  }
  setAccessToken(accessToken)
  sessionStorage.removeItem(PKCE_VERIFIER_KEY)
  sessionStorage.removeItem(PKCE_STATE_KEY)
  const clean = new URL(window.location.href)
  clean.searchParams.delete('code')
  clean.searchParams.delete('state')
  clean.searchParams.delete('session_state')
  clean.searchParams.delete('iss')
  window.history.replaceState({}, document.title, clean.pathname + clean.search + clean.hash)
  return true
}
