import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  TOKEN_KEYS,
  clearTokens,
  getAccessToken,
  getRole,
  hasAccessToken,
  setAuthFailHandler,
  setTokens,
  triggerAuthFail,
} from '@/utils/token'

// happy-dom 提供 localStorage/window；逐测清空，避免跨测残留。
beforeEach(() => {
  localStorage.clear()
})

describe('token.ts 锚点（TASK-215）', () => {
  // R2a：常量精确（键名/键值/键集合）
  it('R2a TOKEN_KEYS 常量精确', () => {
    expect(TOKEN_KEYS).toEqual({ ACCESS: 'access_token', ROLE: 'user_role' })
    expect(TOKEN_KEYS.ACCESS).toBe('access_token')
    expect(TOKEN_KEYS.ROLE).toBe('user_role')
    expect(Object.keys(TOKEN_KEYS)).toEqual(['ACCESS', 'ROLE'])
  })

  // R2b：setTokens(accessToken, role) 写双键 + 读回环
  it('R2b setTokens 写双键且 getAccessToken/getRole/hasAccessToken 回环', () => {
    expect(hasAccessToken()).toBe(false)
    setTokens('acc-123', 'ADMIN')
    expect(localStorage.getItem('access_token')).toBe('acc-123')
    expect(localStorage.getItem('user_role')).toBe('ADMIN')
    expect(getAccessToken()).toBe('acc-123')
    expect(getRole()).toBe('ADMIN')
    expect(hasAccessToken()).toBe(true)
  })

  // R2c：clearTokens 移除 ACCESS+ROLE，并清理存量 'refresh_token' 键（TASK-205 行为锁定）
  it('R2c clearTokens 移除 access/role 与存量 refresh_token 键', () => {
    setTokens('acc-123', 'USER')
    localStorage.setItem('refresh_token', 'legacy-refresh-value')
    clearTokens()
    expect(localStorage.getItem('access_token')).toBeNull()
    expect(localStorage.getItem('user_role')).toBeNull()
    expect(localStorage.getItem('refresh_token')).toBeNull()
    expect(hasAccessToken()).toBe(false)
    expect(getAccessToken()).toBeNull()
    expect(getRole()).toBeNull()
  })

  // R2d：无 handler 时仅清理、不抛。
  // 注意：本文件首次调用 setAuthFailHandler 之前执行（声明序先于 R2e/R2f），
  // 故此处 authFailHandler 必为 null（模块级状态，vitest 逐文件隔离、同文件内顺序执行）。
  it('R2d triggerAuthFail 无 handler 仅清理不抛', () => {
    setTokens('acc-123', 'USER')
    expect(() => triggerAuthFail()).not.toThrow()
    expect(localStorage.getItem('access_token')).toBeNull()
    expect(localStorage.getItem('user_role')).toBeNull()
  })

  // R2e：注册的 handler 被调用
  it('R2e 注册的 handler 在 triggerAuthFail 时被调用', () => {
    const handler = vi.fn()
    setAuthFailHandler(handler)
    setTokens('acc-123', 'USER')
    triggerAuthFail()
    expect(handler).toHaveBeenCalledTimes(1)
    // 触发前清理：handler 被调用时 access 已移除
    expect(localStorage.getItem('access_token')).toBeNull()
  })

  // R2f：handler 抛异常被吞没、不外传
  it('R2f handler 抛异常被吞没且不外传', () => {
    const handler = vi.fn(() => {
      throw new Error('handler boom')
    })
    setAuthFailHandler(handler)
    setTokens('acc-123', 'USER')
    expect(() => triggerAuthFail()).not.toThrow()
    expect(handler).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('access_token')).toBeNull()
  })
})
