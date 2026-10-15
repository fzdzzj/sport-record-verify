import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, type AxiosResponse } from 'axios'
import api from '@/api/client'
import { getAccessToken, setAuthFailHandler, setTokens } from '@/utils/token'
import { deleteGoal, delRecord, deleteFriend, getFeed, getLikesBatch, getMyGoals, getMyRecords, getMyStats, reviewAppeal, submitAppeal, upsertGoal, fetchPendingAppeals, batchMarkNotificationsAsRead } from '@/api/client'


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

// ===== Feed 点赞态批量下推（TASK-240 add-feed-likes-batch） =====
describe('client.ts getLikesBatch（TASK-240）', () => {
  // R1：getLikesBatch 请求打 POST /record/api/records/likes/batch 且 body 为记录 id 列表
  it('R1 getLikesBatch hits POST /record/api/records/likes/batch with payload', async () => {
    const likeBody = {
      code: 0,
      message: 'ok',
      data: [
        { recordId: 9, likeCount: 3, liked: true },
        { recordId: 10, likeCount: 0, liked: false },
      ],
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, likeBody)))

    const ids = [9, 10]
    const res = await getLikesBatch(ids)

    expect(res.data).toHaveLength(2)
    expect(res.data[0].recordId).toBe(9)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string; data?: string }
    expect(call.url).toBe('/record/api/records/likes/batch')
    expect(call.method?.toLowerCase()).toBe('post')
    expect(JSON.parse(call.data || '[]')).toEqual(ids)
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

// ===== 解除好友关系（TASK-242 B14 add-delete-friend） =====
describe('client.ts deleteFriend（TASK-242）', () => {
  // R1：deleteFriend 请求打 DELETE /user/api/friends/{targetUserId}（数字 targetUserId）
  it('R1 deleteFriend hits DELETE /user/api/friends/{targetUserId}', async () => {
    const okBody = { code: 0, message: 'ok', data: null }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, okBody)))

    const res = await deleteFriend(123)

    expect(res).toEqual(okBody)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string }
    expect(call.url).toBe('/user/api/friends/123')
    expect(call.method?.toLowerCase()).toBe('delete')
  })

  // R2：字符串 targetUserId 也被规范化进 URL
  it('R2 deleteFriend accepts string targetUserId', async () => {
    const okBody = { code: 0, message: 'ok', data: null }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, okBody)))

    await deleteFriend('456')

    const call = adapter.mock.calls[0][0] as { url: string; method?: string }
    expect(call.url).toBe('/user/api/friends/456')
    expect(call.method?.toLowerCase()).toBe('delete')
  })
})

// ===== 我的记录筛选（TASK-224 add-my-records-filter） =====
describe('client.ts getMyRecords（TASK-224）', () => {
  // R1：无过滤参数时仅透传 page 与 size
  it('R1 getMyRecords without filters sends page and size only', async () => {
    const pageBody = {
      code: 0,
      message: 'ok',
      data: {
        current: 1,
        size: 20,
        total: 0,
        records: [],
      },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, pageBody)))

    const res = await getMyRecords(1, 20)

    expect(res.data.current).toBe(1)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; params?: Record<string, unknown> }
    expect(call.url).toBe('/record/api/records/my')
    expect(call.params).toEqual({ page: 1, size: 20 })
  })

  // R2：携带过滤参数时透传 sportType/status/startDate/endDate
  it('R2 getMyRecords with filters passes sportType, status, startDate, endDate', async () => {
    const pageBody = {
      code: 0,
      message: 'ok',
      data: {
        current: 2,
        size: 50,
        total: 1,
        records: [
          {
            id: 10,
            sportType: 1,
            distance: 5.0,
            status: 2,
            createdAt: '2026-10-02T10:00:00',
          },
        ],
      },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, pageBody)))

    const res = await getMyRecords(2, 50, {
      sportType: 1,
      status: 2,
      startDate: '2026-10-01',
      endDate: '2026-10-05',
    })

    expect(res.data.total).toBe(1)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; params?: Record<string, unknown> }
    expect(call.url).toBe('/record/api/records/my')
    expect(call.params).toEqual({
      page: 2,
      size: 50,
      sportType: 1,
      status: 2,
      startDate: '2026-10-01',
      endDate: '2026-10-05',
    })
  })
})

