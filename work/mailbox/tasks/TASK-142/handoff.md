# TASK-142 回传：outbox 满批重试耗尽行堵住队首——真实 SQL 先红后绿的最小取批资格修复

## 回传概要

- **开工 HEAD**：`7d44134a52d187768452f8d2a60be86835eed735`（与任务书一致）。既有脏项
  archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 未触碰；未 stash、未 add -A。
- **提交**：两笔本地提交——业务+测试+交付物+规范三件套为
  `6248ec926eb92350caebe495133767d7299af0c3`
  （`fix(verify): outbox 取批按重试上限过滤耗尽行，解除队首饥饿`，9 文件）；台账两件套与
  本记录为收口提交（`docs(mailbox): TASK-142 提交绑定与验收记录`，3 文件；台账提交哈希由
  任务回传承载，台账无法承载自身提交哈希）。未 push、未建 PR。
- **结论**：**已修复队首饥饿的正确性缺陷**（耗尽行不再占发送批次）；**不声称查询耗时、
  吞吐或业务延迟已改善**——EXPLAIN 显示取批仍走顺序扫描，过滤后还需跳过耗尽行，代价形态
  变化但未测量提速。
- **反证检查（先做）**：verify-service 全部生产源码只有 1 处 `@Scheduled`（relay 自身），
  全仓无任何 `DELETE`/清理 outbox 行的路径，无重置 `retry_count` 的路径（`setRetryCount(0)`
  只出现在写侧 `newPendingRow`），DDL 无触发器/事件。**不存在「查询前自动移走耗尽行」的
  路径 → 无反证，继续修复。**

## 行为红（隔离 scratch MySQL，真实 SQL）

- 环境：独立 scratch 容器 `task131-scratch-mysql`（`mysql:8.0.46`，宿主 13318，非 compose
  实例），自有库 `task142_outbox_scratch`（DDL 为 `sql/03-verify-db.sql` 的
  `verify_event_outbox` 逐字副本）；未向演示库 `verify_db` 写入任何坏行。
- 种子：id 1..100 PENDING `retry_count=16`（= 默认 maxRetry，全部耗尽）；id 101 PENDING
  `retry_count=0`（可投递）；id 102 PENDING `retry_count=15`；id 103 PENDING `retry_count=16`；
  id 104 SENT（`retry_count=0`）。
- 旧 SQL（HEAD 的 `selectPendingBatch` 逐字）首轮：`returned_rows=100`、`min_id=1`、
  `max_id=100`、`contains_live=0`、`returned_exhausted=100` → **遮挡复现（行为红）**：
  满批 100 条耗尽行把 id 101 挡在批外。
- 未修复实现的 Java 侧红：`mvn-verify.sh --mode=offline --pl verify-service test` **rc=1**，
  `Tests run: 90, Failures: 0, Errors: 1`，失败点为新增 Mapper 取批资格契约单测
  `NoSuchMethodException: VerifyEventOutboxMapper.selectPendingBatch(int,int)`。
  该红是 **Java 契约/反射红**，不是行为红；本任务的行为红以上一条 scratch SQL 为准
  （未用 Mockito 预制已过滤列表冒充 SQL 红测）。

## 最小修复

- `VerifyEventOutboxMapper.selectPendingBatch`：SQL 增 `AND retry_count < #{maxRetry}`，
  签名增 `@Param("maxRetry") int maxRetry`。
- `VerifyOutboxRelay`：`selectPendingBatch(batchSize, maxRetry)`，把 `verify.outbox.max-retry`
  当前值传入取批查询；既有 `retryCount >= maxRetry` 跳过分支保留为**防御性兜底**（取批已过滤，
  仅运行中下调上限等极端情况下可达）。
- 未删除 / 未重置 / 未改写耗尽行，未改 eventId、批次大小、relay 周期、延迟与 MQ 参数。

## 行为绿（同一 scratch 数据）

- 新 SQL 首轮：`returned_rows=2`、`event_ids=evt-live-0101,evt-bound-15`、
  `contains_live=1`、`contains_bound15=1`、`contains_bound16=0`、`contains_sent=0`
  → 后一正常行进入前 N 条，阈值-1 有资格、阈值行与 SENT 行不入选；耗尽行留库 **101 行**。
