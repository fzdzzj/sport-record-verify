// 通知实时推送 WebSocket+STOMP 客户端（TASK-185 add-notification-ws-push / TASK-186 add-notification-read-receipt）。
// 封装 @stomp/stompjs Client：brokerURL 由当前协议推导 ws/wss + /user/ws-notifications；
// connectHeaders 携带 Bearer access token（浏览器 WS API 不能自定义 HTTP 头，token 入
// CONNECT 帧是协议内正规位，见任务书 §2.1）；
// 订阅两队列：/user/queue/notifications（新通知推送）与 /user/queue/notification-read（已读回执）；
// 回执与新推反应同构（刷新未读数 + 重载列表），共用同一 onMessage 回调（预注册设计决策）；
// reconnectDelay 5000 内建重连；断开 deactivate 清理。未登录调用 connect 即 no-op（不开流）。
import { Client } from '@stomp/stompjs'
import { getAccessToken, hasAccessToken } from '@/utils/token'

let client: Client | null = null

/** 由当前页面协议推导同源 broker URL（http→ws、https→wss，经网关 /user/ws-notifications） */
function resolveBrokerUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/user/ws-notifications`
}

/**
 * 建立实时推送连接（未登录 no-op）。
 *
 * @param onMessage 收到消息时的回调（载荷为新推送或已读回执对象，反应同构）
 */
export function connectNotificationWs(onMessage: (payload: unknown) => void): void {
  if (!hasAccessToken()) {
    return // 未登录不开流（红线 §0.2 语义：未登录零请求）
  }
  if (client && client.active) {
    return // 已存在活跃连接，不重复建连
  }
  const token = getAccessToken()
  if (!token) {
    return
  }
  const stomp = new Client({
    brokerURL: resolveBrokerUrl(),
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 5000,
    onConnect: () => {
      stomp.subscribe('/user/queue/notifications', (frame) => {
        let payload: unknown = frame.body
        try {
          payload = JSON.parse(frame.body)
        } catch {
          /* 非 JSON 载荷原样透传 */
        }
        onMessage(payload)
      })
      stomp.subscribe('/user/queue/notification-read', (frame) => {
        let payload: unknown = frame.body
        try {
          payload = JSON.parse(frame.body)
        } catch {
          /* 非 JSON 载荷原样透传 */
        }
        onMessage(payload)
      })
    },
  })
  client = stomp
  stomp.activate()
}

/** 断开并清理实时推送连接（停止内建重连并释放资源） */
export function disconnectNotificationWs(): void {
  if (client) {
    client.deactivate()
    client = null
  }
}
