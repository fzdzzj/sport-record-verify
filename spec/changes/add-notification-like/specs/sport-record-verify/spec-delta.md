# spec-delta：点赞通知（TASK-188 add-notification-like）

## ADDED 需求：点赞通知（记录收到点赞时通知作者）

### 场景

1. 用户 A 首次点赞用户 B 的记录（POST /api/records/{id}/like，SADD 返回 1 的 firstLike 分支）：record-service 经 `LikeEventProducer` 向 topic `record-verify-events` 之外的独立 topic `record-like-events`（Tag LIKED）asyncSend 发布 `LikeEventDTO`（eventId=UUID / recordId / likerId / recordOwnerId / occurredAt，收件人冗余在事件体不回查）；发送失败仅告警不阻断点赞返回（best-effort，无补偿路径）。
2. user-service `LikeEventConsumer`（独立消费组 `notification-like-consumer-group`，编程式 DefaultMQPushConsumer 沿 NotificationEventConsumer 范式）消费 LIKED 事件：eventId SETNX 24h 去重 → `createNotification(recordOwnerId, RECORD_LIKED, recordId, "你的运动记录收到新的点赞", null, "RECORD_LIKED:{recordId}:{likerId}")`。
3. 表级去重键 `RECORD_LIKED:{recordId}:{likerId}`：同一点赞者对同一记录**终身至多一条**点赞通知（取消再赞不重复通知）；MQ eventId 只承担消息重复投递去重，两层幂等各司其职。
4. 不通知的三种情形（预注册）：自赞（likerId == recordOwnerId，发布前短路不产生事件）；幂等跳过（SADD 返回 0 分支）；unlike（取消点赞不产生事件）。
5. 通知类型 `RECORD_LIKED` 加入 `NotificationType` 常量与 `NotificationPreferenceService.SUPPORTED_TYPES`（四类）：偏好闸门（createNotification 落库前）自动覆盖——关闭后点赞通知不落库不推送不计未读；GET /api/notifications/preferences 返回四类视图。
6. RECORD_LIKED 通知经既有 createNotification 主链落库后自动享有：偏好闸门、WS 实时推送（/queue/notifications）、未读计数（铃铛）、已读回执（单条/全部）——四机制零改动。
7. 消费失败重试：返回 RECONSUME_LATER 删去重键放行重投；原生 maxReconsumeTimes=3，超次进 `%DLQ%notification-like-consumer-group`；解析失败直接 ack 丢弃并告警；namesrv 未就绪后台 30s 重连（均沿 NotificationEventConsumer 范式逐字）。
8. web 通知页偏好卡片扩展第四开关（「收到点赞」/ RECORD_LIKED）；保存/读取沿既有三开关模式；通知列表对 RECORD_LIKED 沿既有 type 列渲染零改动。
9. flush 落库 / 对账 / 计数读路径零触碰：点赞通知只在 like() 热路径 firstLike 分支发布，flush 与对账职责不变（落库与收敛）。

### 验收断言

- 单测（offline 确定性，Mockito）：生产端——firstLike 且非自赞 → 发布事件（含 likerId/recordOwnerId/recordId）；自赞 → 零发布；幂等跳过（SADD=0）→ 零发布；unlike → 零发布；LikeEventProducer asyncSend 成败回调沿 RecordEventProducer 测试模式。消费端——buildConsumer 订阅参数（topic=record-like-events / tag=LIKED / 独立组 / maxReconsumeTimes=3）；SETNX 已存在 → 跳过零落库；LIKED → createNotification 以 recordOwnerId 为收件人、dedupKey=RECORD_LIKED:{recordId}:{likerId}；解析失败 ack 丢弃；未知 eventType 跳过。偏好——SUPPORTED_TYPES 四类视图；RECORD_LIKED 关闭闸门不落库。
- 基线：Java offline 527（36/41/110/127/144/59/10）只增不减（预计 +14 上下，以实测逐位登记）；`--static=record-service` 811 不增。
- 前端：偏好卡片四开关读写真实 API；type-check / build / typed-router 零漂移 / frozen-lockfile 全绿。
- 零依赖增量：后端零 pom、前端零 lockfile、零 SQL DDL（既有 VARCHAR(30) 容纳 RECORD_LIKED）。
- 真实链路联调可选：本机 broker 不可达则 UNDETERMINED（沿 182/185/186/187 口径）。
