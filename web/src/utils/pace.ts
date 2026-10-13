import type { TrackPointDTO } from '@/api/client'

export interface PacePoint {
  distKm: number
  paceSec: number | null
  speed: number | null
}

/**
 * haversine 球面距离（R=6371km），返回单位 km。
 * 私有辅助：仅 computePaceSeries 使用，不导出。
 */
function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

/**
 * 由轨迹点序列计算配速序列（纯前端，零依赖）：
 * - 首点 distKm=0，其后逐段 haversine 累加（R=6371km，单调非递减）；
 * - paceSec = (speed != null && speed > 0) ? 1000/speed : null（speed 缺失/为 0 记停顿点）；
 * - 保持原始顺序；空数组 → []。
 */
export function computePaceSeries(points: TrackPointDTO[]): PacePoint[] {
  if (!points.length) return []
  const out: PacePoint[] = []
  let distKm = 0
  for (let i = 0; i < points.length; i++) {
    if (i > 0) {
      distKm += haversineKm(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
    }
    const rawSpeed = points[i].speed
    const speed = rawSpeed === undefined || rawSpeed === null ? null : rawSpeed
    const paceSec = speed !== null && speed > 0 ? 1000 / speed : null
    out.push({ distKm, paceSec, speed })
  }
  return out
}

/**
 * 配速秒/公里 → "m'ss\""（如 500 → 8'20"、45 → 0'45"、3659 → 60'59"）；null → '--'。
 * 先整体取整再拆 m/s，避免取余进位产生 60 秒。
 */
export function formatPace(paceSec: number | null): string {
  if (paceSec === null) return '--'
  const totalSec = Math.round(paceSec)
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  return `${m}'${String(s).padStart(2, '0')}"`
}

/**
 * 停顿断线规则（页面侧绘图 clamp 的单元测试出口）：
 * null / 0 → false；> 3600（>= 60 分/公里）→ false；其余（正数且 <= 3600）→ true。
 */
export function isDrawablePace(paceSec: number | null): boolean {
  return paceSec !== null && paceSec > 0 && paceSec <= 3600
}
