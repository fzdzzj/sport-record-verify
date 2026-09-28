# TASK-154 回传：判别 outbox `markSent` 线程等待可归因性（只读 P_S 观测，不优化）

## 结论

开工 HEAD `88692cf0d67aeb7aed41ae647a8524b21af374f5`（与任务书一致，作业期间未被其他会话推进）。**裁决：NO-GO**——
在**不改仪器、不重置计数器**的前提下只读已有 P_S 汇总表，隔离 scratch 真库 + test-only IT 证明**身份与计数层可闭合**
（目标连接→`THREAD_ID` 映射成功且序列后不变；每个窗口**目标线程语句总计数增量恰好 1**、`markSent` digest 计数增量恰好 1、
影响行数与行状态一致；两轮独立运行一致），负对照可区分（命中行 UPDATE 才有 `binlog` 文件等待 ×2；零行 UPDATE / `SELECT 1`
无，同表 SELECT 仅表级等待）——**但后台归属不可拆**：同一序列时段 `thread/innodb/log_flusher_thread`（A 34 次/111.2ms、
B 35 次/136.1ms）与 `log_writer_thread`（47 次/0.83ms、48 次/1.32ms）等待增量非零（B 轮另含 io_write/page_flush/dblwr），
全局 `sql/binlog` 计数 24 对线程口径 14，且 `wait/synch/*` 全部未启用、无 `events_waits_history_long`
→ 「哪些等待由后台线程承担、哪些根本没采集」无法判定，**只报告直接读数，不换算 fsync/锁/纯 SQL 占比、不命名、不实施优化**。
**未改生产 Java/SQL/YAML/仪器/默认值、未跑 c100×2000、未起四服务；TASK-152 的 0.739312 与 TASK-153 的批末标记 NO-GO
均未改写、未翻案。** 报告 `docs/perf/判别-outbox-markSent-线程等待可归因性.md`，机器摘要
`docs/perf/data/exp-outbox-relay-mark-sent-wait-attribution.json`。

## 起点核对

