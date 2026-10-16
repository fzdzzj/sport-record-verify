/**
 * 通知域夹具（TASK-227 route-mock 引入；TASK-257 E14 状态化改造）：
 * createNotificationStore 沿 records store 先例做状态记忆——列表/未读数随写操作流转
 * （单条标已读/批量已读/全部已读使未读数收敛，清空已读移除全部已读行）。
 * 初始态：13 条（id 61..49，id 倒序即数组序），未读恰 3 条（id 55/50/49）→
 * 未读徽标初值 3（与原 UNREAD_COUNT=3 徽标夹具对齐，顶栏导航用例角标断言不受影响）；
 * 第 1 页（size=10，id 61..52）恰 1 条未读（55）→ 顶栏导航用例 strict 模式
 * getByText('未读', { exact: true }) 唯一匹配不破；第 2 页（id 51..49）2 条未读（50/49）。
 * 偏好 4 项全开（静态，读写偏好不改变行集）。
 */
export interface NotificationItem {
  id: number
  type: string
  sourceId?: number
  title: string
  content: string
  isRead: number
  createdAt: string
}

export interface NotificationPage {
  records: NotificationItem[]
  total: number
}

export interface NotificationStore {
  items: NotificationItem[]
  /** 未读数派生自列表（isRead===0 行数），随写操作实时流转 */
  readonly unreadCount: number
  page(page: number, size: number): NotificationPage
  /** 单条标已读：命中未读行置读返回 true（已读/不存在返回 false 不流转） */
  markRead(id: number): boolean
  /** 批量标已读：返回实际流转（命中未读）条数 */
  batchRead(ids: number[]): number
  /** 全部已读：置读全部未读行，返回流转条数 */
  markAllRead(): number
  /** 清空已读：移除全部已读行，返回删除条数 */
  clearRead(): number
}

export function createNotificationStore(): NotificationStore {
  const items: NotificationItem[] = [
    { id: 61, type: 'RECORD_VERIFIED', sourceId: 1006, title: '记录校验通过', content: '您的骑行记录已通过校验', isRead: 1, createdAt: '2026-10-14T10:00:00' },
    { id: 60, type: 'FRIEND_ACCEPTED', sourceId: 7, title: '好友申请已通过', content: '跑友1 已同意您的好友申请', isRead: 1, createdAt: '2026-10-14T09:55:00' },
    { id: 59, type: 'RECORD_REJECTED', sourceId: 1004, title: '记录校验未通过', content: '您的健走记录未通过校验', isRead: 1, createdAt: '2026-10-14T09:50:00' },
    { id: 58, type: 'RECORD_VERIFIED', sourceId: 1003, title: '记录校验通过', content: '您的跑步记录已通过校验', isRead: 1, createdAt: '2026-10-14T09:45:00' },
    { id: 57, type: 'RECORD_LIKED', sourceId: 1002, title: '收到点赞', content: '跑友2 赞了您的记录', isRead: 1, createdAt: '2026-10-14T09:40:00' },
    { id: 56, type: 'FRIEND_ACCEPTED', sourceId: 8, title: '好友申请已通过', content: '跑友3 已同意您的好友申请', isRead: 1, createdAt: '2026-10-14T09:35:00' },
    { id: 55, type: 'RECORD_VERIFIED', sourceId: 1000, title: '记录校验通过', content: '您的跑步记录已通过校验', isRead: 0, createdAt: '2026-10-14T09:00:00' },
    { id: 54, type: 'FRIEND_ACCEPTED', sourceId: 7, title: '好友申请已通过', content: '跑友1 已同意您的好友申请', isRead: 1, createdAt: '2026-10-14T08:55:00' },
    { id: 53, type: 'RECORD_REJECTED', sourceId: 1005, title: '记录校验未通过', content: '您的骑行记录未通过校验', isRead: 1, createdAt: '2026-10-14T08:50:00' },
    { id: 52, type: 'RECORD_VERIFIED', sourceId: 1002, title: '记录校验通过', content: '您的健走记录已通过校验', isRead: 1, createdAt: '2026-10-14T08:45:00' },
    { id: 51, type: 'FRIEND_ACCEPTED', sourceId: 7, title: '好友申请已通过', content: '跑友1 已同意您的好友申请', isRead: 1, createdAt: '2026-10-14T08:40:00' },
    { id: 50, type: 'RECORD_VERIFIED', sourceId: 1001, title: '记录校验通过', content: '您的骑行记录已通过校验', isRead: 0, createdAt: '2026-10-14T08:35:00' },
    { id: 49, type: 'FRIEND_ACCEPTED', sourceId: 9, title: '好友申请已通过', content: '跑友4 已同意您的好友申请', isRead: 0, createdAt: '2026-10-14T08:30:00' },
  ]

  function markRead(id: number): boolean {
    const row = items.find((n) => n.id === id)
    if (!row || row.isRead !== 0) return false
    row.isRead = 1
    return true
  }

  return {
    items,
    get unreadCount() {
      return items.filter((n) => n.isRead === 0).length
    },
    page(p, size) {
      const start = (p - 1) * size
      return { records: items.slice(start, start + size), total: items.length }
    },
    markRead,
    batchRead(ids) {
      let flowed = 0
      for (const id of ids) {
        if (markRead(id)) flowed++
      }
      return flowed
    },
    markAllRead() {
      let flowed = 0
      for (const row of items) {
        if (row.isRead === 0) {
          row.isRead = 1
          flowed++
        }
      }
      return flowed
    },
    clearRead() {
      const before = items.length
      for (let i = items.length - 1; i >= 0; i--) {
        if (items[i].isRead === 1) items.splice(i, 1)
      }
      return before - items.length
    },
  }
}

export const NOTIFICATION_PREFERENCES = [
  { type: 'RECORD_VERIFIED', enabled: true },
  { type: 'RECORD_REJECTED', enabled: true },
  { type: 'FRIEND_ACCEPTED', enabled: true },
  { type: 'RECORD_LIKED', enabled: true },
]
