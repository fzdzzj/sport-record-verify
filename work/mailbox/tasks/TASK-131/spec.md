# TASK-131 wire-verify-outbox——判定事件走事务内 outbox，relay 唯一出口

## 目标

F03（TASK-128 核实「仍在」）：a048745 只落了 outbox 骨架（entity/mapper/relay/写入器 + 2 测试），
主链路从未接线——`VerifyService` 两处发事件仍是 upsert 后同步直发、`VerifyEventProducer` catch 吞异常、
全仓无代码写 outbox 行、`sql/` 无 `verify_event_outbox` DDL、relay 每 5s 扫不存在的表报错。
后果：判定 PASSED 但事件丢失 → 榜单永久缺分。

本任务按指导侧设计裁定接线：判定事件改为「同事务 upsert 结果行 + insert outbox PENDING 行」，
relay 成为唯一发送出口，表结构按 TASK-102 spec:33 落进 `sql/03-verify-db.sql`。

## 设计裁定（指导侧已定，照此执行）

1. **DDL**：`verify_event_outbox` 建表语句按 TASK-102 spec:33 补进 `sql/03-verify-db.sql`（幂等 IF NOT EXISTS）。
2. **接线**：`VerifyService` 两处发事件（verify 主链路、reviewAppeal）改为经修正后的
   `VerifyOutboxService`（其 `:31-36` 现为 upsert+直发，javadoc 与实现不符 → 改为真写 outbox 行）。
3. **eventId 语义**：outbox 行在写入时生成并保存 eventId；relay 重发沿用行内 eventId（消费端幂等键稳定）；
   producer 的 UUID-per-publish 只保留在 outbox 写入侧。
4. **单一出口**：relay 是唯一发送路径；判定主链路删除同步直发与 catch-log 兜底。
   `VerifyEventProducer` 保留同步发送能力供 relay 调用。
5. **延迟语义变化**登记在 handoff：事件到达延迟从同步毫秒级变为 relay 周期（5s），由既有 10min 结算纠偏兜底。

## 红绿取证

- 红①：判定主链路「不再同步直发」的断言 → 基线必红（附失败原文与 `VerifyService.java` 行号）
- 红②：scratch 库按 `sql/` 脚本初始化后查 `verify_event_outbox` 存在性 → 基线红（表不存在）；补 DDL 后绿
- 绿：relay 按行内 eventId 重发的断言（补 eventId 透传断言）；既有用例全绿；
  变异验证（临时摘除 outbox insert 复现红①，还原 cmp 零差异）

## 只改清单

- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
- verify-service/src/main/java/com/sportverify/verify/service/VerifyOutboxService.java
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyEventProducer.java
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
- verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java
- verify-service/src/test/java/com/sportverify/verify/service/VerifyOutboxServiceTest.java
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyEventProducerTest.java
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java
- sql/03-verify-db.sql
- spec/changes/wire-verify-outbox/proposal.md
- spec/changes/wire-verify-outbox/tasks.json
- spec/changes/wire-verify-outbox/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-131/spec.md
- work/mailbox/tasks/TASK-131/handoff.md
- work/mailbox/PLAN.md

## 停止边界

- 不动 record→verify 降级链路（`VerifyDegradeService`）
- 不动 leaderboard 消费端（F09 归 TASK-132）
- 不改既有表结构（只新增 outbox 表）
- DB 实测只用 scratch 库
- 不 push

## 复跑口径

- 定向：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`（先红后绿 + 变异）
- offline 全量：`bash scripts/verify/mvn-verify.sh --mode=offline test`（逐模块用例数）
- 契约 rc=0（在途 `--baseline=<开工基线>` + 收口后无参数）
- 词面自检双 locale

## 完成定义

- 判定/终判事件全部经 outbox 落库并由 relay 唯一投递；主链路同步直发与 catch-log 兜底删除
- DDL 落进 `sql/03-verify-db.sql` 且 scratch 库实测表存在、脚本可重复执行
- 红①（失败原文）/红②（表存在性）/绿/变异四段取证齐备，既有用例零回退
- 规范三件套 + 台账两件套 + PLAN 收口记录（绑定 commit 与门槛来源）
