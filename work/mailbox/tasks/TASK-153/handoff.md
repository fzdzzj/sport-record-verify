# TASK-153 回传：判别「批末统一标记 SENT」的语义边界（只裁决，不实施）

## 结论

开工 HEAD `abfc477c1cad64c4885125e7cf10830fdc94e540`（与任务书一致，作业期间未被其他会话推进）。**裁决：NO-GO**——
隔离 scratch 真库 + 真实 DDL/Mapper/relay 代码路径对照下，「批末统一标记 SENT」出现四类差异：**重复投递窗口由在飞
1 行扩大为整批**（最强反例：3 行全部已被模拟 broker 接收后进程退出，下轮真实取批基线 `[crash-e3]` vs 候选
`[crash-e1, crash-e2, crash-e3]`）、**SENT 独立连接可见性推迟到批末**、**`sent_at` 漂移 0s→3s 且整批压平（间隔 4s→0s）**、
**批末聚合标记丢逐行归因（2 id 中 1 命中只返回 1；真 SQL 失败整批留 PENDING，异常类型与逐行不同）**。
消费端 SETNX/业务锚点幂等**不作为**授权依据。**未改生产 relay/Mapper、未加开关、未跑 c100×2000、未改默认值、
不声称吞吐/P99 改善；TASK-152 的 73.93% 未作批量化收益依据、其读数未改写。**报告
`docs/perf/判别-outbox-批末标记SENT-语义边界.md`，机器摘要 `docs/perf/data/exp-outbox-batch-mark-safety.json`。

## 起点核对

