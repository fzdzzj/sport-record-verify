import axios, { AxiosError, AxiosInstance } from 'axios'
import { getAccessToken } from '@/utils/token'

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

// Response interceptor: code===0 success else throw message
api.interceptors.response.use(
  (response) => {
    const body = response.data as Result
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body  // or return body.data if want, but keep for probe to show full
      } else {
        const err = new Error(body.message || '请求失败')
        ;(err as any).code = body.code
        ;(err as any).data = body.data
        throw err
      }
    }
    return response.data
  },
  (error: AxiosError) => {
    const msg = (error.response?.data as any)?.message || error.message || '网络错误'
    const err = new Error(msg)
    ;(err as any).original = error
    ;(err as any).status = error.response?.status
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