// ===== 运动目标（TASK-225 add-sport-goal） =====
describe('client.ts 运动目标三方法（TASK-225）', () => {
  it('R1 getMyGoals hits GET /record/api/records/my/goals', async () => {
    const goalsBody = {
      code: 0,
      message: 'ok',
      data: [
        {
          id: 1,
          userId: 100,
          periodType: 0,
          metric: 0,
          targetValue: 20.0,
          achievedValue: 14.0,
          percent: 70,
        },
      ],
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, goalsBody)))

    const res = await getMyGoals()

    expect(res.data).toHaveLength(1)
    expect(res.data[0].percent).toBe(70)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string }
    expect(call.url).toBe('/record/api/records/my/goals')
    expect(call.method?.toLowerCase() || 'get').toBe('get')
  })

  it('R2 upsertGoal hits POST /record/api/records/my/goals with payload', async () => {
    const goalBody = {
      code: 0,
      message: 'ok',
      data: {
        id: 2,
        userId: 100,
        periodType: 1,
        metric: 1,
        targetValue: 10,
        achievedValue: 0,
        percent: 0,
      },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, goalBody)))

    const payload = { periodType: 1, metric: 1, targetValue: 10 }
    const res = await upsertGoal(payload)

    expect(res.data.id).toBe(2)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string; data?: string }
    expect(call.url).toBe('/record/api/records/my/goals')
    expect(call.method?.toLowerCase()).toBe('post')
    expect(JSON.parse(call.data || '{}')).toEqual(payload)
  })

  it('R3 deleteGoal hits DELETE /record/api/records/my/goals/{id}', async () => {
    const okBody = { code: 0, message: 'ok', data: null }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, okBody)))

    const res = await deleteGoal(456)

    expect(res).toEqual(okBody)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string }
    expect(call.url).toBe('/record/api/records/my/goals/456')
    expect(call.method?.toLowerCase()).toBe('delete')
  })
})

// ===== 个人核心统计（TASK-231 add-my-stats-endpoint） =====
describe('client.ts getMyStats（TASK-231）', () => {
  // R1：getMyStats 打 GET /record/api/records/my/stats，无 query 参数（归属由网关注入）
  it('R1 getMyStats hits GET /record/api/records/my/stats without params', async () => {
    const statsBody = {
      code: 0,
      message: 'ok',
      data: {
        totalCount: 7,
        totalDistance: 22.0,
        passCount: 5,
        passRate: '71.4%',
        dailyDistances: {
          '2026-10-01': 3.5,
          '2026-10-02': 0,
        },
      },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, statsBody)))

    const res = await getMyStats()

    expect(res.data.totalCount).toBe(7)
    expect(res.data.passRate).toBe('71.4%')
    expect(res.data.dailyDistances['2026-10-01']).toBe(3.5)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string; params?: unknown }
    expect(call.url).toBe('/record/api/records/my/stats')
    expect(call.method?.toLowerCase() || 'get').toBe('get')
    expect(call.params).toBeUndefined()
  })
})

// ===== 申诉管理与终判契约（TASK-241） =====
describe('client.ts submitAppeal（TASK-241）', () => {
  // R1：submitAppeal 请求打 POST /record/api/records/{id}/appeal 且载荷（recordId + reason）正确
  it('R1 submitAppeal hits POST /record/api/records/{id}/appeal with payload', async () => {
    const appealBody = {
      code: 0,
      message: 'ok',
      data: { id: 88, recordId: 7, status: 'PENDING' },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, appealBody)))

    const res = await submitAppeal(7, '这是一条申诉理由')

    expect(res.data.id).toBe(88)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string; data?: string }
    expect(call.url).toBe('/record/api/records/7/appeal')
    expect(call.method?.toLowerCase()).toBe('post')
    expect(JSON.parse(call.data || '{}')).toEqual({ recordId: 7, reason: '这是一条申诉理由' })
  })

  // R2：字符串 recordId 也被规范化进 URL 与 body
  it('R2 submitAppeal accepts string recordId and normalizes to number in body', async () => {
    const appealBody = { code: 0, message: 'ok', data: { id: 99, recordId: 12, status: 'PENDING' } }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, appealBody)))

    await submitAppeal('12', '理由')

    const call = adapter.mock.calls[0][0] as { url: string; data?: string }
    expect(call.url).toBe('/record/api/records/12/appeal')
    expect(JSON.parse(call.data || '{}')).toEqual({ recordId: 12, reason: '理由' })
  })
})

