import { describe, expect, it } from 'vitest'
import { computeKilometerSplits } from '@/utils/splits'
import type { TrackPointDTO } from '@/api/client'

const BASE_TS = 1_700_000_000_000

/** 同经线（lng=0）北向布点：Haversine 沿经线解析可算，误差 < 1e-9 米，round2 后断言值确定。 */
function latOffset(meters: number): number {
  return (meters / 6_371_000) * (180 / Math.PI)
}

function pt(seq: number, lat: number, ts: number, ele?: number): TrackPointDTO {
  return ele === undefined ? { seq, lat, lng: 0, ts } : { seq, lat, lng: 0, ts, ele }
}

describe('splits.ts computeKilometerSplits（TASK-273）', () => {
  // R1：空数组 → 空 splits、总里程 0、无最快最慢、无海拔
  it('R1 空数组返回空切片全景', () => {
    const out = computeKilometerSplits([], 7)
    expect(out.recordId).toBe(7)
    expect(out.splits).toEqual([])
    expect(out.totalDistanceMeters).toBe(0)
    expect(out.fastestKm).toBeNull()
    expect(out.slowestKm).toBeNull()
    expect(out.totalElevationGain).toBe(0)
    expect(out.totalElevationLoss).toBe(0)
    expect(out.hasElevationData).toBe(false)
  })

  // R2：单点 → 累计 0 米，无阈值可跨、余程 0 不足 10m → 空 splits
  it('R2 单点无切片', () => {
    const out = computeKilometerSplits([pt(0, 31.23, BASE_TS)])
    expect(out.splits).toEqual([])
    expect(out.totalDistanceMeters).toBe(0)
    expect(out.totalElapsedSeconds).toBe(0)
    expect(out.fastestKm).toBeNull()
  })

  // R3：不足 1km（500m/100s）→ 仅一条末尾余程段，无满公里 → fastestKm/slowestKm 恒 null
  it('R3 不足 1km 仅余程段且无最快最慢', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(500), BASE_TS + 100_000),
    ])
    expect(out.totalDistanceMeters).toBe(500)
    expect(out.totalElapsedSeconds).toBe(100)
    expect(out.splits).toHaveLength(1)
    const tail = out.splits[0]
    expect(tail.km).toBe(1)
    expect(tail.distanceMeters).toBe(500)
    expect(tail.elapsedSeconds).toBe(100)
    expect(tail.paceFormatted).toBe("3'20\"")
    expect(tail.avgSpeed).toBe(5)
    expect(tail.isFastest).toBe(false)
    expect(tail.isSlowest).toBe(false)
    expect(out.fastestKm).toBeNull()
    expect(out.slowestKm).toBeNull()
  })

  // R4：跨 2km 线性插值（1500m/450s + 1400m/560s）+ 900m 余程；最快第 1 公里、最慢第 2 公里
  it('R4 跨公里阈值线性插值切片与余程段', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(1500), BASE_TS + 450_000),
      pt(2, latOffset(2900), BASE_TS + 1_010_000),
    ])
    expect(out.totalDistanceMeters).toBe(2900)
    expect(out.totalElapsedSeconds).toBe(1010)
    expect(out.splits).toHaveLength(3)

    const [km1, km2, tail] = out.splits
    expect(km1.km).toBe(1)
    expect(km1.distanceMeters).toBe(1000)
    expect(km1.elapsedSeconds).toBe(300)
    expect(km1.paceFormatted).toBe("5'00\"")
    expect(km1.avgSpeed).toBe(3.33)
    expect(km1.isFastest).toBe(true)
    expect(km1.isSlowest).toBe(false)

    expect(km2.km).toBe(2)
    expect(km2.elapsedSeconds).toBe(350)
    expect(km2.paceFormatted).toBe("5'50\"")
    expect(km2.avgSpeed).toBe(2.86)
    expect(km2.isFastest).toBe(false)
    expect(km2.isSlowest).toBe(true)

    expect(tail.km).toBe(3)
    expect(tail.distanceMeters).toBe(900)
    expect(tail.elapsedSeconds).toBe(360)
    expect(tail.paceFormatted).toBe("6'40\"")
    expect(tail.avgSpeed).toBe(2.5)
    expect(tail.isFastest).toBe(false)
    expect(tail.isSlowest).toBe(false)

    expect(out.fastestKm).toBe(1)
    expect(out.slowestKm).toBe(2)
  })

  // R5：3.5km 四段（整公里恰落在点位上）：300/360/330s + 500m 余程 200s，最快/最慢判定
  it('R5 多公里整点越线与最快最慢公里', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(1000), BASE_TS + 300_000),
      pt(2, latOffset(2000), BASE_TS + 660_000),
      pt(3, latOffset(3000), BASE_TS + 990_000),
      pt(4, latOffset(3500), BASE_TS + 1_190_000),
    ])
    expect(out.totalDistanceMeters).toBe(3500)
    expect(out.totalElapsedSeconds).toBe(1190)
    expect(out.splits).toHaveLength(4)
    expect(out.splits.map((s) => s.elapsedSeconds)).toEqual([300, 360, 330, 200])
    expect(out.splits.map((s) => s.paceFormatted)).toEqual(["5'00\"", "6'00\"", "5'30\"", "6'40\""])
    expect(out.splits[3].isFastest).toBe(false)
    expect(out.splits[3].isSlowest).toBe(false)
    expect(out.splits[0].isFastest).toBe(true)
    expect(out.splits[1].isSlowest).toBe(true)
    expect(out.fastestKm).toBe(1)
    expect(out.slowestKm).toBe(2)
  })

  // R6：余程 < 10m 不单列（1005m/2010s → 仅 1 条满公里段）
  it('R6 余程不足 10 米并入不列', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(1005), BASE_TS + 2_010_000),
    ])
    expect(out.totalDistanceMeters).toBe(1005)
    expect(out.splits).toHaveLength(1)
    expect(out.splits[0].km).toBe(1)
    expect(out.splits[0].elapsedSeconds).toBe(2000)
    expect(out.splits[0].paceFormatted).toBe("33'20\"")
    expect(out.splits[0].avgSpeed).toBe(0.5)
  })

  // R7：唯一满公里同领最快与最慢（1040m/500s → 481s + 40m 余程 19s）
  it('R7 唯一满公里同时标记最快与最慢', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(1040), BASE_TS + 500_000),
    ])
    expect(out.splits).toHaveLength(2)
    const [km1, tail] = out.splits
    expect(km1.elapsedSeconds).toBe(481)
    expect(km1.paceFormatted).toBe("8'01\"")
    expect(km1.isFastest).toBe(true)
    expect(km1.isSlowest).toBe(true)
    expect(tail.km).toBe(2)
    expect(tail.distanceMeters).toBe(40)
    expect(tail.elapsedSeconds).toBe(19)
    expect(tail.isFastest).toBe(false)
    expect(out.fastestKm).toBe(1)
    expect(out.slowestKm).toBe(1)
  })

  // R8：全程零耗时（1100m 两点同 ts）→ 配速回退 --、均速 0.0
  it('R8 零耗时配速回退 --', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(1100), BASE_TS),
    ])
    expect(out.splits).toHaveLength(2)
    const [km1, tail] = out.splits
    expect(km1.elapsedSeconds).toBe(0)
    expect(km1.paceFormatted).toBe('--')
    expect(km1.avgSpeed).toBe(0)
    expect(tail.paceFormatted).toBe('--')
    expect(tail.avgSpeed).toBe(0)
    expect(out.fastestKm).toBe(1)
    expect(out.slowestKm).toBe(1)
  })

  // R9：海拔统计（3 点含 ele：600m +50 / 600m −30 → 满公里段 gain50/loss30，余程段 0；总计 50/30）
  it('R9 含海拔时计算分段与全程累计爬升下降', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS, 100),
      pt(1, latOffset(600), BASE_TS + 300_000, 150),
      pt(2, latOffset(1200), BASE_TS + 600_000, 120),
    ])
    expect(out.hasElevationData).toBe(true)
    expect(out.totalElevationGain).toBe(50)
    expect(out.totalElevationLoss).toBe(30)
    const [km1, tail] = out.splits
    expect(km1.distanceMeters).toBe(1000)
    expect(km1.elevationGain).toBe(50)
    expect(km1.elevationLoss).toBe(30)
    expect(tail.elevationGain).toBe(0)
    expect(tail.elevationLoss).toBe(0)
  })

  // R10：缺 ele 的相邻点对高差跳过，但 hasElevationData 仍为 true
  it('R10 局部缺 ele 高差跳过但仍标记含海拔', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS, 100),
      pt(1, latOffset(600), BASE_TS + 300_000),
      pt(2, latOffset(1200), BASE_TS + 600_000, 130),
    ])
    expect(out.hasElevationData).toBe(true)
    expect(out.totalElevationGain).toBe(0)
    expect(out.totalElevationLoss).toBe(0)
  })

  // R11：无 ele → hasElevationData false，高差恒 0（与后端 track_point 无海拔列同形态）
  it('R11 无海拔数据时高差恒零', () => {
    const out = computeKilometerSplits([
      pt(0, 0, BASE_TS),
      pt(1, latOffset(1200), BASE_TS + 600_000),
    ])
    expect(out.hasElevationData).toBe(false)
    expect(out.totalElevationGain).toBe(0)
    expect(out.totalElevationLoss).toBe(0)
    expect(out.splits.every((s) => s.elevationGain === 0 && s.elevationLoss === 0)).toBe(true)
  })
})
