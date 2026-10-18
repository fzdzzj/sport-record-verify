import { describe, expect, it } from 'vitest'
import { formatInstantPace, formatPlaybackClock, getPointAtProgress } from '@/utils/track-playback'
import type { TrackPointDTO } from '@/api/client'

const BASE_TS = 1_700_000_000_000

/** 同经线（lng=0）北向布点：段长有解析解（米），插值断言值确定。 */
function pt(seq: number, lat: number, ts: number, speed?: number): TrackPointDTO {
  return speed === undefined ? { seq, lat, lng: 0, ts } : { seq, lat, lng: 0, ts, speed }
}

/** 同经线北向移动 meters 米后的纬度（Haversine 沿经线解析可算）。 */
function latOffset(meters: number): number {
  return (meters / 6_371_000) * (180 / Math.PI)
}

describe('track-playback.ts getPointAtProgress（TASK-274）', () => {
  it('R1 空数组安全返回 null', () => {
    expect(getPointAtProgress([], 0)).toBeNull()
    expect(getPointAtProgress([], 0.5)).toBeNull()
    expect(getPointAtProgress([], 1)).toBeNull()
  })

  it('R2 单点原样返回（任意进度）', () => {
    const single = [pt(0, 31.23, BASE_TS, 3)]
    expect(getPointAtProgress(single, 0)).toEqual({ lat: 31.23, lng: 0, ts: BASE_TS, speed: 3 })
    expect(getPointAtProgress(single, 0.7)).toEqual({ lat: 31.23, lng: 0, ts: BASE_TS, speed: 3 })
  })

  it('R3 进度 0/1 边界：起点与终点原样命中', () => {
    const pts = [pt(0, 0, BASE_TS, 2), pt(1, latOffset(1000), BASE_TS + 600_000, 4)]
    expect(getPointAtProgress(pts, 0)).toEqual({ lat: 0, lng: 0, ts: BASE_TS, speed: 2 })
    expect(getPointAtProgress(pts, 1)).toEqual({
      lat: latOffset(1000),
      lng: 0,
      ts: BASE_TS + 600_000,
      speed: 4,
    })
  })

  it('R4 越界进度钳制到 [0,1]', () => {
    const pts = [pt(0, 0, BASE_TS), pt(1, latOffset(500), BASE_TS + 100_000)]
    expect(getPointAtProgress(pts, -1)?.lat).toBe(0)
    expect(getPointAtProgress(pts, 2)?.lat).toBeCloseTo(latOffset(500), 12)
  })

  it('R5 两点直线中点插值：经纬度/时间戳按里程比例线性内插', () => {
    // 两点相距 1000m：progress=0.5 → 中点（250m 处为 0.25）
    const pts = [pt(0, 0, BASE_TS), pt(1, latOffset(1000), BASE_TS + 600_000)]
    const mid = getPointAtProgress(pts, 0.5)
    expect(mid?.lat).toBeCloseTo(latOffset(500), 12)
    expect(mid?.ts).toBe(BASE_TS + 300_000)
    const quarter = getPointAtProgress(pts, 0.25)
    expect(quarter?.lat).toBeCloseTo(latOffset(250), 12)
    expect(quarter?.ts).toBe(BASE_TS + 150_000)
  })

  it('R6 跨点插值：不等长三段按累计里程归一（非点序均分）', () => {
    // 段长 100m / 300m / 600m，总 1000m；progress=0.2 → 目标 200m，
    // 落在第 2 段（100~400m）1/3 处（段内 100m），绝对里程 200m
    const pts = [
      pt(0, 0, BASE_TS),
      pt(1, latOffset(100), BASE_TS + 60_000, 2),
      pt(2, latOffset(400), BASE_TS + 180_000, 4),
      pt(3, latOffset(1000), BASE_TS + 780_000, 6),
    ]
    const at = getPointAtProgress(pts, 0.2)
    expect(at?.lat).toBeCloseTo(latOffset(200), 9)
    // 第 2 段 60s~180s 的 1/3 → 100s
    expect(at?.ts).toBe(BASE_TS + 100_000)
    // 速度取右端点
    expect(at?.speed).toBe(4)
  })

  it('R7 全程退化（累计里程 0）：按点序比例取整点', () => {
    const pts = [pt(0, 31.23, BASE_TS), pt(1, 31.23, BASE_TS + 1000), pt(2, 31.23, BASE_TS + 2000)]
    expect(getPointAtProgress(pts, 0)?.ts).toBe(BASE_TS)
    expect(getPointAtProgress(pts, 1)?.ts).toBe(BASE_TS + 2000)
    // 0.4 × 2 = 0.8 → round → 1
    expect(getPointAtProgress(pts, 0.4)?.ts).toBe(BASE_TS + 1000)
  })

  it('R8 速度回退：右端点缺失回退左端点，均缺失为 null', () => {
    const pts = [pt(0, 0, BASE_TS), pt(1, latOffset(100), BASE_TS + 60_000)]
    expect(getPointAtProgress(pts, 0.5)?.speed).toBeNull()
    const withLeft = [pt(0, 0, BASE_TS, 3), pt(1, latOffset(100), BASE_TS + 60_000)]
    expect(getPointAtProgress(withLeft, 0.5)?.speed).toBe(3)
  })
})

describe('track-playback.ts 辅助格式化（TASK-274）', () => {
  it('formatInstantPace：m/s → 配速 m\'ss"，非正/缺失回退 --', () => {
    expect(formatInstantPace(1000 / 300)).toBe("5'00\"")
    expect(formatInstantPace(1000 / 275.4)).toBe("4'35\"")
    expect(formatInstantPace(0)).toBe('--')
    expect(formatInstantPace(null)).toBe('--')
    expect(formatInstantPace(undefined)).toBe('--')
  })

  it('formatPlaybackClock：基准 ts + 已播放毫秒 → 本地墙钟 HH:mm:ss，无效回退 --', () => {
    // 期望值由 Date 对象同帧推导，避免 CI 时区漂移
    const wall = (ts: number) => {
      const d = new Date(ts)
      return (
        `${String(d.getHours()).padStart(2, '0')}:` +
        `${String(d.getMinutes()).padStart(2, '0')}:` +
        `${String(d.getSeconds()).padStart(2, '0')}`
      )
    }
    expect(formatPlaybackClock(BASE_TS, 0)).toBe(wall(BASE_TS))
    expect(formatPlaybackClock(BASE_TS, 90_000)).toBe(wall(BASE_TS + 90_000))
    expect(formatPlaybackClock(Number.NaN, 0)).toBe('--')
  })
})
