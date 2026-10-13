import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, type AxiosResponse } from 'axios'
import api from '@/api/client'
import { getAccessToken, setAuthFailHandler, setTokens } from '@/utils/token'
import { delRecord, getFeed } from '@/api/client'

// adapter 注入范式：替换 api.defaults.adapter 为 vi.fn 模拟传输层，
// 请求/响应两段拦截器链全真实执行（不 mock axios 自身）。
let adapter: ReturnType<typeof vi.fn>

beforeEach(() => {
  localStorage.clear()
  adapter = vi.fn()
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(api.defaults as any).adapter = adapter
})

// 构造成功响应（200 + 业务 body）
function ok(config: unknown, data: unknown, status = 200): AxiosResponse {
  return {
    data,
    status,
    statusText: String(status),
    headers: {},
    config: config as never,
    request: {},
  } as AxiosResponse
}

// 构造 401 失败（reject 一个带 response 的 AxiosError，形似真实传输层）
function unauthorized(config: unknown, body: unknown = { code: 1001, message: '认证失败' }): Promise<never> {
  const response = ok(config, body, 401)
  return Promise.reject(
    new AxiosError('Request failed with status code 401', 'ERR_BAD_REQUEST', config as never, {}, response),
  )
}

// 读取请求配置上的 Authorization 头（AxiosHeaders 与普通对象两态）
function authHeaderOf(config: unknown): string | undefined {
  const headers = (config as { headers?: Record<string, unknown> & { get?: (n: string) => unknown } })?.headers
  if (!headers) return undefined
  const value = typeof headers.get === 'function' ? headers.get('Authorization') : headers['Authorization']
  return value == null ? undefined : String(value)
}

