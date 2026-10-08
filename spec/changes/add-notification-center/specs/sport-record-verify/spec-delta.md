# spec-delta：add-notification-center

变更 `add-notification-center` 对主规格 `spec/specs/sport-record-verify/spec.md` 做以下 ADDED（新增「通知」功能域，归并位置：服务划分 user-service 职责段之后）。

## ADDED Requirement: 通知存储

**GIVEN** 用户产生需要被告知的事件（记录判定出结果 / 好友申请被通过）
**WHEN** 通知落库
**THEN**
- `user_db.notification` 表持久化（收件人 / 类型 / 聚合根 ID / 标题 / 内容 / 已读位 / 幂等键 / 时间戳），建表脚本幂等追加进 `sql/01-user-db.sql`，可重复执行；
- `uk_dedup(dedup_key)` 唯一键保证表级幂等：判定事件通知的 dedup_key = MQ eventId，好友通知 = `FRIEND_ACCEPTED:{requestId}`；同一 dedup_key 重复写入至多一条（改判链 VERIFIED→REJECTED→VERIFIED 每次判定各成一条，type 与 eventId 均不同）；
- `idx_user_read(user_id, is_read, id)` 索引覆盖分页列表与未读数查询。

## ADDED Requirement: 判定结果通知（事件消费）

**GIVEN** verify-service 经 outbox→RocketMQ 投递判定事件（`RecordVerifyEvents.TOPIC`，TAG_VERIFIED / TAG_REJECTED，payload 为 `VerifyEventDTO`）
**WHEN** user-service 的通知消费者收到事件
**THEN**
- 独立消费组 `notification-consumer-group` 订阅既有 Topic 的 VERIFIED || REJECTED（与 leaderboard 消费组同 Topic 不同组不同位点；生产侧与既有消费者零改动）；
- 收件人取 `VerifyEventDTO.userId`（不回查其他服务）；VERIFIED 落「记录通过」通知、REJECTED 落「记录驳回」通知；
- 消费幂等双保险：Redis SETNX（eventId，24h TTL）先行去重 + `uk_dedup` 唯一键兜底（去重键过期后重复投递仍只落一条）；
- 消费失败返回 RECONSUME_LATER 交 MQ 原生退避重投（`maxReconsumeTimes=3`），超次由 broker 转入 `%DLQ%notification-consumer-group`，不自建重试计数、不自建死信 topic；无法解析的消息体直接 ack 丢弃并告警；
- 通知消费失败不得影响 leaderboard 消费组（组间隔离）。

## ADDED Requirement: 好友申请结果通知

**GIVEN** 一条 PENDING 好友申请
**WHEN** 被申请人执行 accept（同意）
**THEN** 在 `FriendService.accept()` 既有本地事务内追加写一条通知（收件人 = 申请人，dedup_key = `FRIEND_ACCEPTED:{requestId}`，与申请状态更新、friendship 插入同事务提交或回滚）。
**WHEN** 被申请人执行 reject（拒绝）
**THEN** 不产生通知（产品裁决：拒绝不打扰）。

## ADDED Requirement: 通知读取与已读

**GIVEN** 收件人名下存在通知
**WHEN** 调用读取接口
**THEN**
- 分页列表仅返回收件人本人的通知，按 id 倒序；
- 未读数 = `COUNT(user_id=?, is_read=0)`，走索引，不引入 Redis 计数器；
- 单条标记已读须校验归属（非本人通知不可标记）；全部已读批量流转 is_read 并回填 read_at；
- 已读流转幂等：重复标记已读为无操作。

## ADDED Requirement: 通知写路径不跨服务

**GIVEN** 通知的两类来源
**WHEN** 写通知
**THEN** 好友通知在 user-service 本地事务内直写（同库）；判定通知经 MQ 异步消费落库（事件由 verify 既有 outbox 可靠投递承载）——不在任何本地事务中跨服务写通知，不引入分布式事务。

## ADDED Requirement: 通知域不回归既有基线

**GIVEN** 本变更实施完成
**WHEN** 全量门禁执行
**THEN** 离线测试基线 450 只增不减（user-service 41 只增）；`--static=record-service` 811 不增；api 模块与其余五服务 `src/**` 零触碰；MQ 既有参数（topic / tag / 既有消费组 / 批次 / 周期）零改动；不引入新 Maven 插件；词面门 / 契约门 / 受保护 token 29 项只增不减，惯例照旧。
