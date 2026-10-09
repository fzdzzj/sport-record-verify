# spec-delta：通知偏好设置（TASK-187 add-notification-preference）

## ADDED 需求：通知偏好（按类型开关）

### 场景

1. 用户读取偏好：GET /api/notifications/preferences 返回三类通知（RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED）的开关视图；从未设置过的用户返回全开（缺行默认 true，无需预填充）。
2. 用户关闭某类型（如 RECORD_VERIFIED）：PUT 后该类型新通知**不落库**——不推送（WS 静默）、不计未读（铃铛不加数）、轮询不可见、通知中心不堆积，全链路一致静默。
3. 用户重新开启：仅对未来新通知生效，不追溯补发历史（含被拦截期间 MQ SETNX 幂等窗口内的最后一条，登记为说明项）。
4. 偏好闸门位置：createNotification 落库前单一权威闸门；被拦截时返回 false（返回语义扩展：false = 已存在幂等跳过 **或** 偏好关闭，javadoc 载明；两调用方 fire-and-forget 不依赖返回值）。
5. 重复 PUT 同值：upsert 幂等（PK(user_id, type) + ON DUPLICATE KEY UPDATE），行级原子，无重复行。
6. 偏好变更不做 WS 实时广播：低频自服务设置，变更端本地即可见。
7. 未登录访问偏好端点：沿通知中心既有鉴权口径（X-User-Id 由网关注入，缺失即拒绝）。
8. 通知页展示偏好卡片：三类开关 + 保存；保存成功提示、失败沿 5003 等 `[code] message` alert 内规。
9. 通知类型集合零扩：偏好只对既有三类做开关，不引入新类型。

### 验收断言

- 单测（offline 确定性）：闸门三态（关闭类型 → insertIgnore 零调用且 relay publish 零调用、返回 false；缺省/开启 → 正常落库推送返回 true；偏好关闭与已存在同返 false 时调用方无感）；getPreferences 缺行补默认全 true；upsert 重复同值幂等；GET/PUT 端点契约（沿 user-service 既有 controller 测试模式，若无先例则 service 层覆盖并登记）。
- scratch DB：notification_preference 建表 DDL 可执行、PK 与 ON DUPLICATE KEY UPDATE 行为符合断言（沿 TASK-182 口径）。
- 前端：偏好卡片三开关读写真实 API；未登录不发请求；type-check / build / typed-router 零漂移 / frozen-lockfile 全绿。
