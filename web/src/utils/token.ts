/**
 * Token 存储工具（分别存储 access/refresh/role，按规范不使用单 token 头）。
 * 使用 localStorage 持久化演示用；生产可考虑 httpOnly cookie + refresh 端点。
 */
export const TOKEN_KEYS = {
  ACCESS: 'access_token',
  REFRESH: 'refresh_token',
  ROLE: 'user_role',
} as const

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEYS.ACCESS)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(TOKEN_KEYS.REFRESH)
}

export function getRole(): string | null {
  return localStorage.getItem(TOKEN_KEYS.ROLE)
}

export function setTokens(accessToken: string, refreshToken: string, role: string): void {
  localStorage.setItem(TOKEN_KEYS.ACCESS, accessToken)
  localStorage.setItem(TOKEN_KEYS.REFRESH, refreshToken)
  localStorage.setItem(TOKEN_KEYS.ROLE, role)
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEYS.ACCESS)
  localStorage.removeItem(TOKEN_KEYS.REFRESH)
  localStorage.removeItem(TOKEN_KEYS.ROLE)
}

export function hasAccessToken(): boolean {
  return !!getAccessToken()
}

// 401 refresh 失败时触发：清会话 + 跳转登录（由 main 注册 handler 防止循环）
let authFailHandler: (() => void) | null = null

export function setAuthFailHandler(handler: () => void): void {
  authFailHandler = handler
}

export function triggerAuthFail(): void {
  clearTokens()
  if (authFailHandler) {
    try { authFailHandler() } catch (e) { /* ignore */ }
  }
}
