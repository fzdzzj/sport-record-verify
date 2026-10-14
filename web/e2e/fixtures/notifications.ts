/**
 * 通知域夹具（TASK-227 route-mock）：未读数 3；列表 2 条（1 未读 1 已读）；偏好 4 项全开。
 * 供顶栏导航（铃铛角标）与通知页挂载断言使用。
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

export const UNREAD_COUNT = 3

export const NOTIFICATION_LIST: NotificationItem[] = [
  { id: 51, type: 'RECORD_VERIFIED', sourceId: 1000, title: '记录校验通过', content: '您的跑步记录已通过校验', isRead: 0, createdAt: '2026-10-14T09:00:00' },
  { id: 50, type: 'FRIEND_ACCEPTED', sourceId: 7, title: '好友申请已通过', content: '跑友1 已同意您的好友申请', isRead: 1, createdAt: '2026-10-14T08:00:00' },
]

export const NOTIFICATION_PREFERENCES = [
  { type: 'RECORD_VERIFIED', enabled: true },
  { type: 'RECORD_REJECTED', enabled: true },
  { type: 'FRIEND_ACCEPTED', enabled: true },
  { type: 'RECORD_LIKED', enabled: true },
]
