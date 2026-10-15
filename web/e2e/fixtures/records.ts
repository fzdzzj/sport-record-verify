/**
 * 我的记录夹具（TASK-227 route-mock）：
 * 25 条记录（2 页 × size20），createdAt 秒级严格递减 → 前端 createdAt 降序兜底排序后页码内容确定：
 * 第 1 页 = id 1000..1019，第 2 页 = id 1020..1024。
 * 过滤：sportType / status / startDate / endDate 按查询参数 null-safe 过滤后分页（与后端语义同构）。
 * 状态分布（i%8）：status 2（通过）∈{2,10,18}、status 5（复审通过）∈{5,13} → 第 1 页 20 条中通过 5 条（25.0%）。
 */
export interface Rec {
  id: number
  sportType: number
  distance: number
  status: number
  visibility: number
  createdAt: string
}

export const PAGE_SIZE = 20

export function createRecordStore(): {
  records: Rec[]
  readonly lastQuery: URLSearchParams | null
  pageFiltered(page: number, size: number, q: URLSearchParams): { records: Rec[]; total: number }
  deleteById(id: number): boolean
  resetQuery(): void
} {
  const records: Rec[] = Array.from({ length: 25 }, (_, i) => ({
    id: 1000 + i,
    sportType: (i % 3) + 1,
    distance: 5 + (i % 7),
    status: i % 8,
    visibility: i % 5 === 0 ? 2 : i % 5 === 1 ? 1 : 0,
    createdAt: `2026-10-10T08:00:${String(59 - i).padStart(2, '0')}`,
  }))
  let lastQuery: URLSearchParams | null = null

  function filterByQuery(list: Rec[], q: URLSearchParams): Rec[] {
    let out = list
    const sportType = q.get('sportType')
    if (sportType) out = out.filter((r) => String(r.sportType) === sportType)
    const status = q.get('status')
    if (status) out = out.filter((r) => String(r.status) === status)
    const start = q.get('startDate')
    if (start) out = out.filter((r) => r.createdAt.slice(0, 10) >= start)
    const end = q.get('endDate')
    if (end) out = out.filter((r) => r.createdAt.slice(0, 10) <= end)
    return out
  }

  function pageFiltered(page: number, size: number, q: URLSearchParams): { records: Rec[]; total: number } {
    lastQuery = new URLSearchParams(q)
    const filtered = filterByQuery(records, q)
    const start = (page - 1) * size
    return { records: filtered.slice(start, start + size), total: filtered.length }
  }

  function deleteById(id: number): boolean {
    const idx = records.findIndex((r) => r.id === id)
    if (idx < 0) return false
    records.splice(idx, 1)
    return true
  }

  return {
    records,
    // getter：pageFiltered 内部重绑闭包变量，须经 getter 暴露最新一次查询（对象字面量属性是创建时快照）
    get lastQuery() {
      return lastQuery
    },
    pageFiltered,
    deleteById,
    resetQuery: () => (lastQuery = null),
  }
}

/** 第 1 页（20 条）总里程：5+(i%7) 对 i=0..19 求和 */
export function pageOneDistanceSum(): number {
  let sum = 0
  for (let i = 0; i < 20; i++) sum += 5 + (i % 7)
  return sum
}

/** 全量 25 条总里程：5+(i%7) 对 i=0..24 求和（对齐 /my/stats 服务端全量聚合口径，TASK-233） */
export function totalDistanceSum(): number {
  let sum = 0
  for (let i = 0; i < 25; i++) sum += 5 + (i % 7)
  return sum
}

/**
 * /my/stats 全量统计夹具（TASK-231 端点 / TASK-233 契约对齐）：
 * 25 条中 status=2|5（i%8 命中 {2,10,18}∨{5,13,21}）恰 6 条 → 通过率 6/25=24.0%；
 * dailyDistances 为以 fixtures 基准日（2026-10-10）为窗口尾的 30 键 YYYY-MM-DD Map，
 * 有记录日映射该日总里程、无记录日补 0，恒 30 键（与后端 30 天时间窗契约同构）。
 */
export function myStats(): {
  totalCount: number
  totalDistance: number
  passCount: number
  passRate: string
  dailyDistances: Record<string, number>
} {
  const passCount = recordsByStatusPassed()
  const daily = dailyDistancesMap()
  return {
    totalCount: 25,
    totalDistance: totalDistanceSum(),
    passCount,
    passRate: '24.0%',
    dailyDistances: daily,
  }
}

/** 通过记录数：status ∈ {2（通过）, 5（复审通过）}——25 条中恰 6 条（i%8 ∈ {2,10,18,5,13,21}） */
function recordsByStatusPassed(): number {
  let n = 0
  for (let i = 0; i < 25; i++) {
    const status = i % 8
    if (status === 2 || status === 5) n++
  }
  return n
}

/** 30 键每日里程：以 2026-10-10 为窗口尾生成 30 个自然日键，key 与夹具 createdAt 逐日对齐并累加该日里程。 */
function dailyDistancesMap(): Record<string, number> {
  const byDay: Record<string, number> = {}
  // 夹具记录集中在 2026-10-10（createdAt=2026-10-10T08:00:xx），映射到该 ISO 日键
  const base = '2026-10-10'
  for (let i = 29; i >= 0; i--) {
    const date = new Date(base + 'T00:00:00')
    date.setDate(date.getDate() - i)
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    byDay[key] = 0
  }
  // 25 条夹具全部归集到基准日（简化：单日聚合窗口内各记录里程），键保证存在
  byDay[base] = totalDistanceSum()
  return byDay
}
