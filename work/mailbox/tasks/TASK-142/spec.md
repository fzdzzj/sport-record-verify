# TASK-142：outbox 满批重试耗尽行堵住队首——真实 SQL 先红后绿的最小取批资格修复

## 目标

按 `spec/changes/fix-verify-outbox-poison-head-of-line/` 三件套执行一次完整作业：
先在隔离 scratch MySQL 上用真实 SQL 复现「最早满批 100 条 PENDING 全部重试耗尽时，
ID 更大的下一条可投递行被永久遮挡」的行为红；再最小修改取批资格——`selectPendingBatch`
增加 `retry_count < maxRetry` 条件、由 relay 传入当前上限，使耗尽行不占发送批次但仍留库
供人工处理。**不改索引、状态机、批次大小、relay 周期与 MQ 参数，不声称查询/业务延迟提速。**

规范来源：`spec/changes/fix-verify-outbox-poison-head-of-line/`（proposal.md / tasks.json /
specs/sport-record-verify/spec-delta.md）。

## 开工基线

- HEAD 应为 `7d44134a52d187768452f8d2a60be86835eed735`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、
  `.trae/`、`spec/changes/add-verify-degrade-status-index/`；未 stash、未 add -A。

## 停止条件（要点）

1. 若旧 SQL 无法在相同隔离数据上复现「前 N 耗尽行遮挡后一可投递行」，或发现现有其他
   调度/清理路径会在查询前自动移走这些行 → 停止修改并回报反证。
2. 真实 SQL 验证环境不可用则行为红记未覆盖，**不以 mock 预制已过滤列表冒充 SQL 红**。
3. 不改索引、状态机、批次、周期、MQ 参数；不向演示库写入坏行；不 push、不建 PR。

## 判别式

- 红：scratch 库 `task142_outbox_scratch`（容器 `task131-scratch-mysql`，MySQL 8.0.46）上
  旧 SQL（`WHERE status='PENDING' ORDER BY id LIMIT 100`）首轮返回 100 行、`contains_live=0`；
  未修复实现上 Mapper 取批资格契约单测以 `NoSuchMethodException` 失败。
- 绿：同数据上新 SQL（`AND retry_count < 16`）返回 `evt-live-0101`、`evt-bound-15`，不含
  `retry_count=16` 行与 SENT 行；耗尽 101 行仍留库；offline verify-service 测试 rc=0。
- 门槛：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` 与
  `bash work/mailbox/verification/task142-outbox-poison-check.sh`。

## 收口

三件套勾选 + TASK-142 两件套 + PLAN 验收记录 + 改动清单一致性 + mailbox 契约；
只提交本任务文件（业务与台账两笔本地提交），不 push、不建 PR。
