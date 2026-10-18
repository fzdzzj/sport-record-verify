import type { KilometerSplitDTO, RecordSplitsDTO, TrackPointDTO } from '@/api/client'
import { formatPace } from '@/utils/pace'

/**
 * 公里分段配速切片（纯前端，TASK-273）：与后端 SportRecordService.getRecordSplits 同一算法口径——
 * Haversine（R=6371000m）逐点累计路程，跨 1000m, 2000m... 阈值时在跨线相邻两点间按距离比例
 * 线性插值求穿越时刻（ts_cross = ts_{i-1} + (ts_i - ts_{i-1}) × (k·1000 − dist_{i-1}) / (dist_i − dist_{i-1})），
 * 逐公里切出耗时/配速/均速；末尾余程 ≥10m 独立成段；满 1000m 分段中标记最快/最慢公里。
 *
 * <p>与后端的两点差异（GPX 本地预览增强）：轨迹点携带 {@code ele} 时计算分段与全程
 * 累计爬升/下降（hasElevationData=true），相邻点缺 ele 的高差按跳过处理。</p>
 *
 * <p>用途：本地拖拽 GPX 预览切片计算，以及后端 /splits 接口任一环节失败时的无缝兜底。</p>
 *
 * @param points 轨迹点（按 seq 升序；空数组/单点 → 空 splits）
 * @param recordId 记录 id（本地预览无记录时缺省 0）
 */
export function computeKilometerSplits(points: TrackPointDTO[], recordId = 0): RecordSplitsDTO {
  const empty: RecordSplitsDTO = {
    recordId,
    totalDistanceMeters: 0,
    totalElapsedSeconds: 0,
    splits: [],
    fastestKm: null,
    slowestKm: null,
    totalElevationGain: 0,
    totalElevationLoss: 0,
    hasElevationData: false,
  }
  if (!points.length) return empty

  const n = points.length
  const hasElevationData = points.some((p) => eleAt(p) !== null)

  // 累计路程：dist[0]=0，逐段 Haversine 累加（单调非递减）
  const dist = new Array<number>(n).fill(0)
  for (let i = 1; i < n; i++) {
    dist[i] = dist[i - 1] + haversineMeters(points[i - 1], points[i])
  }
  const totalMeters = dist[n - 1]

  // 分段高差（逐相邻点对）：缺 ele 的一对跳过；段归属按段起点所在公里（末尾归余程段）
  const gainBySplit = new Map<number, number>()
  const lossBySplit = new Map<number, number>()
  const fullCount = Math.floor(totalMeters / 1000)
  for (let j = 1; j < n; j++) {
    const a = eleAt(points[j - 1])
    const b = eleAt(points[j])
    if (a === null || b === null) continue
    const d = b - a
    if (d === 0) continue
    const owner = Math.min(Math.floor(dist[j - 1] / 1000) + 1, fullCount + 1)
    const map = d > 0 ? gainBySplit : lossBySplit
    map.set(owner, (map.get(owner) || 0) + Math.abs(d))
  }

  // 逐阈值切片：i 为跨线段右端点游标，跨线相邻两点间线性插值越线时刻
  const splits: KilometerSplitDTO[] = []
  let prevCrossTs = points[0].ts
  let i = 1
  let k = 1
  while (k * 1000 <= totalMeters) {
    const threshold = k * 1000
    while (i < n && dist[i] < threshold) {
      i++
    }
    const crossTs = crossTimestamp(points, dist, i, threshold)
    splits.push(newSplit(k, 1000, crossTs - prevCrossTs, gainBySplit, lossBySplit))
    prevCrossTs = crossTs
    k++
  }
  const remainder = totalMeters - (k - 1) * 1000
  if (remainder >= 10) {
    splits.push(
      newSplit(k, remainder, points[n - 1].ts - prevCrossTs, gainBySplit, lossBySplit),
    )
  }
  markFastestSlowest(splits, fullCount)

  return {
    recordId,
    totalDistanceMeters: round2(totalMeters),
    totalElapsedSeconds: Math.round((points[n - 1].ts - points[0].ts) / 1000),
    splits,
    fastestKm: fullCount > 0 ? fastestKmOf(splits) : null,
    slowestKm: fullCount > 0 ? slowestKmOf(splits) : null,
    totalElevationGain: round2(sumSplits(splits, (s) => s.elevationGain)),
    totalElevationLoss: round2(sumSplits(splits, (s) => s.elevationLoss)),
    hasElevationData,
  }
}

