import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { getAccessToken, setTokens, triggerAuthFail } from '@/utils/token'

export interface Result<T = any> {
  code: number
  message: string
  data: T
}

// ===== Record Console DTOs (per spec) =====
export interface TrackPointDTO {
  seq: number
  lat: number
  lng: number
  ts: number
  speed?: number
  // 后端 com.sportverify.api.record.dto.TrackPointDTO 会带出所属记录与用户，前端「提取 myUserId」用到
  userId?: number
}

export interface RecordSubmitDTO {
  requestId: string
  sportType: number
  points: TrackPointDTO[]
  // 可见性（RecordVisibility.code：0 公开/默认，1 好友可见，2 仅自己；可选，缺省服务端回退 PUBLIC）
  visibility?: number
  // userId omitted when auth (gateway injects)
}

export interface RecordSubmitResultDTO {
  recordId: number
  requestId: string
  status: number
  duplicated: boolean
  message?: string
}

export interface VerificationResultDTO {
  recordId: number
  verdict: number // 0=VERIFYING, 1=PASSED, 2=REJECTED
  score?: number
  ruleHits?: string
  checkedAt?: string
}

export interface LikeDTO {
  recordId: number
  likeCount: number
  liked: boolean
}

export interface LeaderboardDTO {
  rank: number
  userId: number
  nickname: string
  distance: number
}

export interface FriendDTO {
  userId: number
  nickname: string
  createdAt?: string
}

