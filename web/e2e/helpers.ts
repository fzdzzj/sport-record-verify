import { expect, Page, Route } from '@playwright/test'
import { createRecordStore, PAGE_SIZE } from './fixtures/records'
import { createFeedStore } from './fixtures/feed'
import { createGoalStore, type GoalUpsert } from './fixtures/goals'
import { NOTIFICATION_LIST, NOTIFICATION_PREFERENCES, UNREAD_COUNT } from './fixtures/notifications'

/**
 * route-mock 装配（TASK-227）：page.route 拦截 /record/api/* 与 /user/api/* 返回固定夹具，
 * 并在 page.on('request') 记录全部 /api/ 请求——两前缀之外的任何 API 请求记入 unhandled
 * （断言 no-unhandled = 零真实网络请求）。WebSocket（/user/ws-notifications，STOMP 推送）
 * 不属于 HTTP 请求面，preview 下连接失败被客户端静默降级，见 handoff 说明。
 */
export interface ApiCtx {
  apiRequests: string[]
  unhandled: string[]
  submitBodies: Record<string, unknown>[]
}

export function createApiCtx(): ApiCtx {
  return { apiRequests: [], unhandled: [], submitBodies: [] }
}

export function ok(data: unknown): { status: number; contentType: string; body: string } {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, message: 'success', data }),
  }
}

export function okPage(records: unknown[], total: number, current: number, size: number) {
  return ok({ current, size, total, records })
}

/** 预置登录态（access_token / user_role 写入 localStorage，取值口径同 token.ts） */
export async function seedSession(page: Page, role = 'USER'): Promise<void> {
  await page.addInitScript((r) => {
    localStorage.setItem('access_token', 'e2e-dummy-access-token')
    localStorage.setItem('user_role', r)
  }, role)
}

export function assertNoUnhandled(ctx: ApiCtx): void {
  expect(ctx.unhandled, `发现两前缀外未覆盖 API 请求: ${ctx.unhandled.join(', ')}`).toEqual([])
}

export interface Stores {
  records: ReturnType<typeof createRecordStore>
  feed: ReturnType<typeof createFeedStore>
  goals: ReturnType<typeof createGoalStore>
}

export function createStores(): Stores {
  return { records: createRecordStore(), feed: createFeedStore(), goals: createGoalStore() }
}

export async function installRouteMocks(page: Page, ctx: ApiCtx, stores: Stores): Promise<void> {
  await page.route('**/record/api/**', (route) => handleRecord(route, ctx, stores))
  await page.route('**/user/api/**', (route) => handleUser(route, stores))
  page.on('request', (req) => {
    const p = new URL(req.url()).pathname
    // 仅统计后端 API 前缀（排除 dev server 下的 /src/api/*.ts 源码模块）
    if (!API_PREFIX_RE.test(p)) return
    ctx.apiRequests.push(`${req.method()} ${p}`)
    if (!p.startsWith('/record/api/') && !p.startsWith('/user/api/')) {
      ctx.unhandled.push(`${req.method()} ${p}`)
    }
  })
}

/** 后端 API 前缀白名单（两前缀 route-mock 之外的前缀命中即 unhandled） */
const API_PREFIX_RE = /^\/(api|record|user|leaderboard|verify|admin|mapmatch)\//

