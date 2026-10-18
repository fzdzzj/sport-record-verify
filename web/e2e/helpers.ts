import { expect, Page, Route } from '@playwright/test'
import { createRecordStore, myStats, PAGE_SIZE } from './fixtures/records'
import { createFeedStore } from './fixtures/feed'
import { createGoalStore, type GoalUpsert } from './fixtures/goals'
import { createNotificationStore, NOTIFICATION_PREFERENCES } from './fixtures/notifications'

/**
 * route-mock 装配（TASK-227）：page.route 拦截 /record/api/* 与 /user/api/* 返回固定夹具，
 * 并在 page.on('request') 记录全部 /api/ 请求——两前缀之外的任何 API 请求记入 unhandled
 * （断言 no-unhandled = 零真实网络请求）。WebSocket（/user/ws-notifications，STOMP 推送）
 * 不属于 HTTP 请求面，preview 下连接失败被客户端静默降级，见 handoff 说明。
 * TASK-257 E14 mock 兜底可见性：两前缀「之内」未映射请求原兜底 404（code:9999）不入任何
 * 断言 → 假安全；现兜底 404 前记入 ctx.unmocked（断言 no-unmocked = 零未映射兜底）。
 */
export interface ApiCtx {
  apiRequests: string[]
  unhandled: string[]
  /** 两前缀内未映射请求（兜底 404 前记入，TASK-257 E14） */
  unmocked: string[]
  submitBodies: Record<string, unknown>[]
}

