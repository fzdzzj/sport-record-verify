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