async function handleRecord(route: Route, ctx: ApiCtx, stores: Stores): Promise<void> {
  const req = route.request()
  const url = new URL(req.url())
  const p = url.pathname
  const method = req.method()

  if (p === '/record/api/records' && method === 'POST') {
    const body = (req.postDataJSON() || {}) as Record<string, unknown>
    ctx.submitBodies.push(body)
    route.fulfill(
      ok({
        recordId: 9001,
        requestId: body.requestId || '',
        sportType: body.sportType ?? 1,
        status: 0,
        duplicated: false,
      }),
    )
    return
  }
  if (p === '/record/api/records/my' && method === 'GET') {
    const page = Number(url.searchParams.get('page') || '1')
    const size = Number(url.searchParams.get('size') || String(PAGE_SIZE))
    const { records, total } = stores.records.pageFiltered(page, size, url.searchParams)
    route.fulfill(okPage(records, total, page, size))
    return
  }
  if (p === '/record/api/records/my/streak' && method === 'GET') {
    route.fulfill(ok({ current: 5, longest: 12 }))
    return
  }
  if (p === '/record/api/records/my/goals' && method === 'GET') {
    route.fulfill(ok(stores.goals.goals))
    return
  }
  if (p === '/record/api/records/my/goals' && method === 'POST') {
    const body = (req.postDataJSON() || {}) as GoalUpsert
    route.fulfill(ok(stores.goals.upsert(body)))
    return
  }
  const goalDel = p.match(/^\/record\/api\/records\/my\/goals\/(\d+)$/)
  if (goalDel && method === 'DELETE') {
    stores.goals.deleteById(Number(goalDel[1]))
    route.fulfill(ok(null))
    return
  }
  if (p === '/record/api/records/feed' && method === 'GET') {
    const page = Number(url.searchParams.get('page') || '1')
    const size = Number(url.searchParams.get('size') || '20')
    const items = stores.feed.items
    const start = (page - 1) * size
    route.fulfill(okPage(items.slice(start, start + size), items.length, page, size))
    return
  }
  const recDel = p.match(/^\/record\/api\/records\/(\d+)$/)
  if (recDel && method === 'DELETE') {
    stores.records.deleteById(Number(recDel[1]))
    route.fulfill(ok(null))
    return
  }
  const points = p.match(/^\/record\/api\/records\/(\d+)\/points$/)
  if (points && method === 'GET') {
    route.fulfill(okPage([], 0, 1, 5))
    return
  }
  const verify = p.match(/^\/record\/api\/records\/(\d+)\/verify-result$/)
  if (verify && method === 'GET') {
    route.fulfill(ok({ recordId: Number(verify[1]), verdict: 1, score: 88, checkedAt: '2026-10-14T09:00:00' }))
    return
  }
  const like = p.match(/^\/record\/api\/records\/(\d+)\/like$/)
  if (like) {
    route.fulfill(ok({ recordId: Number(like[1]), likeCount: 2, liked: method === 'POST' }))
    return
  }
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}

async function handleUser(route: Route, stores: Stores): Promise<void> {
  const req = route.request()
  const url = new URL(req.url())
  const p = url.pathname
  const method = req.method()

  if (p === '/user/api/notifications/unread-count' && method === 'GET') {
    route.fulfill(ok(UNREAD_COUNT))
    return
  }
  if (p === '/user/api/notifications' && method === 'GET') {
    const page = Number(url.searchParams.get('page') || '1')
    const size = Number(url.searchParams.get('size') || '10')
    const start = (page - 1) * size
    route.fulfill(okPage(NOTIFICATION_LIST.slice(start, start + size), NOTIFICATION_LIST.length, page, size))
    return
  }
  if (p === '/user/api/notifications/preferences' && method === 'GET') {
    route.fulfill(ok(NOTIFICATION_PREFERENCES))
    return
  }
  if (p === '/user/api/notifications/preferences' && method === 'PUT') {
    route.fulfill(ok(NOTIFICATION_PREFERENCES))
    return
  }
  const readOne = p.match(/^\/user\/api\/notifications\/(\d+)\/read$/)
  if (readOne && method === 'PATCH') {
    route.fulfill(ok(null))
    return
  }
  if (p === '/user/api/notifications/read-all' && method === 'PATCH') {
    route.fulfill(ok(2))
    return
  }
  if (p === '/user/api/friends' && method === 'GET') {
    route.fulfill(
      okPage(
        [
          { userId: 8, nickname: '跑友A', createdAt: '2026-10-01T08:00:00' },
          { userId: 9, nickname: '跑友B', createdAt: '2026-10-02T08:00:00' },
        ],
        2,
        1,
        20,
      ),
    )
    return
  }
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}