describe('client.ts 拦截器锚点（TASK-215）', () => {
  // R3a：无 token 请求不携带 Authorization 头
  it('R3a 无 token 请求不携带 Authorization 头', async () => {
    adapter.mockImplementationOnce((config: unknown) =>
      Promise.resolve(ok(config, { code: 0, message: 'ok', data: [] })),
    )
    await api.get('/user/api/notifications')
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(authHeaderOf(adapter.mock.calls[0][0])).toBeUndefined()
  })

  // R3b：有 token 请求携带 Bearer
  it('R3b 有 token 请求携带 Bearer', async () => {
    setTokens('tok-abc', 'USER')
    adapter.mockImplementationOnce((config: unknown) =>
      Promise.resolve(ok(config, { code: 0, message: 'ok', data: [] })),
    )
    await api.get('/user/api/notifications')
    expect(authHeaderOf(adapter.mock.calls[0][0])).toBe('Bearer tok-abc')
  })

  // R3c：code=0 响应 resolve 出 body（拦截器透传 body 本身，而非 AxiosResponse）
  it('R3c code=0 响应 resolve 出 body', async () => {
    const body = { code: 0, message: 'ok', data: { liked: true } }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, body)))
    const result = await api.post('/record/api/records/1/like', {})
    expect(result).toEqual(body)
  })

  // R3d：code!=0 reject 且 err.code/err.message 正确
  it('R3d code!=0 reject 且 err.code/err.message 正确', async () => {
    adapter.mockImplementationOnce((config: unknown) =>
      Promise.resolve(ok(config, { code: 6001, message: '未通过校验的记录不可点赞', data: null })),
    )
    await expect(api.post('/record/api/records/1/like', {})).rejects.toMatchObject({
      code: 6001,
      message: '未通过校验的记录不可点赞',
    })
  })

  // R3e：401 落在 /api/auth/* URL 立即 reject 零 refresh（adapter 恰 1 次调用）
  it('R3e 401 落在 /api/auth/* URL 立即 reject 零 refresh', async () => {
    adapter.mockImplementationOnce((config: unknown) => unauthorized(config))
    await expect(api.post('/api/auth/login', { phone: 'p', password: 'w' })).rejects.toMatchObject({
      status: 401,
      code: 1001,
    })
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  // R3f：业务 URL 401 触发 refresh POST（withCredentials:true）后携新 token 单次重试原请求
  it('R3f 业务 URL 401 经 refresh 后携新 token 重试一次', async () => {
    setTokens('oldtok', 'USER')
    // 重试复用同一 config 对象（interceptor 原地改 headers），故按调用时刻快照 Authorization
    const businessAuths: Array<string | undefined> = []
    adapter.mockImplementation((config: unknown) => {
      const url = String((config as { url?: string }).url || '')
      if (url.includes('/api/auth/refresh')) {
        return Promise.resolve(
          ok(config, { code: 0, message: 'ok', data: { accessToken: 'newtok', role: 'USER' } }),
        )
      }
      businessAuths.push(authHeaderOf(config))
      if (businessAuths.length === 1) return unauthorized(config)
      return Promise.resolve(ok(config, { code: 0, message: 'ok', data: { liked: true } }))
    })

    const result = await api.post('/record/api/records/1/like', {})
    expect(result).toEqual({ code: 0, message: 'ok', data: { liked: true } })

    // refresh 调用存在且 withCredentials=true
    const refreshCall = adapter.mock.calls.find((c) => String(c[0].url).includes('/api/auth/refresh'))
    expect(refreshCall).toBeTruthy()
    expect((refreshCall![0] as { withCredentials?: boolean }).withCredentials).toBe(true)

    // 原请求恰重试一次：第一次携旧 token，第二次携新 token
    expect(businessAuths).toHaveLength(2)
    expect(businessAuths[0]).toBe('Bearer oldtok')
    expect(businessAuths[1]).toBe('Bearer newtok')
    // adapter 总调用 = 原请求 + refresh + 重试 = 3
    expect(adapter).toHaveBeenCalledTimes(3)
    // 新 token 已落库
    expect(getAccessToken()).toBe('newtok')
  })

  // R3g：refresh 自身 401 → triggerAuthFail 被调 + reject
  it('R3g refresh 自身 401 → triggerAuthFail 被调并 reject', async () => {
    const failHandler = vi.fn()
    setAuthFailHandler(failHandler)
    setTokens('oldtok', 'USER')
    adapter.mockImplementation((config: unknown) => {
      const url = String((config as { url?: string }).url || '')
      if (url.includes('/api/auth/refresh')) {
        return unauthorized(config, { code: 1001, message: 'refresh 失败' })
      }
      return unauthorized(config)
    })

    await expect(api.post('/record/api/records/1/like', {})).rejects.toBeTruthy()
    expect(failHandler).toHaveBeenCalledTimes(1)
    // 原请求 + refresh = 2 次，无重试
    expect(adapter).toHaveBeenCalledTimes(2)
  })
})

// ===== 好友动态 feed（TASK-222 add-friend-feed） =====
describe('client.ts feed（TASK-222）', () => {
  // R1：getFeed 请求打 GET /record/api/records/feed 且携带 page/size 查询参数
  it('R1 getFeed hits /record/api/records/feed with page/size params', async () => {
    const pageBody = {
      code: 0,
      message: 'ok',
      data: {
        current: 1,
        size: 20,
        total: 1,
        records: [
          {
            id: 9,
            sportType: 1,
            distance: 12.5,
            status: 2,
            visibility: 0,
            createdAt: '2026-10-01T10:00:00',
            authorUserId: 201,
            authorNickname: 'B',
          },
        ],
      },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, pageBody)))

    const res = await getFeed(3, 50)

    expect(res.data.current).toBe(1)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; params?: Record<string, number> }
    expect(call.url).toBe('/record/api/records/feed')
    expect(call.params).toEqual({ page: 3, size: 50 })
  })
})

// ===== 记录删除（TASK-223 add-record-deletion） =====
describe('client.ts delRecord（TASK-223）', () => {
  // R1：delRecord 请求打 DELETE /record/api/records/{id}
  it('R1 delRecord hits DELETE /record/api/records/{id}', async () => {
    const okBody = { code: 0, message: 'ok', data: null }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, okBody)))

    const res = await delRecord(123)

    expect(res).toEqual(okBody)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string }
    expect(call.url).toBe('/record/api/records/123')
    expect(call.method?.toLowerCase()).toBe('delete')
  })
})
