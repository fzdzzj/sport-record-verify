// 模块级共享未读数 composable（TASK-183 通知铃铛）。
// 单例：App.vue 与通知列表页 import 同一实例；操作后调用 refreshUnread() 即时同步。
// 刷新策略：仅登录态（hasAccessToken()）拉取；未登录置 0 且不发请求；请求失败静默置 0 不弹错。
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { getUnreadCount } from '@/api/client'
import { hasAccessToken } from '@/utils/token'

const unread = ref(0)

const POLL_INTERVAL_MS = 60_000

let timer: ReturnType<typeof setInterval> | null = null
let started = false

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
}

export function useNotificationBell() {
  onMounted(startUnreadPolling)
  onBeforeUnmount(stopUnreadPolling)
  return { unread, refreshUnread }
}
