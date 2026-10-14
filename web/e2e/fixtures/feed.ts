/**
 * 好友动态 feed 夹具（TASK-227 route-mock）：22 条（第 1 页 20 条 / 第 2 页 2 条）。
 * createdAt 秒级严格递减 → 前端倒序兜底排序后第 1 页 = index 0..19、第 2 页 = 20..21。
 */
export interface FeedItem {
  id: number
  authorUserId: number
  authorNickname?: string
  sportType: number
  distance: number
  status: number
  visibility: number
  createdAt: string
}

export function createFeedStore(): { items: FeedItem[] } {
  const items: FeedItem[] = Array.from({ length: 22 }, (_, i) => ({
    id: 2000 + i,
    authorUserId: 7,
    authorNickname: `跑友${i}`,
    sportType: (i % 3) + 1,
    distance: 4 + (i % 9),
    status: i % 2 === 0 ? 2 : 5,
    visibility: i % 4 === 0 ? 2 : i % 4 === 1 ? 1 : 0,
    createdAt: `2026-10-10T08:00:${String(59 - i).padStart(2, '0')}`,
  }))
  return { items }
}