- 判别脚本 `task142-outbox-poison-check.sh`：旧 SQL 遮挡断言 + 新 SQL 放行断言 + 边界/保留
  断言 + 源码接线命中断言，全部通过，**退出码 0**（脚本另含 `--container` 缺省与 3 号环境
  不可用出口，本次未触发）。
- **Maven 唯一入口**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`
  **rc=0**、`Tests run: 93, Failures: 0, Errors: 0, Skipped: 0`（89 → 93，**+4 只增不减**：
  1 条 Mapper 取批资格契约单测 + 3 条 relay 单测；未删除既有用例）。
- `git diff --check` 无空白/冲突标记告警（仅 autocrlf 的 LF→CRLF 提示）。

## EXPLAIN 与查询代价（只记录，不声称提速）

| 查询 | EXPLAIN 访问路径 | EXPLAIN ANALYZE 实测 |
| --- | --- | --- |
| 旧 SQL | `possible_keys=idx_status_id`，实际 `key=PRIMARY`，`rows=100`，`filtered=99.04` | Limit 100 → 实际返回 100 行，index scan PRIMARY 读 100 行 |
| 新 SQL | `possible_keys=idx_status_id`，实际 `key=PRIMARY`，`rows=100`，`filtered=33.01` | Limit 100 → 实际返回 2 行，index scan PRIMARY 读 **104 行**（需跳过前 100 条耗尽行） |

- 口径：两查询均未使用 `idx_status_id`，而是 PRIMARY 顺序扫描；过滤后仍按 id 顺序扫描，
  在耗尽行规模大时需扫过全部耗尽行才能取满一批，**顺序扫描成本随耗尽行长增长**；本任务
  未测量吞吐/延迟，**不声称过滤是查询优化**。
- 未添加索引、未改状态机/批次/周期/MQ 参数；未在真实演示库制造坏行。

## 实际改动清单

- verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java
- verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMapperSqlContractTest.java（新）
- work/mailbox/verification/task142-outbox-poison-sql.sql（新）
- work/mailbox/verification/task142-outbox-poison-check.sh（新）
- spec/changes/fix-verify-outbox-poison-head-of-line/proposal.md（开工前既有未跟踪，随本变更提交，内容未改）
- spec/changes/fix-verify-outbox-poison-head-of-line/specs/sport-record-verify/spec-delta.md（同上）
- spec/changes/fix-verify-outbox-poison-head-of-line/tasks.json（同上，本次按事实勾选）

（以上 9 项为业务提交 `6248ec926eb92350caebe495133767d7299af0c3`）

- work/mailbox/tasks/TASK-142/spec.md（新）
- work/mailbox/tasks/TASK-142/handoff.md（新，本文件）
- work/mailbox/PLAN.md（追加验收记录）

（以上 3 项为台账收口提交）

## 未覆盖/缺口

- **未加索引**：`(status,id)` 仍在但 EXPLAIN 实际走 PRIMARY；大量历史耗尽行下顺序扫描成本
  可能升高，本任务按停止边界只记录风险，不顺手扩围；如实证退化再另立状态迁移/索引方案。
- **观测面下降**：耗尽行不再每轮产生 `log.error` 告警（旧实现对同一批耗尽行每轮重复告警），
  人工盘点需用 SQL（`status='PENDING' AND retry_count>=maxRetry`）；本任务未新增盘点/告警
  接口，避免扩围。
- **配置边界未定义**：`verify.outbox.max-retry<=0`（如设 0 则所有 PENDING 行都不可投递）与
  库内 `retry_count` 非法值的语义本任务未定义、未防护，属既有阈值语义。
- **无常驻服务端到端实测**：未启动 verify-service 对真 MySQL/RocketMQ 跑 relay 生产路径
  （只做隔离 SQL 判别与单测）；`--it`、`--mode=online`、CI 均未跑；verify-service 的 IT
  类清单不在唯一入口支持范围内，缺 env 会被跳过记未覆盖。
- **历史数字不作证据**：TASK-138 的 outbox PENDING 1012 / SENT 998 未证明当时存在耗尽行，
  不作为本缺陷的事故证据。
- **多实例锁语义沿用未改**：Redisson 锁与 batch 语义保持原状，本任务未新增并发交错判别。
