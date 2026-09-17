import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { getAccessToken, getRefreshToken, setTokens, triggerAuthFail } from '@/utils/token'

export interface Result<T = any> {
  code: number
  message: string
  data: T
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

      const rt = getRefreshToken()
      if (!rt) {
        triggerAuthFail()
        const err = new Error('未登录或会话失效') as any
        err.status = 401
        throw err
      }

      originalRequest._retry = true
      try {
        const refreshRes = await api.post('/api/auth/refresh', { refreshToken: rt })
        const t = refreshRes.data
        if (t && t.accessToken) {
          setTokens(t.accessToken, t.refreshToken || rt, t.role || '')
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

// Example usage for leaderboard probe (public?)
// GET /leaderboard/api/leaderboard?type=overall&size=2
export async function probeLeaderboard() {
  return api.get<Result>('/leaderboard/api/leaderboard', {
    params: { type: 'overall', size: 2 }
  })
}
