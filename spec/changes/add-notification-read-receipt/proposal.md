# TASK-186 add-notification-read-receipt 提案：通知已读回执（跨端已读同步）

## Why（现状与痛点）

TASK-183/185 后通知时效已达秒级，但**已读状态跨端仍漂移**：用户在 A 端（页面/设备）标记已读后，B 端铃铛未读数与通知列表要等最长 60s 轮询兜底周期才同步。WS 管道目前只扇出「新通知」一种事件（`notification:push` → `/user/queue/notifications`），未覆盖「已读流转」事件。管道双向能力已就绪，补齐已读回执是通知中心运营面二期的第一刀。

## What（方案）

**变更通道权威性不动**：REST 仍是已读操作唯一入口（`markRead`/`markAllRead` 语义零改动）；回执是**事件**不是指令——服务层在已读流转成功（影响行数 > 0）后发布回执（尽力而为），经 RTopic `notification:read` 扇出至收件人**全部在线会话**（含发起端自身，幂等刷新无害）。

| 层 | 改动 |
| --- | --- |
| 后端 | `NotificationReadReceipt` record（userId/kind=single\|all/notificationId/readAt，静态工厂 single/all）；`NotificationPushRelay` 增第二订阅 `notification:read` → `/user/queue/notification-read`（同一组件两 topic，生命周期复用）；`NotificationService.markRead`/`markAllRead` 影响行数>0 后 `publishReceipt`（失败仅告警，尽力而为沿 §2.1 取舍） |
| 前端 | `notificationWs.ts` onConnect 增订第二个用户队列 `/user/queue/notification-read`——回执与新推**反应同构**（刷新未读数 + 重载列表），复用同一 `onMessage` 回调，零签名变化；`useNotificationBell.ts` / `notifications.page.vue` / `client.ts` / `typed-router.d.ts` / `App.vue` 零触碰 |

跨端已读同步从 60s 轮询周期提升到秒级；回执丢失退化为轮询兜底基线（不劣化）。

## 边界（明确不做）

- 不引入 client→server STOMP SEND（变更走 REST 权威通道；帧通道只承载事件扇出）
- 不改 DB schema / 不加新依赖（后端零 pom 改动、前端零 lockfile 改动）/ 不动 Controller / 网关 / compose / ci.yml
- 不做回执去重：发起端自身收到自己操作的回执 → 与既有本地刷新形成幂等双刷（GET 无害），登记为说明项
- 回执风暴防护按载荷级设计：`markAllRead` 只发**一条**回执（kind=all），不逐条发

## 风险

| 风险 | 缓解 |
| --- | --- |
| 回执与新推共用回调，载荷形态不同 | 注释显式说明两种载荷形态；反应同构是预注册决策 |
| relay 双 topic 单组件，订阅/退订遗漏 | 生命周期对称（PostConstruct 双订 / PreDestroy 双退），单测断言两 topic 常量与投递目标 |
| publish 失败 | 仅告警不影响 REST 返回值（沿 TASK-185 尽力而为语义，单测覆盖） |

## 验收（摘要）

单测矩阵全绿（relay 回执扇出 / service 已读钩子三态：成功才发、零行不发、异常不影响返回）；web 三件套 + frozen-lockfile；Java offline 新基线只增不减（预计 +8 上下，user-service 81→~89，以实测逐位登记）；契约门在途 rc=0；词面门 ZERO_HIT；token 29 项只增不减。真实链路联调本机中间件不可达则 UNDETERMINED（沿 182/185 口径）。