describe('client.ts reviewAppeal（TASK-241）', () => {
  // R1：reviewAppeal 请求打 POST /admin/api/appeals/{id}/review 且载荷正确
  it('R1 reviewAppeal hits POST /admin/api/appeals/{id}/review with payload', async () => {
    const reviewBody = {
      code: 0,
      message: 'ok',
      data: { id: 88, recordId: 7, status: 'RE_PASSED', operator: 'admin' },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, reviewBody)))

    const res = await reviewAppeal(88, { operator: 'admin', pass: true, recheckResult: '复核通过' })

    expect(res.data.status).toBe('RE_PASSED')
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string; data?: string }
    expect(call.url).toBe('/admin/api/appeals/88/review')
    expect(call.method?.toLowerCase()).toBe('post')
    expect(JSON.parse(call.data || '{}')).toEqual({
      operator: 'admin',
      pass: true,
      recheckResult: '复核通过',
    })
  })

  // R2：pass=false 且无 recheckResult 时载荷正确
  it('R2 reviewAppeal omits recheckResult when undefined and passes pass=false', async () => {
    const reviewBody = {
      code: 0,
      message: 'ok',
      data: { id: 90, recordId: 9, status: 'REJECTED', operator: 'admin' },
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, reviewBody)))

    await reviewAppeal('90', { operator: 'admin', pass: false })

    const call = adapter.mock.calls[0][0] as { url: string; data?: string }
    expect(call.url).toBe('/admin/api/appeals/90/review')
    expect(JSON.parse(call.data || '{}')).toEqual({ operator: 'admin', pass: false })
  })
})

// ===== 申诉待审队列（B15 申诉待审闭环，TASK-244） =====
describe('client.ts fetchPendingAppeals（B15）', () => {
  // R1：fetchPendingAppeals 请求打 GET /admin/api/appeals/pending，返回待审列表
  it('R1 fetchPendingAppeals hits GET /admin/api/appeals/pending', async () => {
    const pendingBody = {
      code: 0,
      message: 'ok',
      data: [
        { id: 7, recordId: 12, userId: 100, reason: '里程判定有误', status: 0, createdAt: '2026-10-01T10:00:00' },
        { id: 6, recordId: 11, userId: 101, reason: '轨迹遗漏', status: 0, createdAt: '2026-09-30T09:00:00' },
      ],
    }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, pendingBody)))

    const res = await fetchPendingAppeals()

    expect(res.data).toHaveLength(2)
    expect(res.data[0].id).toBe(7)
    expect(res.data[0].status).toBe(0)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string }
    expect(call.url).toBe('/admin/api/appeals/pending')
    expect(call.method?.toLowerCase() || 'get').toBe('get')
  })
})

// ===== 通知批量标记已读（B17 批量标记已读，TASK-244） =====
describe('client.ts batchMarkNotificationsAsRead（B17）', () => {
  // R1：batchMarkNotificationsAsRead 请求打 PATCH /user/api/notifications/batch-read 且 body 为 ID 列表
  it('R1 batchMarkNotificationsAsRead hits PATCH /user/api/notifications/batch-read with ids', async () => {
    const batchBody = { code: 0, message: 'ok', data: 2 }
    adapter.mockImplementationOnce((config: unknown) => Promise.resolve(ok(config, batchBody)))

    const res = await batchMarkNotificationsAsRead([1, 2])

    expect(res.data).toBe(2)
    expect(adapter).toHaveBeenCalledTimes(1)
    const call = adapter.mock.calls[0][0] as { url: string; method?: string; data?: string }
    expect(call.url).toBe('/user/api/notifications/batch-read')
    expect(call.method?.toLowerCase()).toBe('patch')
    expect(JSON.parse(call.data || '[]')).toEqual([1, 2])
  })
})
