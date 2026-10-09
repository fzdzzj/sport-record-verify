// 模块级共享未读数 composable（TASK-183 通知铃铛）。
// 单例：App.vue 与通知列表页 import 同一实例；操作后调用 refreshUnread() 即时同步。
// 刷新策略：仅登录态（hasAccessToken()）拉取；未登录置 0 且不发请求；请求失败静默置 0 不弹错。
// 实时推送（TASK-185 add-notification-ws-push）：登录态开 WS，notification 消息 → refreshUnread()；
// 60s 轮询保留为兜底不删（WS 断流时新鲜度不劣于纯轮询基线）。未登录不开流不开轮询。
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { getUnreadCount } from '@/api/client'
import { connectNotificationWs, disconnectNotificationWs } from '@/api/notificationWs'
import { hasAccessToken } from '@/utils/token'

const unread = ref(0)

const POLL_INTERVAL_MS = 60_000

let timer: ReturnType<typeof setInterval> | null = null
let started = false

// 实时推送通知回调注册口（TASK-185）：通知页打开期间注册，收到推送后刷新当前页。
type NotificationListener = () => void
const notificationListeners = new Set<NotificationListener>()

/** 注册实时通知回调（消息到达时触发刷新）；返回注销函数 */
export function onNotification(cb: NotificationListener): () => void {
  notificationListeners.add(cb)
  return () => notificationListeners.delete(cb)
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    refreshUnread()
  }
}

function onPollTick() {
  // 页面不可见时跳过本轮轮询回调；可见性恢复时由 onVisibilityChange 触发刷新
  if (document.visibilityState === 'visible') {
    refreshUnread()
  }
}

/** 收到实时推送：刷新未读数并通知注册回调（错误静默，不阻塞请求链） */
function handleNotificationMessage(_payload: unknown): void {
  refreshUnread()
  notificationListeners.forEach((cb) => {
    try {
      cb()
    } catch {
      /* 单回调异常不影响其余回调 */
    }
  })
}

export async function refreshUnread(): Promise<void> {
  if (!hasAccessToken()) {
    unread.value = 0
    return
  }
  try {
    const res = await getUnreadCount()
    unread.value = typeof res.data === 'number' ? res.data : 0
  } catch {
    // 静默置 0 不弹错：铃铛不阻塞主流程
    unread.value = 0
  }
}

export function startUnreadPolling(): void {
  if (started) return
  started = true
  refreshUnread()
  // 登录态开 WS 实时推送（TASK-185）；未登录 connect 为 no-op
  if (hasAccessToken()) {
    connectNotificationWs(handleNotificationMessage)
  }
  timer = setInterval(onPollTick, POLL_INTERVAL_MS)
  document.addEventListener('visibilitychange', onVisibilityChange)
}

export function stopUnreadPolling(): void {
  if (!started) return
  started = false
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  document.removeEventListener('visibilitychange', onVisibilityChange)
  // 同步关闭实时推送连接（TASK-185）；轮询兜底逻辑零删改
  disconnectNotificationWs()
}

export function useNotificationBell() {
  onMounted(startUnreadPolling)
  onBeforeUnmount(stopUnreadPolling)
  return { unread, refreshUnread }
}