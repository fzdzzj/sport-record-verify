/**
 * Token 存储工具（access 经 localStorage，role 判定管理端）。
 * refresh 自 TASK-205 起不再经 JS 存储与发送：改由后端经 HttpOnly cookie（SameSite=Lax，
 * Path=/api/auth）下发与回传，XSS 无法读取（凭证窗口收敛为 access 15min 短窗口）。
 * access 仍需 JS 可读（供 Authorization: Bearer 头与 STOMP CONNECT 帧级鉴权，见 README）。
 */
export const TOKEN_KEYS = {
  ACCESS: 'access_token',
  ROLE: 'user_role',
} as const

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEYS.ACCESS)
}

export function getRole(): string | null {
  return localStorage.getItem(TOKEN_KEYS.ROLE)
}

export function setTokens(accessToken: string, role: string): void {
  localStorage.setItem(TOKEN_KEYS.ACCESS, accessToken)
  localStorage.setItem(TOKEN_KEYS.ROLE, role)
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEYS.ACCESS)
  // 存量清理：移除历史上持久化的 refresh 键（presented 旧版），此后该键不再写入
  localStorage.removeItem('refresh_token')
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
