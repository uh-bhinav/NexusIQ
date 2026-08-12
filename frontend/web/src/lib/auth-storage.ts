/**
 * Tokens live in module-level memory only, never localStorage/sessionStorage
 * (.claude/rules/frontend.md: "Token in memory + refresh flow; if
 * localStorage is used, document the XSS trade-off" — we simply don't use
 * it, so there's no trade-off to document). This means a hard page reload
 * logs the user out; `AuthProvider` calls `/auth/me` on mount to re-derive
 * session state from the refresh token cookie-less flow is NOT used here —
 * instead the refresh token is kept in memory alongside the access token,
 * with the same lifetime trade-off (lost on reload, by design, not a bug).
 */

let accessToken: string | null = null
let refreshToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return refreshToken
}

export function setTokens(access: string, refresh: string): void {
  accessToken = access
  refreshToken = refresh
}

export function clearTokens(): void {
  accessToken = null
  refreshToken = null
}