- HEAD `88692cf` 与任务书一致；既有脏项（归档移名删除侧 6、`spec/changes/archive/*` 新侧、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/`）与 TASK-153 结束时一致、**未 stash、未 `git add -A`、未触碰**。
- 本任务未跟踪三件套 `spec/changes/prove-verify-mark-sent-wait-attribution/{proposal.md,tasks.json,specs/.../spec-delta.md}`
  为交付输入，已随业务提交入库（tasks.json 5 组全部 completed/passes）。
- 隔离环境：既有 `task131-scratch-mysql`（MySQL 8.0.46、宿主 13318）**只新增** `task154_wait_scratch` schema
  （仓库 `sql/03-verify-db.sql` 机械改名生成；IT 内硬校验 `SELECT DATABASE()`）；跑前跑后既有 scratch 行数不变
  （task142=104 / task147=6 / task148=6 / task149=130 / task153=3，`task154-04/05/13` 日志）；
  演示 `sport-verify-mysql`（宿主 3307）**只读登记 P_S**，`verify_db` 未写入；未启动任何 Java 服务、未跑负载。

## 环境披露（必须保留）

- 开工时两个既有容器均为 `Exited (255)`——外部 Docker 引擎周期于 `2026-09-28T03:38:23Z` 将其杀掉
  （`OOMKilled=false`、`restart=no`），并非本任务或前序任务所为。本任务为完成只读预检与实验，**只启动**这两个
  既有容器（未重建、未改配置、未删卷、未起其他容器）。收尾时两个容器保持运行，如实回传。

## 只读预检（仪器侧事实）

- 两实例 P_S 配置一致：`events_waits_current/history/history_long = NO`、`events_statements_history_long = NO`、
  `events_statements_current/history = YES`、`statements_digest = YES`。
- 400 条 waits instruments 中 54 条 `ENABLED+TIMED`（`wait/io/file/%`、`wait/io/table/sql/handler`、
  `wait/lock/table/sql/handler`）；**`wait/synch/*` 全部未启用**。尽管 `events_waits_current=NO`，
  `events_waits_summary_by_thread_by_event_name` 仍有非零行（空转即有 `idle`/文件 I/O）→ 线程级等待可观测。
- 本实例**无** `setup_timers` 表 → 计时单位经验标定：`SELECT SLEEP(0.5)` 语句事件 5.0037–5.0056e11 ps
  （≈0.5 s）→ **皮秒**。`Performance_schema_*_lost=0`、`digest_lost=0`；仪器开关/计数器全程未改、未重置。

## 协议与直接读数（摘要）

- test-only IT `VerifyEventOutboxMarkSentWaitAttributionMysqlIT`（`*IT` 默认不收集；缺 `TASK154_IT_URL/USER/PASSWORD`
  即 assume 跳过且**不记通过**）：独立 `DriverManager` 目标连接（autocommit=true，与生产「一调用一提交」同语义）
  取 `CONNECTION_ID()` → 观察连接映射 `PROCESSLIST_ID`→`THREAD_ID`（断言 `TYPE=FOREGROUND`、
  `thread/sql/one_connection`）；SQL 文本由生产 Mapper 注解运行时渲染（`#{id}`→`?`）。
- 两轮独立运行（A 线程 47/88、B 60/101）共 12 个命中窗口（`w1..w5` + 预热 + `incrRetry`），目标线程上**一致**出现：
  `wait/io/file/sql/binlog` **×2**、`wait/io/table/sql/handler` **×2**、`wait/lock/table/sql/handler` **×1**
  （binlog 1.7–7.4 ms 随负载波动）；另有 `idle` 1 次/窗（~0.22–0.26 s 空转，剔除）。
  客户端墙钟 A 4800.0–11967.0 µs、B 6201.4–24325.7 µs；服务端语句事件 A 3623.0–10405.8 µs、
  B 4653.1–15889.4 µs（**两个独立观测，不相减/相除**）。目标 digest `6b07036b…b05b7` 与 TASK-152 逐字一致。
- 负对照：`n1` 不存在 id / `n2` 已 SENT 行（各 0 行）**无 binlog 等待**；`n3` `SELECT 1` 无非 idle 等待；
  `n4` 同表 SELECT 仅表级；`n5` 真实 `incrRetry`（1 行）与 markSent 同组合。
- 序列时段：后台 `log_flusher`/`log_writer` 非零（数值见结论）；全局 waits `sql/binlog=24/38.4ms(A)、24/41.5ms(B)`；
  `SHOW GLOBAL STATUS` `Com_update +8`、`Com_insert +6`。

## 判据与退出码口径

- **本任务运行过 Maven（唯一入口 `scripts/verify/mvn-verify.sh`）**：显式真库 IT **1/0/0/0、BUILD SUCCESS、rc=0**
  （`task154-08`）、还原复绿 rc=0（`task154-10`）；**变异红**（目标线程多发一条 `SELECT 1` → 断言实测=2）
  **1/1/0/0、BUILD FAILURE、rc=1**（`task154-09`）；**缺变量** `1/0/0/1 skipped` rc=0，**不记为真库通过**
  （`task154-12`）；offline verify-service 常规套件 **110/0/0/0、BUILD SUCCESS、rc=0**（与既有基线同数，
  `*IT` 不被默认 Surefire 收集，`task154-11`）。
- JSON 校验：`exp-outbox-relay-mark-sent-wait-attribution.json` 与三件套 `tasks.json` 均解析通过（rc=0）；
  `git diff --check` rc=0（含已暂存检查 rc=0）。
- 无参数 `mailbox-contract.sh`：**提交前** rc=1（在途 TASK-154 与既有脏项同现，预期口径）；
  **提交后** rc=0（足迹不在工作树，视为已收口）。原始日志见 `docs/perf/data/raw/task154-14/15-mailbox-contract-*.log`。
- `--mode=online`/CI 未跑，**未达外部门槛**；本任务不是完整 MQ/Redis 端到端，也不含真实 relay 负载。

## 实际改动清单（只改）

- **业务证据提交 `ae8fa9290701bfda2790be7257c160c5800fbc8a`（6 文件）**：
  `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentWaitAttributionMysqlIT.java`（新）·
  `docs/perf/判别-outbox-markSent-线程等待可归因性.md`（新）·
  `docs/perf/data/exp-outbox-relay-mark-sent-wait-attribution.json`（新）·
  本变更三件套 `spec/changes/prove-verify-mark-sent-wait-attribution/proposal.md`（新）·
  `.../tasks.json`（新）· `.../specs/sport-record-verify/spec-delta.md`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-154/spec.md`（新）· `work/mailbox/tasks/TASK-154/handoff.md`（新）·
  `work/mailbox/PLAN.md`（改）· `work/mailbox/后端优化机会总览-2026-09-26.md`（改）。
- 既有脏项原样保留；未用 `git stash`、未 `git add -A`、未 push、未建 PR。

## 未覆盖 / 不得推出

- **未覆盖**：后台线程等待与某次 `markSent` 的对应；`wait/synch` 类等待（本实例根本没采集）；等待事件树/嵌套还原；
  连接获取/prepare/commit 拆分；服务端 SQL/网络/fsync 命名；生产 Spring/Hikari 装配下的同一观测；多实例锁竞争；
  真实 relay 负载（未跑 c100×2000）；榜单段与 R5（leaderboard/mapmatch 未启动）；`--mode=online`/CI。
- **不得推出**：不得把线程汇总称为完整单语句成本；不得把后台刷盘或全局计数归到 `markSent`；不得从 TASK-152 的
  73.93% 反推待优化子项；不得据此改 SQL/索引/事务/池/JVM/MQ/`relay` 默认值或 `innodb_flush_log_at_trx_commit`；
  不得声称延迟或吞吐收益；不得借本结果翻案 TASK-153 的批末标记 NO-GO。受限事实陈述（不构成 GO、不构成优化授权）：
  本隔离环境与本仪器设置下，命中行 UPDATE 在其客户端连接线程上稳定留下「binlog×2 + 表处理器×2 + 表锁×1」组合，
  零行 UPDATE / 非目标 SQL 不产生该组合。
