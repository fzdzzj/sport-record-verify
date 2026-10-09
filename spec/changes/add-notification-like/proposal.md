# TASK-188 add-notification-like 提案：点赞通知（记录收到点赞时通知作者）

## Why（现状与痛点）

点赞域（record-service，TASK-180/181 落地）已是完整的读写热冷分离链路（Redis 热路径 + 5s flush 落库），但记录作者对收到的点赞完全无感知——通知中心三类通知（判定通过/驳回/好友通过）无点赞类型，作者需主动翻记录才能看到点赞数变化。通知中心运营面四课题（183/185/186/187）收官后的自然延伸：补齐社交互动类通知。

## What（方案）

**触发挂点 = like() 热路径 firstLike 分支 asyncSend 发布事件**（预注册裁决，沿 ADR-0009 热路径无本地事务）：

| 对比项 | 热路径 firstLike 发布（选定） | flush 落库后发布（否决） |
| --- | --- | --- |
| 语义 | 点赞瞬间通知（SADD 返回 1 天然幂等闸门，幂等跳过不通知） | 落库后通知：延迟 5s+ 且语义漂移为"持久化完成" |
| 正确性 | 取消再赞各自独立事件（dedupKey 终身一次去重，见下） | flush 按 (record_id,user_id) 去重取**末次动作**：like→unlike 净删会被误判为"曾点赞"，且 unlike 后无行可发 |
| 与既有机制关系 | flush/对账零触碰（职责不变：只管落库与收敛） | flush 职责膨胀，需区分"首次落库"与"补偿重放"，破坏幂等重放语义 |

**防轰炸三闸门**：自赞不通知（发布前短路 liker==owner）· 幂等跳过不通知（SADD 返回 0 分支）· dedupKey=`RECORD_LIKED:{recordId}:{likerId}`（同一人对同一记录终身只通知一次，取消再赞不重复通知；MQ eventId 只管消息重复投递，两层幂等各司其职——沿 FRIEND_ACCEPTED:{requestId} 先例）。

| 层 | 改动 |
| --- | --- |
| api | `RecordLikeEvents`（TOPIC=record-like-events / TAG_LIKED / EVENT_LIKED）+ `LikeEventDTO`（eventId/recordId/likerId/recordOwnerId/occurredAt，收件人冗余在事件体不回查——沿 VerifyEventDTO 先例） |
| record-service | `LikeEventProducer`（新，沿 RecordEventProducer asyncSend 模式逐字：traceId 捕获恢复 + 失败仅告警，**无补偿路径**——点赞通知 best-effort）；`RecordLikeService.like()` firstLike 分支注入发布（自赞短路）；record-service 已有 rocketmq-spring-boot-starter 与 producer 配置，**零 pom 零配置** |
| user-service | `LikeEventConsumer`（新，沿 NotificationEventConsumer 范式逐字：编程式 DefaultMQPushConsumer + 独立消费组 notification-like-consumer-group + SETNX 24h + maxReconsumeTimes=3 + %DLQ% + 后台重连）；`NotificationType` 扩 `RECORD_LIKED`；`NotificationPreferenceService.SUPPORTED_TYPES` 扩为四类 |
| web | 通知页偏好卡片三开关扩四开关（「收到点赞」）；通知列表渲染/点赞触发面（verdict.page.vue、client.ts like 函数）零触碰 |
| DB | **零 DDL**：notification.type 与 notification_preference.type 均 VARCHAR(30)，RECORD_LIKED(12 字符) 容纳 |

**Topic/消费组决策**：新 topic `record-like-events` + 新消费组 `notification-like-consumer-group`——点赞事件不是校验事件（record-verify-events 语义域不污染），新消费组独立位点（本组消费失败不影响判定通知组，沿 leaderboard/notification 两组先例）；broker `autoCreateTopicEnable=true` 随发随建（TASK-110 先例），零运维。

**既有机制自动受益（零改动）**：RECORD_LIKED 经 createNotification 落库 → 偏好闸门（TASK-187 落库前权威闸门自动覆盖新类型）+ WS 实时推送（TASK-185 type 字段透传）+ 未读计数（TASK-183 铃铛）+ 已读回执（TASK-186）全链路即刻生效。

## 边界（明确不做）

- unlike 不通知（取消点赞是收回不是新事件，预注册）；flush/对账/READ 路径零触碰
- 不改既有三类通知行为与 record-verify-events topic/三既有消费组
- 不做点赞者昵称/头像富化（通知 content 沿判定通知先例留空，sourceId=recordId 可跳转记录）
- 不加依赖：后端零 pom、前端零 lockfile、零 SQL 脚本、零 ci.yml/compose/gateway 改动
- 不做 MQ 不可达时的补偿重发（点赞通知非关键链路，best-effort，登记说明项）

## 风险

| 风险 | 缓解 |
| --- | --- |
| 热路径增加一次 MQ 提交开销 | asyncSend 非阻塞（提交微秒级、不等 Broker ACK）；序列化/提交失败同步回调仅告警，不阻断点赞返回（沿 RecordEventProducer 模式） |
| 通知轰炸（热门记录/反复点赞） | dedupKey 终身一次 + 自赞短路 + 幂等跳过不通知 + 偏好开关可关四层防护 |
| MQ 不可达事件丢失 | best-effort 无补偿（与判定通知 SUBMITTED 降级 Feign 不同：点赞无下游闭环，丢失不损数据）；登记说明项 |
| 消费端收到畸形/未知事件 | 解析失败 ack 丢弃告警 + 未知 eventType 跳过（沿 NotificationEventConsumer 范式） |

## 验收（摘要）

单测矩阵全绿（生产端：firstLike 发布/自赞短路/幂等跳过/unlike 不发布 + asyncSend 成败回调；消费端：订阅参数/SETNX 幂等/解析失败丢弃/落通知调用与 dedupKey；偏好四类视图与 RECORD_LIKED 闸门）；Java offline 新基线只增不减（预计 +14 上下，record-service 与 user-service 两模块增量，以实测逐位登记）；`--static=record-service` 811 不增；web 三件套 + frozen-lockfile；契约门在途 rc=0；词面门 ZERO_HIT；token 只增不减。
