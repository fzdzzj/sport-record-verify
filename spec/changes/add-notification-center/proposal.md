# 提案：add-notification-center（通知中心）

提案人：指导 Agent（2026-10-08）。状态：待派发。

## Why（为什么做）

系统社交闭环断在「用户不知道有事发生」：好友申请被通过、运动记录判定出结果（VERIFIED / REJECTED），收件方唯一获知方式是轮询各业务列表。全仓现状核实：

- 全仓 Java / 配置 / Web 控制台**零通知代码**（`notification|notif|站内信` 全仓检索仅命中无关项）；
- 事件流现成：verify 判定事件已走 outbox → RocketMQ（`RecordVerifyEvents.TOPIC`，TAG_VERIFIED / TAG_REJECTED）→ leaderboard 独立消费组沉淀榜单——通知消费者可搭同一班车，**零生产侧改动**（`VerifyEventDTO` 已携带 `userId`，收件人无需回查）；
- 好友申请通过点 `FriendService.accept()`（user-service，本地事务内「申请状态更新 + friendship 插入」）与通知表同库，可事务内直写，无跨服务写。

## What（做什么）

在 user-service 新增通知域（后端全量）：

1. **存储**：`user_db.notification` 表（幂等脚本追加进 `sql/01-user-db.sql`），`uk_dedup` 唯一键做表级幂等，`idx_user_read(user_id, is_read, id)` 覆盖分页与未读数；
2. **判定事件通知**：`NotificationEventConsumer` 订阅既有 `RecordVerifyEvents.TOPIC` 的 VERIFIED / REJECTED Tag，独立消费组 `notification-consumer-group`（与 leaderboard 消费组同 Topic 不同组不同位点，先例：verify 与 leaderboard）；消费范式逐字沿 `LeaderboardEventConsumer`（编程式 DefaultMQPushConsumer、Redis SETNX eventId 去重 + 表唯一键双保险、RECONSUME_LATER + `maxReconsumeTimes=3` + `%DLQ%` 原生重试、解析失败 ack 丢弃、namesrv 未就绪后台重连）；
3. **好友申请通过通知**：`FriendService.accept()` 事务内写通知（收件人 = 申请人）；**reject 不通知**（产品裁决：拒绝不打扰）；
4. **读取入口**：REST 四接口——分页列表 / 未读数 / 单条标记已读 / 全部已读；未读数走 `COUNT + 索引`（不引入 Redis 计数器，登记为非目标）。

## 明确不做（本变更边界）

- **Web 控制台铃铛位**：列二期候选（TASK-183，依赖本变更的 REST 契约），前后端解耦推进符合「一次只改一类因素」纪律；
- **点赞通知 / 排榜变动通知**：二期候选，本变更只接现成事件流（判定 + 好友）；
- **推送渠道（邮件 / 短信 / WebSocket）**：站内通知表是唯一载体，渠道扩展另行提案；
- **不改 api 模块**（`VerifyEventDTO.userId` 已在）；**不改 MQ 既有参数**（topic / tag / 既有消费组 / 批次 / 周期零触碰，新消费组为纯新增）。

## 依赖增量与红线核查

- user-service pom 新增 `redisson-spring-boot-starter`、`rocketmq-spring-boot-starter`（版本均由 root `dependencyManagement` 既有锁定，**不引入新 Maven 插件**）；
- 其余五服务 `src/**` 与 api 模块零触碰；`ci.yml` / `scripts/` 零触碰；
- 离线测试基线 450 只增不减（user-service 41 只增）；`--static=record-service` 811 不增（user-service 不在静态门口径，如实登记）。

## 验收要点

- 表级 + Redis 级双幂等：同一 eventId 重复投递 / 同一申请重复 accept 均只产生一条通知；
- 消费失败走 MQ 原生重试与 `%DLQ%notification-consumer-group`，不自建重试计数；
- 通知可见性与操作权仅限收件人本人；
- 契约门两件套 / 词面门 / 受保护 token 29 项只增不减 / PLAN.md 纯追加，惯例照旧；
- 外部终验：推送后下一次外部门槛 CI 绿。
