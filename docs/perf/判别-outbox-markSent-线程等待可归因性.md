# 判别：outbox `markSent` 线程等待可归因性（TASK-154，只读 Performance Schema 观测，不优化）

结论先说：**NO-GO（不能把 `markSent` 的服务端等待成本、更小的「fsync / 锁 / 纯 SQL」子项归因）**。
可以闭合的是「身份 + 计数 + 负对照」这一层：目标连接线程可稳定映射，每次目标 UPDATE 的语句计数/digest 计数/影响行数逐窗闭合，
零行 UPDATE 与非目标 SQL 的等待组合可区分；**但**后台 innodb 线程在同一时段有非零等待增量、`wait/synch/*` 仪器未启用、
`events_waits_history_long` 关闭，「哪些等待由后台线程承担、哪些根本没采集」不可拆——因此只报告直接读数，
**不换算任何占比、不命名 fsync/锁/纯 SQL、不实施优化、不跑负载**。TASK-152 的 0.739312 与 TASK-153 的批末标记 NO-GO **均未改写、未翻案**。

## 1. 本次要回答的问题与边界

- 问题：在不改仪器、不重置计数器、不改任何配置的前提下，**只读**已有的 P_S 汇总表，能否把真实 `markSent`
  条件 UPDATE 引起的等待**安全归因**到「执行它的客户端连接线程」，或进一步拆成服务端子项。
- 允许：在专用 `task154_wait_scratch` schema 建最小种子；test-only 条件真库 IT；重复小样本与负对照。
- 禁止（本任务全程遵守）：开关/清空 P_S 仪器、重置任何计数器、改 MySQL 配置、重启或重建容器、删数据卷、
  改生产 Java/SQL/YAML/运行默认值、跑 c100×2000、把全局计数差额归到 `markSent`。

## 2. 环境与只读预检