export interface FriendRequestDTO {
  id: number
  fromUser: number
  toUser: number
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface PageResult<T> {
  current: number
  size: number
  total: number
  records: T[]
}

const api: AxiosInstance = axios.create({
  baseURL: '',  // relative, use Vite proxy
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request interceptor: ONLY set Authorization: Bearer <accessToken> if present.
// NEVER set 'token', 'X-User-Id', 'X-Role' (per add-web-auth-session spec; gateway injects X-* )
api.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// Response interceptor: code===0 success else throw; 401 -> single refresh attempt
api.interceptors.response.use(
  (response) => {
    const body = response.data as Result
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body
      } else {
        const err = new Error(body.message || '请求失败') as any
        err.code = body.code
        err.data = body.data
        err.status = response.status
        throw err
      }
    }
    return response.data
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined
    const status = error.response?.status

    // 401 single refresh logic (per spec): use refresh once; on fail clear & trigger login (prevent loop)
    if (status === 401 && originalRequest && !originalRequest._retry) {
      // do not refresh for auth endpoints themselves (login/refresh 401 means auth fail)
      const url = originalRequest.url || ''
      if (url.includes('/api/auth/login') || url.includes('/api/auth/refresh') || url.includes('/api/auth/register')) {
        const err = new Error( (error.response?.data as any)?.message || error.message || '认证失败' ) as any
        err.code = (error.response?.data as any)?.code || 1001
        err.status = status
        err.original = error
        throw err
      }

      originalRequest._retry = true
      try {
        // refresh 凭证在 HttpOnly cookie 中（JS 不可读），body 无凭证字段；withCredentials 使同源 cookie
        // 随请求携带（跨域时亦携带）。cookie 是否可用由服务端 401 裁决，前端不预设本地值。
        const refreshRes = await api.post('/api/auth/refresh', {}, { withCredentials: true })
        const t = refreshRes.data
        if (t && t.accessToken) {
          setTokens(t.accessToken, t.role || '')
          // update header for retry
          originalRequest.headers = originalRequest.headers || {}
          originalRequest.headers['Authorization'] = `Bearer ${t.accessToken}`
          // retry original once
          return api(originalRequest)
        }
      } catch (refreshErr: any) {
        triggerAuthFail()
        throw refreshErr
      }
    }

    // non 401 or already retried: propagate
    const msg = (error.response?.data as any)?.message || error.message || '网络错误'
    const err = new Error(msg) as any
    err.original = error
    err.status = status
    err.code = (error.response?.data as any)?.code
    throw err
  }
)

export default api

// ===== Probe (existing) =====
export async function probeLeaderboard(): Promise<Result> {
  return api.get('/leaderboard/api/leaderboard', {
    params: { type: 'overall', size: 2 }
  })
}

// ===== Record Console API wrappers (no userId in body for auth sessions) =====
export async function submitRecord(dto: RecordSubmitDTO): Promise<Result<RecordSubmitResultDTO>> {
  return api.post('/record/api/records', dto)
}

export async function getVerifyResult(recordId: number | string): Promise<Result<VerificationResultDTO>> {
  return api.get(`/record/api/records/${recordId}/verify-result`)
}

export async function submitAppeal(recordId: number | string, reason: string): Promise<Result<any>> {
  return api.post(`/record/api/records/${recordId}/appeal`, { recordId: Number(recordId), reason })
}

export async function likeRecord(recordId: number | string): Promise<Result<LikeDTO>> {
  return api.post(`/record/api/records/${recordId}/like`, {})
}

export async function unlikeRecord(recordId: number | string): Promise<Result<LikeDTO>> {
  return api.delete(`/record/api/records/${recordId}/like`)
}

export async function getRecordLike(recordId: number | string): Promise<Result<LikeDTO>> {
  return api.get(`/record/api/records/${recordId}/like`)
}

export async function getRecordPoints(recordId: number | string, page = 1, size = 5): Promise<Result<PageResult<TrackPointDTO>>> {
  return api.get(`/record/api/records/${recordId}/points`, { params: { page, size } })
}

/** 删除运动记录（DELETE /record/api/records/{id}，TASK-223）；auth 会话下 caller 由网关注入。 */
export async function delRecord(recordId: number | string): Promise<Result<void>> {
  return api.delete(`/record/api/records/${recordId}`)
}

// ===== My-records (add-personal-stats TASK-211) =====
export interface RecordSummaryDTO {
  id: number
  sportType: number
  distance: number
  status: number
  // 可见性（0 公开，1 好友可见，2 仅自己）；前端据此渲染列表徽标（TASK-221）
  visibility?: number
  // 落库时间；列表/统计按此字段聚合（近30天每日里程、最近记录排序）
  createdAt?: string
}

export interface MyRecordsFilterParams {
  sportType?: number
  status?: number
  startDate?: string
  endDate?: string
}

/** 我的记录分页（GET /record/api/records/my）；auth 会话下 userId 由网关注入，前端不传。支持可选过滤参数（TASK-224）。 */
export async function getMyRecords(
  page = 1,
  size = 20,
  filters?: MyRecordsFilterParams,
): Promise<Result<PageResult<RecordSummaryDTO>>> {
  const params: Record<string, unknown> = { page, size }
  if (filters?.sportType != null) {
    params.sportType = filters.sportType
  }
  if (filters?.status != null) {
    params.status = filters.status
  }
  if (filters?.startDate) {
    params.startDate = filters.startDate
  }
  if (filters?.endDate) {
    params.endDate = filters.endDate
  }
  return api.get('/record/api/records/my', { params })
}

// ===== Friend activity feed (TASK-222 add-friend-feed) =====
// FeedItemDTO = RecordSummaryDTO 全字段 + authorUserId + authorNickname；后端按当页 distinct
// author 批量补齐昵称，authorNickname 缺失（user-service 不可用时）前端回退 authorUserId 字符串。
export interface FeedItemDTO extends RecordSummaryDTO {
  authorUserId: number
  authorNickname?: string
}

/**
 * 好友动态 feed（GET /record/api/records/feed）；auth 会话下 caller 由网关注入，前端不传。
 * 页码 1 起，size 1..500（与 /my 同口径）。auth 关闭降级态为 PUBLIC 全站时间线。
 */
export async function getFeed(page = 1, size = 20): Promise<Result<PageResult<FeedItemDTO>>> {
  return api.get('/record/api/records/feed', { params: { page, size } })
}

// ===== My streak (TASK-220) =====
export interface MyStreakDTO {
  current: number
  longest: number
}

/** 连续打卡统计（GET /record/api/records/my/streak）：仅计入通过终态（PASSED/RE_PASSED），同日多条算一天；auth 会话下 userId 由网关注入。 */
export async function getMyStreak(): Promise<Result<MyStreakDTO>> {
  return api.get('/record/api/records/my/streak')
}

// Friends
export async function createFriendRequest(targetUserId: number): Promise<Result<FriendRequestDTO>> {
  return api.post('/user/api/friends/requests', { targetUserId })
}

export async function acceptFriendRequest(id: number): Promise<Result<FriendRequestDTO>> {
  return api.post(`/user/api/friends/requests/${id}/accept`)
}

export async function rejectFriendRequest(id: number): Promise<Result<FriendRequestDTO>> {
  return api.post(`/user/api/friends/requests/${id}/reject`)
}

export async function listMyFriends(page = 1, size = 20): Promise<Result<PageResult<FriendDTO>>> {
  return api.get('/user/api/friends', { params: { page, size } })
}

// Leaderboard
export async function getLeaderboard(type: 'overall' | 'friend', size = 10): Promise<Result<LeaderboardDTO[]>> {
  return api.get('/leaderboard/api/leaderboard', { params: { type, size } })
}

// Date-range leaderboard (TASK-209): natural-period start assembled by caller, end defaults to today
export async function getLeaderboardRange(start: string, size = 10, end?: string): Promise<Result<LeaderboardDTO[]>> {
  return api.get('/leaderboard/api/leaderboard/range', {
    params: end ? { start, end, size } : { start, size }
  })
}

export const SAMPLE_TRACK_POINTS: TrackPointDTO[] = [
  {"seq":0,"lat":31.2453307,"lng":121.4576321,"ts":1700000000000},
  {"seq":1,"lat":31.2440070,"lng":121.4580362,"ts":1700000015000},
  {"seq":2,"lat":31.2407742,"lng":121.4593788,"ts":1700000030000},
  {"seq":3,"lat":31.2388112,"lng":121.4603849,"ts":1700000045000},
  {"seq":4,"lat":31.2368527,"lng":121.4615065,"ts":1700000060000},
  {"seq":5,"lat":31.2363787,"lng":121.4616759,"ts":1700000075000},
  {"seq":6,"lat":31.2351912,"lng":121.4619259,"ts":1700000090000},
  {"seq":7,"lat":31.2339695,"lng":121.4621549,"ts":1700000105000},
  {"seq":8,"lat":31.2332522,"lng":121.4623037,"ts":1700000120000},
  {"seq":9,"lat":31.2327292,"lng":121.4623708,"ts":1700000135000}
]

// ===== Admin Console (add-web-admin-console) =====
export async function createRuleVersion(request: { version?: string; grayRatio?: number; rules?: any }): Promise<Result<any>> {
  return api.post('/verify/rules/versions', request)
}

export async function updateRuleGray(id: number | string, grayRatio: number): Promise<Result<any>> {
  return api.patch(`/verify/rules/versions/${id}/gray`, { grayRatio })
}

export async function activateRuleVersion(id: number | string): Promise<Result<any>> {
  return api.post(`/verify/rules/versions/${id}/activate`)
}

export async function reviewAppeal(id: number | string, dto: { operator: string; pass: boolean; recheckResult?: string }): Promise<Result<any>> {
  return api.post(`/admin/api/appeals/${id}/review`, dto)
}

// ===== Notifications (add-notification-web-bell) — userId omitted when auth (gateway injects) =====
export interface NotificationViewDTO {
  id: number
  type: string // RECORD_VERIFIED | RECORD_REJECTED | FRIEND_ACCEPTED
  sourceId?: number
  title: string
  content: string
  isRead: number // 0 unread, 1 read
  createdAt?: string
  readAt?: string
}

export async function listNotifications(page = 1, size = 20): Promise<Result<PageResult<NotificationViewDTO>>> {
  return api.get('/user/api/notifications', { params: { page, size } })
}

export async function getUnreadCount(): Promise<Result<number>> {
  return api.get('/user/api/notifications/unread-count')
}

export async function markNotificationRead(id: number): Promise<Result<any>> {
  return api.patch(`/user/api/notifications/${id}/read`)
}

export async function markAllNotificationsRead(): Promise<Result<number>> {
  return api.patch('/user/api/notifications/read-all')
}

// ===== Notification Preferences (add-notification-preference) — userId omitted when auth (gateway injects) =====
export interface NotificationPreferenceView {
  type: string
  enabled: boolean
  updatedAt?: string
}

export interface UpdateNotificationPreferenceRequest {
  preferences: {
    type: string
    enabled: boolean
  }[]
}

export async function getNotificationPreferences(): Promise<Result<NotificationPreferenceView[]>> {
  return api.get('/user/api/notifications/preferences')
}

export async function updateNotificationPreferences(
  prefs: UpdateNotificationPreferenceRequest | { type: string; enabled: boolean }[]
): Promise<Result<NotificationPreferenceView[]>> {
  const body = Array.isArray(prefs) ? { preferences: prefs } : prefs
  return api.put('/user/api/notifications/preferences', body)
}

// ===== Auth: self-service password change (add-change-password TASK-208) =====
export interface ChangePasswordDTO {
  oldPassword: string
  newPassword: string
}

/** 自助改密：校验旧密码 → 新哈希落库 → 全量吊销该用户 refresh（X-User-Id 由网关注入，body 不含 userId）。 */
export async function changePassword(dto: ChangePasswordDTO): Promise<Result> {
  return api.post('/api/auth/change-password', dto)
}
