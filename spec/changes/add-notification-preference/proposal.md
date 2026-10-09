# TASK-187 add-notification-preference 提案：通知偏好设置（按类型开关）

## Why（现状与痛点）

通知中心三类通知（判定通过/驳回/好友通过）对全部用户无条件落库与实时推送——用户无法关闭自己不想收到的类型（如高频的判定结果类）。运营面三期登记项。

## What（方案）

**闸门位置 = createNotification 落库前（单一权威闸门）**：偏好关闭的类型直接不落库——不落库即不推送、不计未读、轮询不可见，全链路一致静默；重新开启仅对未来新通知生效（不追溯补发）。这是预注册裁决，不是 relay 投递前过滤（候选期一句话描述的修正）：

| 对比项 | 落库前闸门（选定） | relay 投递前过滤（否决） |
| --- | --- | --- |
| 语义 | "我不想收到这类通知"——铃铛不加数、中心不堆积 | 仅 WS 静音，60s 轮询仍把未读拉回来，用户观感"关了没用" |
| 数据完整性 | 通知中心是展示数据非审计数据（审计在 verify-service 侧），无损失 | 落库照旧，中心堆积用户明确不要的行 |
| 实现 | service 首行一次偏好查询 + 一个分支 | relay 每条消息扇出前查询，两处状态源 |

| 层 | 改动 |
| --- | --- |
| DB | `sql/01-user-db.sql` 追加 `notification_preference(user_id, type, enabled, updated_at, PK(user_id, type))`；缺行 = 默认开启（惰性 upsert，无预填充） |
| 后端 | `NotificationPreference` entity + mapper + `NotificationPreferenceService`（getPreferences 缺行补默认 / upsert 幂等）；`NotificationService.createNotification` 首行闸门（关闭 → return false 不落库不推送，返回语义扩展 javadoc 载明，两调用方 fire-and-forget 已核实安全）；`NotificationController` 增 GET/PUT `/api/notifications/preferences` 两端点 + View DTO |
| 前端 | 通知页内嵌"通知偏好"卡片（3 个 a-switch + 保存）；`client.ts` 纯追加 2 函数 + DTO；typed-router / App.vue / notificationWs.ts 零触碰（无新页面） |

## 边界（明确不做）

- NotificationType 常量集合零扩（3 类型已存在，偏好只做开关不做新类型）
- 既有 REST 契约零改动（列表/未读数/已读接口与 WS 推送/回执管道零触碰）
- 不追溯：开启偏好不补发历史（含 MQ SETNX 24h 幂等窗口内被拦截的最后一条——登记为说明项）
- 不加依赖、零 pom / lockfile / ci.yml / compose / gateway 改动
- 不做偏好变更的 WS 实时广播（偏好是低频自服务设置，变更端本地即可见）

## 风险

| 风险 | 缓解 |
| --- | --- |
| 闸门查询增加通知写入路径一次 DB 读 | 走 PK(user_id, type) 点查；偏好与通知同库同命运（查询失败即插入也会失败，不预设 fail-open 分支） |
| MQ 事件被拦截后 SETNX 已设 → 开启偏好后 24h 内重复投递不补落库 | 登记为说明项（开启只对未来生效）；dedup 语义本身不破坏 |
| PUT 并发 upsert | PK(user_id, type) + INSERT ... ON DUPLICATE KEY UPDATE，行级原子 |

## 验收（摘要）

单测矩阵全绿（闸门三态：关闭不落库不推送 / 缺省开启正常 / 返回语义；偏好 service：缺行补默认、upsert 幂等；controller 端点沿既有测试模式）；scratch DB 建表验证（沿 TASK-182 口径）；web 三件套 + frozen-lockfile；Java offline 新基线只增不减（预计 +10 上下，以实测逐位登记）；契约门在途 rc=0；词面门 ZERO_HIT；token 只增不减。
