# TASK-153：判别「批末统一标记 SENT」能否进入性能实验（只裁决，不实施）

## 目标与基线

开工 HEAD `abfc477c1cad64c4885125e7cf10830fdc94e540`。在现行规范（relay 唯一投递 · 判定事件经待发行表投递 ·
投递失败保留行 · 失败行 `retry_count+1` 留 PENDING）下，**只裁决**一组候选——把 relay 的逐行「发送成功后立即条件
`markSent`」改为「批末统一标记」——**是否具备进入性能实验的语义资格**。

**不实施优化**：不改生产 relay/Mapper、不加开关、不跑 c100×2000、不改默认值、**不报告真实吞吐/延迟改善**；
**不得**把 TASK-152 的同窗占比 73.93% 当作批量化可获得收益。

## 边界与判据

- **事实盘点先行**：从 relay（逐行、无 `@Transactional`、独立自动提交、`catch Exception`、`Error` 逃出）、Mapper 三条 SQL、
  真实 DDL（`sent_at` 注释「投递成功时间」、`uk_event_id`、`idx_status_id`）、消费端幂等（eventId SETNX + 业务锚点）
  与规范 delta（archive/wire-verify-outbox + fix-verify-outbox-poison-head-of-line）列出**逐行**行为与不确定项；
  明确区分「规范硬约束 / 既有观测 / 仍需产品决策」，**消费端 SETNX/锚点幂等不能自动视作授权**。
- **最强反例清单**：发送第 k 行后崩溃、部分 SQL 失败、混合发送失败、锁租期/多实例、下轮重扫范围。
- **隔离真库对照**：专用 scratch schema（`task153_batch_scratch`，由仓库 `sql/03-verify-db.sql` 机械改名建成；
  IT 内硬校验 `SELECT DATABASE()`），真实 DDL + 真实 Mapper SQL + 真实 relay 代码路径；发送可编程模拟
  （成功/失败/持有 3s/读墙钟/抛 `Error`），**不得用 Mockito 预制数据库结果冒充真库证据**；
  条件 IT 缺 `TASK153_IT_URL/USER/PASSWORD` 即 assume 跳过且**不记通过**；**绝不触碰演示 `verify_db` 与既有 scratch 数据**。
  候选批量 UPDATE 只存在于测试（PreparedStatement 直写，不进生产 Mapper）。
- **一句裁决**：任一未经授权的重复投递窗口、SENT 可见性、失败重试或时间戳语义差异 → **NO-GO**，保留最小真库反例、
  停止生产批量化方向；只有可复现证据支持既有语义均保持，才可裁决受限 GO（只允许另立同负载性能提案）。
- **不改业务**：不 push / PR；只 stage 本任务文件；既有脏项（归档移名、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/`）原状，不 stash、不 `git add -A`。

## 证据与交付

报告 `docs/perf/判别-outbox-批末标记SENT-语义边界.md`、低基数 JSON `docs/perf/data/exp-outbox-batch-mark-safety.json`；
判别 IT `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkSafetyMysqlIT.java`；
原始输出放 ignored `docs/perf/data/raw/task153-*`。完成本两件套、OpenSpec 三件套据实勾选、TASK-153 spec/handoff、
PLAN / 机会总览、实际文件清单、JSON 校验、`git diff --check` 与无参数 `mailbox-contract.sh`（记录提交前 / 后退出码）。
仅 stage 本任务文件，业务证据与台账分两笔本地提交。
