import { describe, expect, it } from 'vitest'
import { computePaceSeries, formatPace, isDrawablePace } from '@/utils/pace'
import type { TrackPointDTO } from '@/api/client'

function pt(seq: number, lat: number, lng: number, ts: number, speed?: number): TrackPointDTO {
  return speed === undefined ? { seq, lat, lng, ts } : { seq, lat, lng, ts, speed }
}

describe('pace.ts 锚点（TASK-219）', () => {
  // R2a：已知短段 haversine 距离（纯纬度 0.001° ≈ 0.1112 km），容差断言；首点 distKm=0、单调非递减
  it('R2a 首点 0、短段距离容差、单调非递减', () => {
    const points = [
      pt(1, 31.230416, 121.473701, 1700000000000, 4),
      pt(2, 31.231416, 121.473701, 1700000001000, 4),
      pt(3, 31.232416, 121.473701, 1700000002000, 4),
    ]
    const series = computePaceSeries(points)
    expect(series[0].distKm).toBe(0)
    expect(series[1].distKm).toBeCloseTo(0.1112, 3) // |diff| <= 0.0005
    expect(series[2].distKm).toBeCloseTo(0.2224, 3)
    // 单调非递减
    for (let i = 1; i < series.length; i++) {
      expect(series[i].distKm).toBeGreaterThanOrEqual(series[i - 1].distKm)
    }
  })

  // R2b：pace 换算 speed=2 → 500、speed=4 → 250（1000/speed 精确）
  it('R2b pace 换算 1000/speed 精确', () => {
    const series = computePaceSeries([
      pt(1, 31.230416, 121.473701, 0, 2),
      pt(2, 31.231416, 121.473701, 1000, 4),
    ])
    expect(series[0].paceSec).toBe(500)
    expect(series[1].paceSec).toBe(250)
  })

  // R2c：speed=0 / 缺失 → paceSec null（停顿点），speed 原样保留
  it('R2c speed 为 0 或缺省 → paceSec null', () => {
    const series = computePaceSeries([
      pt(1, 31.230416, 121.473701, 0, 0),
      pt(2, 31.231416, 121.473701, 1000),
    ])
    expect(series[0].paceSec).toBeNull()
    expect(series[0].speed).toBe(0)
    expect(series[1].paceSec).toBeNull()
    expect(series[1].speed).toBeNull()
  })

  // R2d：formatPace 边界（null→'--'、45→0'45"、500→8'20"、3659→60'59"）
  it('R2d formatPace 边界四例', () => {
    expect(formatPace(null)).toBe('--')
    expect(formatPace(45)).toBe(`0'45"`)
    expect(formatPace(500)).toBe(`8'20"`)
    expect(formatPace(3659)).toBe(`60'59"`)
  })

  // R2e：空数组 → []
  it('R2e 空数组返回空序列', () => {
    const series = computePaceSeries([])
    expect(series).toEqual([])
    expect(series).toHaveLength(0)
  })

  // R2f：isDrawablePace（null false / 0 false / 600 true / 3601 false）
  it('R2f 停顿断线规则', () => {
    expect(isDrawablePace(null)).toBe(false)
    expect(isDrawablePace(0)).toBe(false)
    expect(isDrawablePace(600)).toBe(true)
    expect(isDrawablePace(3601)).toBe(false)
  })
})
