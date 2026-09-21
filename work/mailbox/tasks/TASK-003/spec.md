# TASK-003 异步消息 & 事件驱动

## ⛔ 主 agent 结论（2026-09-20）：方向前提作废，本任务回滚

**本 spec 的目标写错了。** 它假设"Leaderboard 没有异步消息"，于是要求引入 RabbitMQ。实际情况：

- 消费侧早就存在且是完整链路：`leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java`
  用 RocketMQ（`DefaultMQPushConsumer` + `RocketMQTemplate`）消费 `record-verify-events` 的 VERIFIED/REJECTED 判定事件，
  并已接上 `applyVerified` / `rollbackOnRejected`，自带去重、重试计数、死信投递。
- 生产侧也已落地：`verify-service/.../mq/VerifyOutboxRelay.java:43` 注入 `RocketMQTemplate`（TASK-008 的 outbox）。
- 基础设施只投了 RocketMQ：`docker-compose.yml` 命中 13 次、`docker-compose.services.yml` 命中 6 次，
  三个 compose 文件里 **rabbit 命中 0 次**。

按本 spec 建出来的 RabbitMQ 栈因此是纯冗余：`leaderboard-update-queue` 全仓零生产方、无 broker、
`processUpdate()` 只有 `log.info`。另含 5 项硬缺陷（`x-max-length` 误用为重试次数、主队列反绑 DLX 成环、
manual ack 无 Channel、`deleteDedupKey` 无调用方、重试键与 RocketMQ 侧共用 `leaderboard:retry:{eventId}`）。

**处置**：删除 `config/RabbitMQConfig.java`、`consumer/LeaderboardUpdateConsumer.java`、
`event/LeaderboardUpdateEvent.java`、`test/.../LeaderboardUpdateConsumerTest.java`，
连同 pom 的 `spring-boot-starter-amqp` 与 application.yml 的 rabbitmq 段。
这 4 个文件是 untracked，删前必须先存 patch 到 `work/mailbox/rollback/TASK-003-rabbitmq.patch`。

**给后续任务的教训**：本 spec 第 9-10 行"先读文件"已经点名了 `mq/LeaderboardEventConsumer.java`。
读完仍另起一套中间件，说明"先读文件"没有被当作约束——以后凡涉及中间件/缓存/调度，
派发词里要加一句"若模块内已有同职责实现，先回传对比结论再动手"。

## 目标
引入 RabbitMQ 做 Leaderboard 数据更新的异步任务队列，实现幂等性和死信处理。

## 范围外
- 不改动现有同步接口

## 先读文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java
- leaderboard-service/pom.xml

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/RabbitMQConfig.java（新建：RabbitMQ 配置）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/event/LeaderboardUpdateEvent.java（新建：事件 DTO）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/consumer/LeaderboardUpdateConsumer.java（新建：消费者）
- leaderboard-service/pom.xml（添加 spring-amqp 依赖）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=LeaderboardUpdateConsumerTest
docker run -d --name rabbitmq -p 5672:5672 rabbitmq:3-management
# 发送测试消息并验证消费成功
```

## 完成定义
- 消息发送成功
- 消费者能消费并去重
- 失败消息进入 DLQ

## 不准猜测
- 如果 LeaderboardEventConsumer.java 不存在 → 在 handoff.md 写明"待主 agent 确认正确的事件消费者类名"