| 项 | 读数 |
| --- | --- |
| HEAD | `88692cf0d67aeb7aed41ae647a8524b21af374f5`（与任务书一致）；既有脏项（archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`、本任务未跟踪三件套）**未触碰** |
| 实例 | 既有 `task131-scratch-mysql`（MySQL 8.0.46，宿主 13318）与演示 `sport-verify-mysql`（8.0.46，宿主 3307），**只读**登记；两份 P_S 配置一致：`events_waits_current/history/history_long = NO`、`events_statements_history_long = NO`，`events_statements_current/history = YES`、`statements_digest = YES`、`global/thread_instrumentation = YES` |
| 可用的等待仪器 | 400 条 waits instruments 中 54 条 `ENABLED+TIMED`，即 `wait/io/file/%`、`wait/io/table/sql/handler`、`wait/lock/table/sql/handler`；**`wait/synch/*` 未启用** |
| 关键事实 | 尽管 `events_waits_current=NO`，`events_waits_summary_by_thread_by_event_name` 仍有非零行（经验证：目标线程空转即有 `idle`/文件 I/O 行）→ 线程级等待**可观测**；本实例**无** `setup_timers` 表 → 计时单位必须经验标定；`Performance_schema_*_lost` 全 0、`digest_lost=0` |
| 环境披露 | 开工时两个既有容器均为 `Exited (255)`（外部 Docker 引擎周期在 `2026-09-28T03:38:23Z` 杀掉，`OOMKilled=false`，`restart=no`）。本任务只**启动**这两个既有容器（未重建、未改配置、未删卷、未起其他容器）以恢复 TASK-152 结束时的中间件状态；演示 `verify_db` 只读，未写入。[^1] |

[^1]: 原始登记见 `docs/perf/data/raw/task154-01-pre-state.txt` … `task154-05-scratch-baseline-rows.txt`。

## 3. 协议（test-only 条件真库 IT）

`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentWaitAttributionMysqlIT.java`

1. 专用 `task154_wait_scratch` 由仓库真实 DDL 机械改名生成（`sed 's/verify_db/task154_wait_scratch/g' sql/03-verify-db.sql`），
   建后结构核对：PK `id` + `uk_event_id` + `idx_status_id(status,id)`，起始 0 行。
2. **目标连接**（独立 `DriverManager` 连接、autocommit=`true`，与生产「一调用一提交」同语义）取 `CONNECTION_ID()`；
   **观察连接**独立地把该 `PROCESSLIST_ID` 映射到 P_S `THREAD_ID`（断言 `TYPE=FOREGROUND`、`NAME=thread/sql/one_connection`）。
3. SQL 文本取自生产 Mapper 注解（`#{id}` → `?`），逐字：
   `UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`。
4. 每个窗口：观察连接读前快照（目标线程 waits 汇总 / 语句汇总 / `markSent` digest 行）→ 目标连接**只执行一条**该 UPDATE
   → 200 ms 静置 → 观察连接读后快照；逐窗断言：影响行数、行状态、目标线程**语句总计数增量=1**（观察查询不得落到目标线程）、
   `markSent` digest 计数增量=1、digest 累计影响行数 = 期望值。
5. 样本与负对照：`w1..w5`（各一条新 PENDING 行，重复小样本）；`n1` 不存在 id（0 行）、`n2` 已 SENT 行（0 行）、
   `n3` `SELECT 1`、`n4` 同表 SELECT、`n5` 真实 `incrRetry` UPDATE（1 行）。
6. 序列前后另取：后台 `thread/innodb/%` 线程 waits 汇总、全局 waits 汇总、`SHOW GLOBAL STATUS` 的 `Com_update/Com_insert`；
   序列结束后复核线程映射不变（身份稳定性）。
7. 计时单位：**经验标定**——观察连接执行 `SELECT SLEEP(0.5)`，从其语句历史读回该语句事件计时
   `500368961000` / `500561170000` / `500563096000` ps（≈0.5 s）→ 语句事件计时单位为**皮秒**。
   注：首次实现用「线程汇总前后差」标定得到 delta=2，原因是 **SELECT 的汇总计数在结果发完后才落账**，会把前快照那次读自己算进来；
   改为读语句历史行后稳定。

## 4. 直接读数

两轮独立运行（同一栈、同一 schema，中间做过一次变异复核）。窗口 P50 无关——每窗只有一条语句，下表逐窗原值。

### 4.1 目标 `markSent`（pending 行，1 行命中）

| 窗口 | 客户端墙钟 µs | 服务端语句事件 µs（digest 增量） | 线程 waits 非 idle 增量 |
| --- | --- | --- | --- |
| 运行 A w1 | 11967.0 | 10405.8 | binlog=2/5602.1µs; table/sql/handler=2/109.8µs; lock/table/sql/handler=1/2.2µs |
| 运行 A w2 | 7203.8 | 6286.0 | binlog=2/2184.0µs; table=2/66.8µs; lock=1/1.6µs |
| 运行 A w3 | 4800.0 | 3623.0 | binlog=2/1831.7µs; table=2/100.3µs; lock=1/1.4µs |
| 运行 A w4 | 8365.1 | 7152.6 | binlog=2/3895.1µs; table=2/99.9µs; lock=1/1.2µs |
| 运行 A w5 | 8832.4 | 7589.7 | binlog=2/3134.6µs; table=2/86.8µs; lock=1/1.1µs |
| 运行 B w1 | 6201.4 | 4653.1 | binlog=2/2241.2µs; table=2/78.7µs; lock=1/1.7µs |
| 运行 B w2 | 6298.1 | 4938.1 | binlog=2/1729.4µs; table=2/52.1µs; lock=1/1.9µs |
| 运行 B w3 | 24325.7 | 15889.4 | binlog=2/7434.7µs; table=2/1363.1µs; lock=1/3.0µs |
| 运行 B w4 | 10661.0 | 7852.2 | binlog=2/3284.4µs; table=2/189.0µs; lock=1/2.0µs |
| 运行 B w5 | 11736.5 | 9522.1 | binlog=2/3549.9µs; table=2/69.0µs; lock=1/2.2µs |
| 运行 A n5 `incrRetry`（1 行） | 7821.6 | 6649.9 | binlog=2/2723.8µs; table=2/66.1µs; lock=1/1.8µs |
| 运行 B n5 `incrRetry`（1 行） | 14177.8 | 11711.5 | binlog=2/4051.2µs; table=2/145.2µs; lock=1/2.8µs |

- 每个命中行的 UPDATE 窗口在**目标线程**上都稳定出现同一种组合：`wait/io/file/sql/binlog` **2 次**、
  `wait/io/table/sql/handler` **2 次**、`wait/lock/table/sql/handler` **1 次**；另有 `idle` 1 次（~0.22–0.26 s，两窗之间的空转，不属于本次语句）。
- 两轮共 12 个更新窗口（含 `incrRetry`）计数完全一致；计时随负载波动（binlog 1.7–7.4 ms），未出现第三种事件。

### 4.2 负对照（同线程）

| 窗口 | 影响行数 | 服务端语句事件 µs | 线程 waits 非 idle 增量 |
| --- | --- | --- | --- |
| 运行 A n1 不存在 id | 0 | 385.2 | table=1/55.5µs; lock=1/2.3µs（**无 binlog**） |
| 运行 B n1 不存在 id | 0 | 268.0 | table=1/13.7µs; lock=1/2.1µs（**无 binlog**） |
| 运行 A n2 已 SENT 行 | 0 | 295.3 | table=1/14.5µs; lock=1/2.0µs（**无 binlog**） |
| 运行 B n2 已 SENT 行 | 0 | 250.4 | table=1/17.8µs; lock=1/1.7µs（**无 binlog**） |
| 运行 A n3 `SELECT 1` | — | 74.2 | **(no delta)** |
| 运行 B n3 `SELECT 1` | — | 78.7 | **(no delta)** |
| 运行 A n4 同表 SELECT | — | 271.6 | table=1/22.2µs; lock=1/2.0µs |
| 运行 B n4 同表 SELECT | — | 298.4 | table=1/44.4µs; lock=1/2.3µs |

即：**命中行的 UPDATE 才有 `binlog` 文件等待；零行 UPDATE 与 SELECT 没有**；`SELECT 1` 连表级等待都没有。
负对照与目标在等待组合上**可区分**（这正是任务要求核对的一层）。

### 4.3 后台线程与全局计数（同一序列时段）

| 项 | 运行 A 增量 | 运行 B 增量 |
| --- | --- | --- |
| `thread/innodb/log_flusher_thread` | log_file 34 次 / 111.2 ms | 35 次 / 136.1 ms |
| `thread/innodb/log_writer_thread` | log_file 47 次 / 0.83 ms | 48 次 / 1.32 ms |
| 其他后台线程（io_write / page_flush_coordinator / dblwr 等） | 运行 A 无增量 | io_write×3、page_flush_coordinator(dblwr/data) 有增量，合计约 23.5 ms |
| 全局 waits（`events_waits_summary_global_by_event_name`） | `sql/binlog` 24 次 / 38.4 ms；`innodb_log_file` 80 次 / 106.0 ms | `sql/binlog` 24 次 / 41.5 ms；`innodb_log_file` 81 次 / 133.3 ms |
| `SHOW GLOBAL STATUS` | `Com_update +8`、`Com_insert +6` | 同上（`+8 / +6`） |

- 目标线程上产生 binlog 等待的语句共 7 条（预热 1 + `w1..w5` + `n5`）→ 线程口径合计 14 次 binlog 事件；
  **全局**同口径是 24 次。差额与同时段其他连接（种子 INSERT）的提交活动一致，**不能摊到 `markSent`**——
  这正是「全局计数差额不可作单语句测量」的实证。
- 后台 redo 线程（`log_flusher`/`log_writer`，运行 B 还含页刷/双写）在同一时段**确有非零增量**；本轮全部语句
  （含种子 INSERT）都在同一个窗口里发生，因此**无法**把其中哪一部分判给某一次 `markSent` 的提交耐久性路径。

## 5. 已闭合的判据（可以说的）

1. **身份闭合**：`CONNECTION_ID()` → `THREAD_ID` 映射成功，`TYPE=FOREGROUND`、`NAME=thread/sql/one_connection`；
   序列结束后复核映射不变。
2. **计数闭合**：每个窗口目标线程**语句总计数增量恰好 1**（观察连接的 12+ 次 P_S 读没有落到目标线程）；
   `markSent` digest 计数增量恰好 1、累计影响行数增量 = 影响行数（命中 1 / 零行 0）。
3. **digest 同一性**：目标语句 digest = `6b07036bdfeae6b8f12d602eb37af86bfe73e3f9dac3600006dfd8cde12b05b7`，
   与 TASK-152 在演示库管理员窗口记录的同一条生产 SQL 指纹**逐字一致**（`DIGEST_TEXT` 亦为同一规范化文本）。
4. **语义未变**：命中窗行状态 `SENT` + `sent_at` 非空；零行窗无状态变化；影响行数与 JDBC 返回值一致。
5. **负对照可区分**（见 4.2）。
6. **可复现**：以上组合在两轮独立运行中一致；一轮变异（在目标线程多发一条 `SELECT 1`）使「语句总计数增量=1」
   断言转红（`Tests run 1 / Failures 1 / rc=1`，实测 delta=2），还原后复绿——断言是活的。

## 6. 未闭合的原因（裁决 NO-GO 的依据）

1. **后台归属不明**：`thread/innodb/log_flusher_thread` / `log_writer_thread`（运行 B 还有页刷/双写线程）在序列时段
   有非零等待增量。客户端线程上观测到的 2 次 binlog 文件等待**只是**该语句在自身上计到的部分；
   redo 刷盘由后台线程承担的部分**既不能确认也不能量化**，且同一时段的种子 INSERT 也走同样的提交路径。
2. **仪器未覆盖**：`wait/synch/*` 全部未启用 → 「等待日志刷盘完成」这类同步等待**根本没有采集**；
   `events_waits_history_long = NO` → 无法由聚合数**还原**等待事件树（嵌套/先后关系不可得）。
3. **不能命名**：`wait/io/file/sql/binlog` 的 2 次事件只是「该文件类上的 2 次等待」，不能拆成 write/fsync，
   也不能与 redo 一起称为「持久化成本」。
4. **计量口径**：本实例无 `setup_timers`，单位靠经验标定；`idle` 混在同一张汇总表里必须单独剔除；
   客户端墙钟与服务端语句事件是**两个独立观测**，不得相减或相除。
5. 因此本任务**不**给出任何「markSent 内部 fsync / 锁 / 纯 SQL 占比」，也**不**把 TASK-152 的 73.93%
   反推成待优化子项或可回收收益。

## 7. 裁决

- **NO-GO：不能把 `markSent` 的完整等待成本或其子项归因**（依据 = 第 6 节 1–4）。
  受限事实陈述（**不构成 GO、不构成优化授权**）：在**本隔离环境与本仪器设置**下，命中行的 UPDATE 在
  **其客户端连接线程**上稳定留下「binlog 文件等待 ×2 + 表处理器等待 ×2 + 表锁等待 ×1」这一可直接读到的组合，
  且零行 UPDATE / 非目标 SQL 不产生该组合。
- 不得据此改 SQL/索引/事务/池/JVM/MQ/`relay` 默认值；不得声称延迟或吞吐收益；不得据此实施批量标记。
- 依据本任务结果若要再走一步，只能**另立**「同负载、同仪器设置」的单因素提案，且仍须先过语义判别。

## 8. 门槛与退出码

| 项 | 结果 | 日志 |
| --- | --- | --- |
| 显式真库 IT（两轮） | `1/0/0/0`、`BUILD SUCCESS`、**rc=0** | `docs/perf/data/raw/task154-08-it-run.log`、`task154-10-it-regreen.log` |
| 变异红（目标线程多发一条语句） | `1/1/0/0`、**rc=1**，`w1：目标线程语句总数增量应为 1；实测=2` | `docs/perf/data/raw/task154-09-it-mutation-red.log` |
| 缺环境变量 | `1/0/0/1`（skipped）、rc=0，**不记为真库通过** | `docs/perf/data/raw/task154-12-it-noenv-skip.log` |
| offline verify-service 常规套件 | `110/0/0/0`、`BUILD SUCCESS`、**rc=0**（与既有基线同数；`*IT` 不被 Surefire 默认收集） | `docs/perf/data/raw/task154-11-offline-regular.log` |
| scratch 状态 | 本任务只新增 `task154_wait_scratch`（7 行：6 SENT + 1 PENDING(retry=1)）；`task142=104 / task147=6 / task148=6 / task149=130 / task153=3` 跑前跑后**不变**；演示 `verify_db` 只读 | `docs/perf/data/raw/task154-13-scratch-poststate.txt` |

`--mode=online` / CI 未跑 → **未达外部门槛**。本任务未改任何生产代码，无生产红绿用例需补。

## 9. 未覆盖

后台线程等待与某次 `markSent` 的**对应**、`wait/synch` 类等待、事件树还原、连接获取/prepare/commit 拆分、
服务端 SQL/网络/fsync 命名、生产 Spring/Hikari 装配下的同一观测、多实例锁竞争、真实 relay 负载（未跑 c100×2000）、
榜单段与 R5（leaderboard/mapmatch 未启动）、--mode=online 与 CI。

## 10. 只改清单（本任务）

- 新增：`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentWaitAttributionMysqlIT.java`、
  `docs/perf/判别-outbox-markSent-线程等待可归因性.md`、`docs/perf/data/exp-outbox-relay-mark-sent-wait-attribution.json`、
  三件套 `spec/changes/prove-verify-mark-sent-wait-attribution/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`（本任务输入，据实勾选）；
  台账 `work/mailbox/tasks/TASK-154/{spec.md,handoff.md}`、`work/mailbox/PLAN.md`、`work/mailbox/后端优化机会总览-2026-09-26.md`。
- 原始日志：`docs/perf/data/raw/task154-01…13-*`（忽略路径）。
- **未触碰**：归档移名、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`、生产 Java/SQL/YAML、
  其他 scratch schema、演示 `verify_db` 数据。