export function createApiCtx(): ApiCtx {
  return { apiRequests: [], unhandled: [], unmocked: [], submitBodies: [] }
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

/** 两前缀内零未映射兜底 404（TASK-257 E14：mock 兜底假安全收口） */
export function assertNoUnmocked(ctx: ApiCtx): void {
  expect(ctx.unmocked, `发现两前缀内未映射请求(兜底404): ${ctx.unmocked.join(', ')}`).toEqual([])
}

export interface Stores {
  records: ReturnType<typeof createRecordStore>
  feed: ReturnType<typeof createFeedStore>
  goals: ReturnType<typeof createGoalStore>
  /** 通知状态记忆（TASK-257 E14）：列表/未读数随标已读/批量/清空写操作流转 */
  notifications: ReturnType<typeof createNotificationStore>
  /** 点赞态记忆（TASK-236）：key=recordId → { liked, likeCount }，POST +1 / DELETE -1 / GET 返回当前态 */
  likes: Map<number, { liked: boolean; likeCount: number }>
}

export function createStores(): Stores {
  return {
    records: createRecordStore(),
    feed: createFeedStore(),
    goals: createGoalStore(),
    notifications: createNotificationStore(),
    likes: new Map(),
  }
}

export async function installRouteMocks(page: Page, ctx: ApiCtx, stores: Stores): Promise<void> {
  await page.route('**/record/api/**', (route) => handleRecord(route, ctx, stores))
  await page.route('**/user/api/**', (route) => handleUser(route, ctx, stores))
  // TASK-272：赛段请求面（mapmatch 赛段列表/匹配 + leaderboard 赛段榜）由 route-mock 承接，
  // 命中分支即无真实网络；未映射分支仍记入 ctx.unmocked 断言兜底（见 handleMapmatch 等）
  await page.route('**/mapmatch/segments*', (route) => handleMapmatch(route, ctx))
  // TASK-274：轨迹纠偏对比请求面由 route-mock 承接（命中分支即无真实网络）
  await page.route('**/mapmatch/match/compare*', (route) => handleMapmatch(route, ctx))
  await page.route('**/leaderboard/api/leaderboard/segments*', (route) => handleLeaderboardSegments(route, ctx))
  // TASK-275：跑团团队里程榜请求面由 route-mock 承接（命中分支即无真实网络）
  await page.route('**/leaderboard/api/leaderboard/clubs*', (route) => handleLeaderboardClubs(route, ctx))
  page.on('request', (req) => {
    const p = new URL(req.url()).pathname
    // 仅统计后端 API 前缀（排除 dev server 下的 /src/api/*.ts 源码模块）
    if (!API_PREFIX_RE.test(p)) return
    ctx.apiRequests.push(`${req.method()} ${p}`)
    // 赛段与跑团请求面已由 route-mock 承接（上方分支），不再计入 unhandled 真实网络风险面
    const prefixMocked = p.startsWith('/mapmatch/segments')
      || p.startsWith('/mapmatch/match/compare')
      || p.startsWith('/leaderboard/api/leaderboard/segments/')
      || p.startsWith('/leaderboard/api/leaderboard/clubs')
    if (!p.startsWith('/record/api/') && !p.startsWith('/user/api/') && !prefixMocked) {
      ctx.unhandled.push(`${req.method()} ${p}`)
    }
  })
}

/** 后端 API 前缀白名单（两前缀 route-mock 之外的前缀命中即 unhandled） */
const API_PREFIX_RE = /^\/(api|record|user|leaderboard|verify|admin|mapmatch)\//

// ===== 赛段夹具（TASK-272）：与后端预置兜底赛段同口径（世纪公园内圈冲刺段 / 陆家嘴滨江挑战段） =====
const SEGMENT_FIXTURES = [
  { id: 1, name: '世纪公园内圈 1.2km 冲刺段', sportType: 1, distanceMeters: 1195.5, createdAt: null },
  { id: 2, name: '陆家嘴滨江 2.0km 挑战段', sportType: 2, distanceMeters: 2014.1, createdAt: null },
]

/** 赛段匹配夹具：命中预置赛段一，isPr/isKom 为 null（上报后由 leaderboard 回填） */
const SEGMENT_MATCH_FIXTURES = [
  {
    segmentId: 1,
    segmentName: '世纪公园内圈 1.2km 冲刺段',
    recordId: 9001,
    userId: 100,
    elapsedSeconds: 480,
    startTime: 1700000000000,
    endTime: 1700000480000,
    avgSpeed: 2.49,
    isPr: null,
    isKom: null,
  },
]

/** 赛段 KOM 榜夹具：耗时升序，rank 1 即擂主（KOM 👑） */
const SEGMENT_BOARD_FIXTURES = [
  { rank: 1, userId: 100, nickname: '演示用户', elapsedSeconds: 480, recordId: 9001, paceFormatted: "6'41\"" },
  { rank: 2, userId: 101, nickname: '跑友甲', elapsedSeconds: 510, recordId: 9002, paceFormatted: "7'07\"" },
]

/** 公里分段切片夹具（TASK-273）：3.5km 四段（300/360/330s + 500m 余程 200s），最快第 1 公里、最慢第 2 公里 */
const RECORD_SPLITS_FIXTURE = {
  recordId: 9001,
  totalDistanceMeters: 3500,
  totalElapsedSeconds: 1190,
  splits: [
    { km: 1, distanceMeters: 1000, elapsedSeconds: 300, paceFormatted: "5'00\"", avgSpeed: 3.33, isFastest: true, isSlowest: false, elevationGain: 0, elevationLoss: 0 },
    { km: 2, distanceMeters: 1000, elapsedSeconds: 360, paceFormatted: "6'00\"", avgSpeed: 2.78, isFastest: false, isSlowest: true, elevationGain: 0, elevationLoss: 0 },
    { km: 3, distanceMeters: 1000, elapsedSeconds: 330, paceFormatted: "5'30\"", avgSpeed: 3.03, isFastest: false, isSlowest: false, elevationGain: 0, elevationLoss: 0 },
    { km: 4, distanceMeters: 500, elapsedSeconds: 200, paceFormatted: "6'40\"", avgSpeed: 2.5, isFastest: false, isSlowest: false, elevationGain: 0, elevationLoss: 0 },
  ],
  fastestKm: 1,
  slowestKm: 2,
  totalElevationGain: 0,
  totalElevationLoss: 0,
  hasElevationData: false,
}

/** 轨迹纠偏对比夹具（TASK-274）：10 点采样、4 处纠偏、吸附率 0.85 */
const TRACK_COMPARE_FIXTURE = {
  snappedPoints: [
    { seq: 0, lat: 31.2453307, lng: 121.4576321, ts: 1700000000000 },
    { seq: 1, lat: 31.2440070, lng: 121.4580362, ts: 1700000015000 },
    { seq: 2, lat: 31.2407742, lng: 121.4593788, ts: 1700000030000 },
    { seq: 3, lat: 31.2388112, lng: 121.4603849, ts: 1700000045000 },
    { seq: 4, lat: 31.2368527, lng: 121.4615065, ts: 1700000060000 },
    { seq: 5, lat: 31.2363787, lng: 121.4616759, ts: 1700000075000 },
    { seq: 6, lat: 31.2351912, lng: 121.4619259, ts: 1700000090000 },
    { seq: 7, lat: 31.2339695, lng: 121.4621549, ts: 1700000105000 },
    { seq: 8, lat: 31.2332522, lng: 121.4623037, ts: 1700000120000 },
    { seq: 9, lat: 31.2327292, lng: 121.4623708, ts: 1700000135000 },
  ],
  correctedCount: 4,
  matchedRatio: 0.85,
  offRoadRatio: 0.15,
  avgOffRoadDistance: 12.5,
  maxOffRoadDistance: 45.0,
  totalPoints: 10,
}

/** 每周运动智能周报夹具（TASK-274）：周一至周日，环比 +15.2%、出勤 3/7、击败 62% */
const WEEKLY_DIGEST_FIXTURE = {
  userId: 100,
  nickname: '演示用户',
  weekStartDate: '2026-10-12',
  weekEndDate: '2026-10-18',
  totalDistanceMeters: 18450.0,
  totalElapsedSeconds: 6300,
  totalCalories: 1240.5,
  workoutCount: 4,
  activeDays: 3,
  distanceGrowthRate: 0.152,
  beatRatio: 0.62,
  primarySportType: 'RUNNING',
  fastestPaceFormatted: "5'24\"",
  recoveryAdvice: '本周节奏不错，保持现有频次，跑前充分热身、跑后注意拉伸与补水即可稳步进步。',
}

/** 跑团广场夹具（TASK-275）：正常跑团两支，creatorNickname 已由后端批量补齐 */
const CLUB_LIST_FIXTURE = [
  { id: 7, name: '黎明跑团', description: '晨跑为主的城市跑团', badgeUrl: '', creatorId: 100, creatorNickname: '演示用户', memberCount: 12, status: 1, createdAt: '2026-10-01T07:00:00' },
  { id: 9, name: '晚风跑团', description: '夜跑与周末长距离', badgeUrl: '', creatorId: 101, creatorNickname: '跑友甲', memberCount: 5, status: 1, createdAt: '2026-10-03T20:00:00' },
]

/** 我的跑团夹具（TASK-275）：未入团 data 为 null（index「我的跑团」卡片引导态） */
const MY_CLUB_FIXTURE = null

/** 成员名单夹具（TASK-275）：按加入时间升序，团长/团员角色齐全 */
const CLUB_MEMBERS_FIXTURE = [
  { id: 1, clubId: 7, userId: 100, nickname: '演示用户', role: 'CAPTAIN', joinedAt: '2026-10-01T07:00:00' },
  { id: 2, clubId: 7, userId: 101, nickname: '跑友甲', role: 'MEMBER', joinedAt: '2026-10-02T08:00:00' },
]

/** 跑团团队里程榜夹具（TASK-275）：ZSet 里程降序，memberCount 为预留字段 */
const CLUB_BOARD_FIXTURE = [
  { rank: 1, clubId: 7, clubName: '黎明跑团', memberCount: 12, totalDistanceKm: 15.35, formattedDistance: '15.35 km' },
  { rank: 2, clubId: 9, clubName: '晚风跑团', memberCount: 5, totalDistanceKm: 8.0, formattedDistance: '8.00 km' },
]

/** 赛段相关 mock（TASK-272）：GET /mapmatch/segments 列表 + POST /mapmatch/segments/match 匹配；
 *  TASK-274：POST /mapmatch/match/compare 轨迹纠偏对比 */
async function handleMapmatch(route: Route, ctx: ApiCtx): Promise<void> {
  const req = route.request()
  const p = new URL(req.url()).pathname
  const method = req.method()
  if (p === '/mapmatch/segments' && method === 'GET') {
    route.fulfill(ok(SEGMENT_FIXTURES))
    return
  }
  if (p === '/mapmatch/segments/match' && method === 'POST') {
    route.fulfill(ok(SEGMENT_MATCH_FIXTURES))
    return
  }
  if (p === '/mapmatch/match/compare' && method === 'POST') {
    route.fulfill(ok(TRACK_COMPARE_FIXTURE))
    return
  }
  ctx.unmocked.push(`${method} ${p}`)
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}

/** 赛段 KOM 榜 mock（TASK-272）：GET /leaderboard/api/leaderboard/segments/{id} + POST effort 回执 */
async function handleLeaderboardSegments(route: Route, ctx: ApiCtx): Promise<void> {
  const req = route.request()
  const p = new URL(req.url()).pathname
  const method = req.method()
  const board = p.match(/^\/leaderboard\/api\/leaderboard\/segments\/(\d+)$/)
  if (board && method === 'GET') {
    route.fulfill(ok(SEGMENT_BOARD_FIXTURES))
    return
  }
  const effort = p.match(/^\/leaderboard\/api\/leaderboard\/segments\/(\d+)\/effort$/)
  if (effort && method === 'POST') {
    const body = (req.postDataJSON() || {}) as Record<string, unknown>
    route.fulfill(
      ok({
        segmentId: Number(effort[1]),
        segmentName: null,
        recordId: body.recordId ?? null,
        userId: 100,
        elapsedSeconds: body.elapsedSeconds ?? null,
        isPr: true,
        isKom: true,
      }),
    )
    return
  }
  ctx.unmocked.push(`${method} ${p}`)
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}

/** 跑团团队里程榜 mock（TASK-275）：GET /leaderboard/api/leaderboard/clubs（period=overall|weekly） */
async function handleLeaderboardClubs(route: Route, ctx: ApiCtx): Promise<void> {
  const req = route.request()
  const p = new URL(req.url()).pathname
  const method = req.method()
  if (p === '/leaderboard/api/leaderboard/clubs' && method === 'GET') {
    route.fulfill(ok(CLUB_BOARD_FIXTURE))
    return
  }
  ctx.unmocked.push(`${method} ${p}`)
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}

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
  if (p === '/record/api/records/my/stats' && method === 'GET') {
    // TASK-233：补齐 /my/stats 契约 mock（此前落入 404 兜底 → 前端回退 0 值致统计卡断言失败）
    route.fulfill(ok(myStats()))
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
  if (p === '/record/api/records/likes/batch' && method === 'POST') {
    // TASK-257 E14 清障补 mock（此前兜底 404、feed loadLikes 静默吞错）：按请求 id 列表返回当前点赞态
    const ids = (req.postDataJSON() || []) as number[]
    route.fulfill(
      ok(
        ids.map((id) => {
          const prev = stores.likes.get(id)
          return { recordId: id, likeCount: prev?.likeCount ?? 0, liked: prev?.liked ?? false }
        }),
      ),
    )
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
  // TASK-273：公里分段切片 mock（详情页 /splits；points mock 为空时页面不发起，分支为防御性兜底）
  const splits = p.match(/^\/record\/api\/records\/(\d+)\/splits$/)
  if (splits && method === 'GET') {
    route.fulfill(ok(RECORD_SPLITS_FIXTURE))
    return
  }
  // TASK-274：每周运动智能周报 mock（首页周报卡片挂载期拉取）
  if (p === '/record/api/records/weekly-digest' && method === 'GET') {
    route.fulfill(ok(WEEKLY_DIGEST_FIXTURE))
    return
  }
  const verify = p.match(/^\/record\/api\/records\/(\d+)\/verify-result$/)
  if (verify && method === 'GET') {
    route.fulfill(ok({ recordId: Number(verify[1]), verdict: 1, score: 88, checkedAt: '2026-10-14T09:00:00' }))
    return
  }
  const like = p.match(/^\/record\/api\/records\/(\d+)\/like$/)
  if (like) {
    // 动态状态记忆（TASK-236）：POST 赞 +1、DELETE 踩 -1、GET 返回当前态；
    // 未记忆初始态 = 0 赞/未赞，避免固定 mock 导致连续点赞/取消断言失败
    const id = Number(like[1])
    const prev = stores.likes.get(id)
    let likeCount = prev?.likeCount ?? 0
    let liked = prev?.liked ?? false
    if (method === 'POST') {
      liked = true
      likeCount += 1
    } else if (method === 'DELETE') {
      liked = false
      likeCount = Math.max(0, likeCount - 1)
    }
    stores.likes.set(id, { liked, likeCount })
    route.fulfill(ok({ recordId: id, likeCount, liked }))
    return
  }
  // TASK-271: 补齐勋章墙与佩戴状态 mock（解决 TASK-269 遗留 unmocked 缺口）
  if (p === '/record/api/records/my/badges' && method === 'GET') {
    route.fulfill(ok([]))
    return
  }
  const equipped = p.match(/^\/record\/api\/records\/users\/(\d+)\/equipped-badge$/)
  if (equipped && method === 'GET') {
    route.fulfill(ok(null))
    return
  }
  const wear = p.match(/^\/record\/api\/records\/my\/badges\/([A-Za-z0-9_]+)\/wear$/)
  if (wear && method === 'PUT') {
    route.fulfill(ok(null))
    return
  }
  if (p === '/record/api/records/my/badges/unwear' && method === 'PUT') {
    route.fulfill(ok(null))
    return
  }
  ctx.unmocked.push(`${method} ${p}`)
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}

async function handleUser(route: Route, ctx: ApiCtx, stores: Stores): Promise<void> {
  const req = route.request()
  const url = new URL(req.url())
  const p = url.pathname
  const method = req.method()

  if (p === '/user/api/notifications/unread-count' && method === 'GET') {
    // 状态记忆（TASK-257 E14）：未读数随写操作实时流转（顶栏徽标初值 3 = 列表未读派生）
    route.fulfill(ok(stores.notifications.unreadCount))
    return
  }
  if (p === '/user/api/notifications' && method === 'GET') {
    const page = Number(url.searchParams.get('page') || '1')
    const size = Number(url.searchParams.get('size') || '10')
    const { records, total } = stores.notifications.page(page, size)
    route.fulfill(okPage(records, total, page, size))
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
    // 状态记忆：命中未读行置读（1）否则不流转（0），后续列表/未读数查询反映变更
    route.fulfill(ok(stores.notifications.markRead(Number(readOne[1])) ? 1 : 0))
    return
  }
  if (p === '/user/api/notifications/read-all' && method === 'PATCH') {
    route.fulfill(ok(stores.notifications.markAllRead()))
    return
  }
  if (p === '/user/api/notifications/batch-read' && method === 'PATCH') {
    // TASK-257 E14 补 mock（此前兜底 404）：body 为通知 id 列表，返回流转条数
    const ids = (req.postDataJSON() || []) as number[]
    route.fulfill(ok(stores.notifications.batchRead(ids)))
    return
  }
  if (p === '/user/api/notifications/read' && method === 'DELETE') {
    // TASK-257 E14 补 mock（此前兜底 404）：清空本人已读行，返回删除条数
    route.fulfill(ok(stores.notifications.clearRead()))
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
  if (p === '/user/api/friends/requests' && method === 'GET') {
    // TASK-257 E14 清障补 mock（此前兜底 404）：待处理好友申请列表（空态）
    route.fulfill(okPage([], 0, 1, 20))
    return
  }
  if (p === '/user/api/user/profile' && method === 'GET') {
    // TASK-257 E14 清障补 mock（此前兜底 404）：settings 页挂载 loadProfile 拉取个人资料
    route.fulfill(ok({ id: 100, nickname: '演示用户' }))
    return
  }
  // TASK-275：跑团请求面（index「我的跑团」卡片挂载期拉取 /my；广场页其余分支为防御性覆盖）
  if (p === '/user/api/clubs/my' && method === 'GET') {
    route.fulfill(ok(MY_CLUB_FIXTURE))
    return
  }
  if (p === '/user/api/clubs' && method === 'GET') {
    route.fulfill(okPage(CLUB_LIST_FIXTURE, CLUB_LIST_FIXTURE.length, 1, 20))
    return
  }
  if (p === '/user/api/clubs' && method === 'POST') {
    const body = (req.postDataJSON() || {}) as Record<string, unknown>
    route.fulfill(ok({ ...CLUB_LIST_FIXTURE[0], name: body.name || '黎明跑团', memberCount: 1 }))
    return
  }
  const clubJoin = p.match(/^\/user\/api\/clubs\/(\d+)\/join$/)
  if (clubJoin && method === 'POST') {
    route.fulfill(ok(CLUB_LIST_FIXTURE[0]))
    return
  }
  const clubLeave = p.match(/^\/user\/api\/clubs\/(\d+)\/leave$/)
  if (clubLeave && method === 'POST') {
    route.fulfill(ok(null))
    return
  }
  const clubDetail = p.match(/^\/user\/api\/clubs\/(\d+)$/)
  if (clubDetail && method === 'GET') {
    route.fulfill(ok(CLUB_LIST_FIXTURE[0]))
    return
  }
  const clubMembers = p.match(/^\/user\/api\/clubs\/(\d+)\/members$/)
  if (clubMembers && method === 'GET') {
    route.fulfill(ok(CLUB_MEMBERS_FIXTURE))
    return
  }
  ctx.unmocked.push(`${method} ${p}`)
  route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
}