- HEAD `abfc477` 与任务书一致；既有脏项（归档移名删除侧 6、`spec/changes/archive/*` 新侧、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/`）与 TASK-152 结束时一致、**未 stash、未 `git add -A`、未触碰**。
- 本任务未跟踪三件套 `spec/changes/prove-verify-outbox-batch-mark-safety/{proposal.md,tasks.json,specs/.../spec-delta.md}`
  为交付输入，已随业务提交入库。
- 隔离环境：既有 `task131-scratch-mysql`（MySQL 8.0.46，宿主 13318）**只新增** `task153_batch_scratch` schema；
  跑前跑后既有 scratch 行数不变（task147=6 / task148=6 / task149=130，`task153-01`/`task153-09` 日志）；
  **未触碰**演示 `verify_db` 与演示栈（本任务未起任何服务、未跑负载）。

## 事实盘点（现行语义基线，源码为准）

- relay `relay()` **无 `@Transactional`**：按 id 升序逐行，`syncSend` 成功后**立即**条件 `markSent`
  （`SET status='SENT', sent_at=NOW() WHERE id=? AND status='PENDING'`），标记提交后处理下一行 → 第 k 行发送时前 k-1 行
  已对**独立连接可见** SENT（真库实测快照 `[[PENDING×3],[SENT,PENDING,PENDING],[SENT,SENT,PENDING]]`）。
- 发送失败 → `incrRetry` 后 `continue`（失败不中断后续行）；标记失败同样 `incrRetry` 计失败；`markSent` 返回行数
  **被丢弃**（0 行命中仍计成功，实跑摘要 `rows=1, success=1, failed=0`）；`Error` 不被 `catch (Exception)` 捕获 →
  崩溃时「已发送未标记」至多**在飞 1 行**。
- `eventId` 在 outbox 写入时生成并保存，relay 重发**沿用行内 eventId**；耗尽行（`retry_count >= maxRetry=16`）取批即排除、
  保留不投递；防重锁 `verify:outbox:relay` 抢不到整轮跳过。
- DDL：`status` 注释「PENDING 待投递，SENT 已投递」；`sent_at` 注释**「投递成功时间」**；`uk_event_id` 唯一键；
  `idx_status_id(status,id)`。全仓只有 relay 读 `status`；`sent_at` 生产链路只写不读（分析口径见 `归因-*.md`）。
- 规范硬约束（`archive/wire-verify-outbox` + `fix-verify-outbox-poison-head-of-line` 两份 delta）：判定事件须经待发行表
  由 relay 唯一投递并标 SENT、消费端 eventId 与行内**逐字一致**；失败行 `retry_count+1` 且保持 PENDING、超阈值保留
  不静默丢弃；同 eventId 重复投递 → SETNX 去重、业务仅执行一次。**SENT 可见性节奏、重复窗口宽度、`sent_at` 精度
  无规范文字承诺**（列为既有观测），故改动它们**需要产品决策**，本任务不得单方面假设。

## 真库判别与红绿

- test-only 判别 IT（`*IT` 默认不收集）：`sendPhaseBatchEnd` 与 relay 同取批/同顺序/同耗尽兜底/同失败 `incrRetry`，
  唯一差别 = 成功行不在发送后标记、批末用**测试专用**批量条件 UPDATE 统一标记（刻意保留 `status='PENDING'` 条件）。
  7 用例：可见性 / 崩溃重扫（最强反例，`SimulatedProcessExit extends Error`）/ 混合失败 / 条件更新与聚合归因 /
  `sent_at` / 第二实例取批 / 耗尽行。
- **判别力（变异红）**：把候选变异回「发送后逐行标记」→ **恰好 5 例红**（全部有判别力的用例：可见性/崩溃/混合/`sent_at`/
  第二实例），2 例不区分用例保持绿；还原后复绿 7/0/0/0。混合批次里「失败行不标 SENT、`retry_count+1`、耗尽行不动」
  与「下轮资格同 SQL」两路径**无差异**——差异集中在崩溃窗口/可见性/时间戳/归因四类，可直接对照任务停止条件。

## 保留的最小真库反例

`task153_batch_scratch.verify_event_outbox` 三行 `crash-e1/crash-e2/crash-e3` 全部 `PENDING, retry_count=0, sent_at=NULL`；
下轮**真实取批 SQL** 返回全部 3 行（`task153-09` 日志）——即「3 条消息已到达 broker、数据库仍称 3 条都未投递」。
该状态按任务要求保留；候选协议与批量 SQL 均**未**进入生产代码。

## 验收与退出码口径

- **本任务运行过 Maven（唯一入口 `scripts/verify/mvn-verify.sh`）**：真库 IT `7/0/0/0` rc=0（BUILD SUCCESS）；
  变异红 `7/5/0/0` rc=1（日志内 `[verify-entry] Maven 以退出码 1 结束`）；还原复绿 `7/0/0/0` rc=0；
  **缺变量** `7/0/0/7 skipped` rc=0（BUILD SUCCESS，**不记为通过**）；offline verify-service 常规套件
  **`110/0/0/0` rc=0**（与既有基线同数，`*IT` 不被默认 Surefire 收集）；只跑最强反例 `1/0/0/0` rc=0（保状态）。
- JSON 校验：`exp-outbox-batch-mark-safety.json` 与三件套 `tasks.json` 均解析通过（rc=0）。
  `git diff --check` rc=0（无新增空白错误）。
- 无参数 `mailbox-contract.sh`：**提交前** rc=1（本任务在途清单与既有脏项同现，属预期口径）；
  **提交后** rc=0（足迹不在工作树，视为已收口）。原始日志 `docs/perf/data/raw/task153-10-mailbox-contract-precommit.log`、
  `task153-11-mailbox-contract-postcommit.log`。
- `--mode=online`/CI 未跑，**未达外部门槛**；本任务不是完整 MQ/Redis 端到端验证（发送为模拟），也未跑任何负载。

## 工作树如实说明（必须保留）

- 既有脏项（归档移名删除侧与 `spec/changes/archive/*` 新侧、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）
  **非本任务产物、未暂存、原样保留**。
- 本任务 raw 证据、commit-message 临时文件在 ignored `docs/perf/data/raw/task153-*`（未入库）；
  scratch 只新增 `task153_batch_scratch` schema 并按其 DDL 初始化，保留反例 3 行；演示库/演示栈未触碰，未清库、未删卷。
- 本任务**未启动任何服务、未跑负载**（无 Java 进程、无容器启停）；未 push、未建 PR。

## 实际改动清单（只改）

- **业务证据提交 `89945314a4767bff0e6b6747ab1d1e312200469e`（6 文件）**：
  `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkSafetyMysqlIT.java`（新）·
  `docs/perf/判别-outbox-批末标记SENT-语义边界.md`（新）·
  `docs/perf/data/exp-outbox-batch-mark-safety.json`（新）·
  本变更三件套 `spec/changes/prove-verify-outbox-batch-mark-safety/proposal.md`（新）·
  `.../tasks.json`（新）· `.../specs/sport-record-verify/spec-delta.md`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-153/spec.md`（新）· `work/mailbox/tasks/TASK-153/handoff.md`（新）·
  `work/mailbox/PLAN.md`（改）· `work/mailbox/后端优化机会总览-2026-09-26.md`（改）。
- 既有脏项原样保留；未用 `git stash`、未 `git add -A`、未 push、未建 PR。

## 未覆盖 / 不得推出

- **未覆盖**：真实 RocketMQ/Redis 端到端（发送为可编程模拟）；生产 Spring/Hikari 装配与真实网络下同批窗口时长；
  真实 broker 重发对消费端 SETNX 命中率的量化影响；多实例真实锁竞争下窗口叠加；`sent_at` 变化对 `归因-*.md`
  lag 分析的量化影响；`--mode=online`/CI；任何负载/容量对照；「批末标记 + 中间态（如先写 SENDING）」等替代组合
  （超出本任务边界，需另立提案与规格授权）。
- **不得推出**：不得称已证明批量化收益/吞吐改善/P99 改善；不得把 TASK-152 的 73.93% 当作批量化可回收收益；
  不得称已定位生产瓶颈；不得改 relay 默认 5000ms、批次、重试或优化 `markSent`；**不得**在未获产品对
  「重复窗口/可见性/`sent_at`/归因」四项授权前重启生产批量化方向；消费端幂等现状**不构成**授权。
