import type { TrackPointDTO } from '@/api/client'

/**
 * 轨迹动态回放插值计算（纯前端，TASK-274 Track Player）：
 * 归一化进度 progress ∈ [0,1] 沿「累计里程」归一（非点序），在相邻两点间做
 * 线性插值求当前经纬度/瞬时速度/时间戳，供详情页回放器平滑循迹渲染。
 */

/** 回放插值结果：插值经纬度 + 时刻 + 瞬时速度（m/s，无速度数据为 null） */
export interface PlaybackPoint {
  lat: number
  lng: number
  ts: number
  speed: number | null
}

/** haversine 球面距离（米，R=6371km，与 pace.ts/splits.ts 同公式同常数口径） */
function haversineMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6_371_000
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

/**
 * 归一化进度取点：progress ∈ [0,1]（越界自动钳制），0=起点、1=终点。
 * - 空数组 → null；单点 → 该点原样返回；
 * - 多点：按相邻点 haversine 累计里程定位目标段，段内按距离比例线性插值
 *   lat/lng/ts（速度取右端点瞬时速度，缺失回退左端点，均缺失为 null）；
 * - 全程退化（累计里程为 0，如原点打转）：按点序比例取整点。
 */
export function getPointAtProgress(points: TrackPointDTO[], progress: number): PlaybackPoint | null {
  if (!points.length) return null
  const p = Math.min(1, Math.max(0, progress))
  if (points.length === 1) {
    return { lat: points[0].lat, lng: points[0].lng, ts: points[0].ts, speed: points[0].speed ?? null }
  }
  const cum: number[] = [0]
  for (let i = 1; i < points.length; i++) {
    cum.push(cum[i - 1] + haversineMeters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng))
  }
  const total = cum[cum.length - 1]
  if (total <= 0) {
    // 退化：全部同坐标，按点序取整点（无插值可言）
    const idx = Math.min(points.length - 1, Math.round(p * (points.length - 1)))
    return { lat: points[idx].lat, lng: points[idx].lng, ts: points[idx].ts, speed: points[idx].speed ?? null }
  }
  const target = p * total
  let i = 1
  while (i < cum.length - 1 && cum[i] < target) i++
  const segStart = cum[i - 1]
  const segLen = cum[i] - segStart
  const t = segLen > 0 ? (target - segStart) / segLen : 0
  const a = points[i - 1]
  const b = points[i]
  // 速度取右端点（缺失回退左端点）；恰在左端点（t=0）时取左端点自身
  const speed = t === 0
    ? (a.speed ?? b.speed ?? null)
    : (b.speed ?? a.speed ?? null)
  return {
    lat: a.lat + (b.lat - a.lat) * t,
    lng: a.lng + (b.lng - a.lng) * t,
    ts: Math.round(a.ts + (b.ts - a.ts) * t),
    speed,
  }
}

/**
 * 瞬时速度（m/s）→ 配速格式化 "m'ss"" /km（如 3.65 → 4'34"）；
 * null/undefined/非正数 → '--'（停顿口径与 pace.ts 一致）。
 */
export function formatInstantPace(speedMps: number | null | undefined): string {
  if (speedMps == null || !(speedMps > 0)) return '--'
  const paceSec = 1000 / speedMps
  const totalSec = Math.round(paceSec)
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  return `${m}'${String(s).padStart(2, '0')}"`
}

/**
 * 回放时刻格式化：基准时刻 + 已播放毫秒 → HH:mm:ss 墙钟串
 * （ts 为 UTC 纪元毫秒，按本地时区渲染；无效输入回退 '--'）。
 */
export function formatPlaybackClock(baseTs: number, elapsedMs: number): string {
  if (!Number.isFinite(baseTs) || !Number.isFinite(elapsedMs)) return '--'
  const d = new Date(baseTs + elapsedMs)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}