/** 有效海拔（缺失/非有限数值 → null）。 */
function eleAt(p: TrackPointDTO): number | null {
  return p.ele != null && Number.isFinite(p.ele) ? p.ele : null
}

/** 两点球面距离（米，Haversine；R=6371000，与后端推导同公式同常数）。 */
function haversineMeters(a: TrackPointDTO, b: TrackPointDTO): number {
  const R = 6371000
  const rad = (x: number) => (x * Math.PI) / 180
  const dLat = rad(b.lat - a.lat)
  const dLng = rad(b.lng - a.lng)
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}

/** 跨线时刻线性插值：第 i 段（dist[i-1] < 阈值 ≤ dist[i]）内按距离比例求穿越时刻；段长退化防除零。 */
function crossTimestamp(points: TrackPointDTO[], dist: number[], i: number, threshold: number): number {
  const segLen = dist[i] - dist[i - 1]
  const tsPrev = points[i - 1].ts
  const tsCur = points[i].ts
  if (segLen <= 1e-9) {
    return tsCur
  }
  return Math.round(tsPrev + (tsCur - tsPrev) * ((threshold - dist[i - 1]) / segLen))
}

/** 单段装配：耗时（秒）、配速格式化（非正耗时回退 --）、均速（m/s 两位小数，零耗时 0.0）与分段高差。 */
function newSplit(
  km: number,
  distMeters: number,
  elapsedMs: number,
  gainBySplit: Map<number, number>,
  lossBySplit: Map<number, number>,
): KilometerSplitDTO {
  return {
    km,
    distanceMeters: round2(distMeters),
    elapsedSeconds: Math.round(elapsedMs / 1000),
    paceFormatted: elapsedMs > 0 && distMeters > 0
      ? formatPace((elapsedMs / 1000) * (1000 / distMeters))
      : '--',
    avgSpeed: elapsedMs > 0 ? round2(distMeters / (elapsedMs / 1000)) : 0,
    isFastest: false,
    isSlowest: false,
    elevationGain: round2(gainBySplit.get(km) || 0),
    elevationLoss: round2(lossBySplit.get(km) || 0),
  }
}

/** 满 1000m 分段内标记最快/最慢公里（并列取先出现者）；余程段不参与标记。 */
function markFastestSlowest(splits: KilometerSplitDTO[], fullCount: number): void {
  if (fullCount <= 0) return
  const fastest = fastestKmOf(splits)
  const slowest = slowestKmOf(splits)
  for (const s of splits) {
    if (s.km <= fullCount) {
      s.isFastest = s.km === fastest
      s.isSlowest = s.km === slowest
    }
  }
}

/** 最快满公里序号（耗时最短，并列取先出现者）；无满公里 → null。 */
function fastestKmOf(splits: KilometerSplitDTO[]): number | null {
  let best: KilometerSplitDTO | null = null
  for (const s of splits) {
    if (s.distanceMeters < 1000) break // 余程段及其后不参与
    if (best === null || s.elapsedSeconds < best.elapsedSeconds) best = s
  }
  return best ? best.km : null
}

/** 最慢满公里序号（耗时最长，并列取先出现者）；无满公里 → null。 */
function slowestKmOf(splits: KilometerSplitDTO[]): number | null {
  let worst: KilometerSplitDTO | null = null
  for (const s of splits) {
    if (s.distanceMeters < 1000) break // 余程段及其后不参与
    if (worst === null || s.elapsedSeconds > worst.elapsedSeconds) worst = s
  }
  return worst ? worst.km : null
}

function sumSplits(splits: KilometerSplitDTO[], pick: (s: KilometerSplitDTO) => number): number {
  return splits.reduce((acc, s) => acc + pick(s), 0)
}

function round2(v: number): number {
  return Math.round(v * 100) / 100
}
