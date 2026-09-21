# TASK-102 事件可靠性（F03/F09/F13/F22）

## 目标
修复校验事件链路的可靠性：事件丢失无补偿（F03）、自建 DLQ 与 broker 原生重试双轨 + 重试计数键泄漏（F09）、判定结果缓存多实例不一致窗口文档化（F13）、verify 并发重入无互斥（F22）。明细先读 work/mailbox/findings-summary.md 对应条目。

## 范围外
- 不改任何 pom.xml、不改任何 application.yml/properties（配置项一律用 @Value 默认值）
- 不动 record-service 的 RecordEventProducer/VerifyDegradeService（该方向已有补偿）
- 不改 leaderboard-service 的 service 层（LeaderboardService.java 属 TASK-103）
- 不引入新依赖（离线仓库 .m2-repo，settings 为 .mvn-settings.xml）

## 先读文件
- work/mailbox/findings-summary.md（F03/F09/F13/F22）
- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyEventProducer.java
- verify-service/src/main/java/com/sportverify/verify/consumer/VerifyEventConsumer.java
- verify-service/src/main/java/com/sportverify/verify/mapper/VerificationResultMapper.java（参照现有 Mapper 写法）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java
- sql/03-verify-db.sql
- 相关现有测试（VerifyServiceTest / VerifyEventProducerTest / VerifyEventConsumerTest / LeaderboardEventConsumerTest）

## 只改文件
- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyEventProducer.java
- verify-service/src/main/java/com/sportverify/verify/consumer/VerifyEventConsumer.java
- verify-service 下新增 outbox 相关类（entity/mapper/relay 调度器，命名自取，放 verify 包内）
- verify-service/src/test/**（上述类的测试，含新增 outbox 测试）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumerTest.java
- sql/03-verify-db.sql

## 要做的修改
1. F03 本地消息表（outbox）：verify_db 加 `verify_event_outbox` 表（id 自增、event_id 唯一、topic、tag、payload JSON、status PENDING/SENT、retry_count、created_at、sent_at；脚本幂等 IF NOT EXISTS）。VerifyService.verify 与 reviewAppeal 中，发事件改为「同事务落 outbox 行」（upsert verification_result + outbox insert 纳入同一 @Transactional）；新增 @Scheduled relay（多实例 Redisson 锁防重，锁键 verify:outbox:relay，周期与批量用 @Value 默认值如 5s/100 条）：扫 PENDING → syncSend → 成功标 SENT，失败 retry_count+1 留下轮；超阈值（默认 16 次）仅记 error 告警保留行供人工。VerifyEventProducer.publish 保留原签名但内部改写 outbox（最小侵入），或改由 service 直写 outbox——二选一，在 handoff 说明取舍。leaderboard 消费侧不变。
2. F09 去双轨：VerifyEventConsumer 与 LeaderboardEventConsumer 移除自建 MAX_RETRY/retryKey/sendToDlq 逻辑；改用 `consumer.setMaxReconsumeTimes(3)`，失败统一返回 RECONSUME_LATER，超次由 broker 投递 %DLQ%{group}（在类注释写明人工排查入口）；去重 SETNX 逻辑保留。redis retryKey 彻底删除（含键前缀常量）。
3. F22：VerifyService.verify 入口加 Redisson 锁 `lock:verify:{recordId}`，tryLock(0 等待)：拿不到说明同记录正在判定 → 直接走 readCachedResult/DB 现状返回（不重复判定）；拿到锁内完成判定，finally 释放。注意与既有幂等缓存的协作，锁只防并发重入。
4. F13：在 VerifyService 类 javadoc 增补「判定结果 Caffeine 单层缓存的多实例不一致窗口（≤1min）与收敛路径（状态机幂等 + 消费端去重）」说明，不改行为。

## 验收命令
1. `mvn -s .mvn-settings.xml -q -pl verify-service,leaderboard-service -am test` 全绿
2. `grep -c "verify_event_outbox" sql/03-verify-db.sql` ≥ 1 且脚本含 IF NOT EXISTS
3. `grep -rn "retryKey\|sendToDlq" verify-service/src/main leaderboard-service/src/main` 无残留

## 完成定义
- 4 条发现落地，验收命令全过，既有测试不红（如旧测试断言自建 DLQ，随新语义同步更新）
- 写 work/mailbox/tasks/TASK-102/handoff.md：完成情况 / 每条发现改动点（文件:行）/ outbox 设计取舍 / 验收结论 / 「待主 agent 决定」清单
- 回报 ≤300 字：产出 / 校验结果 / 未解决项 / 待决策项

## 约束
- 你是上述文件的唯一写入者；最多 1 次修复重试
- 不准猜测，缺信息写 handoff「待主 agent 决定」
