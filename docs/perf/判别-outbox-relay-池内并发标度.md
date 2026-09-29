# 判别：生产 Hikari 池（默认 10）下 outbox relay 批内并发的 `S_prod(N)`（TASK-161；scratch 真库 + test-only IT，只判别不实施）

- 日期：2026-09-29；开工 HEAD `291e686a663e78ec7a8d1faeec14b9aeb669bca8`（与任务书一致，作业期间未被其他会话推进；开工 `git rev-list --left-right --count origin/main...main` = `0	1`）
- 机器摘要：`docs/perf/data/exp-outbox-relay-pool-concurrency-scaling.json`；原始输出：`docs/perf/data/raw/task161-*`（ignored）

## 一句裁决（预注册，未事后放宽）

**S_prod(2)@J=0 = 1.8567、S_prod(4)@J=0 = 3.1538、S_prod(8)@J=0 = 5.6803（随 N 单调不减），S_prod(4)@J=8 = 1.7756（B 组自证门 `getThreadsAwaitingConnection()` 轮内最大值 = 2 > 0，压力真实）→ 落入预注册第三支「证据不足」**：
第一支要求 `S_prod(4)@J=8 ≥ 1.8`，实测 **1.7756 差 0.0244 未达**；第二支要求任一 ≤ 1.3，两值均未触发；1.7756 介于 1.3 与 1.8 之间 ⇒ **只报数字与噪声，不凑结论、不外推**。

**本轮零生产行为变化、零已测收益**：未改任何生产代码、任何默认值（`relay-send-concurrency` 保持 1、`application.yml` 一字未动）；**未覆盖 (a) 并发 `syncSend`/broker 吞吐**（未起四服务、未跑负载、未连 RocketMQ）；J 是**池占用代理（occupancy proxy）**，不是消费者行为模型。**不得**据此在生产开启 `relay-send-concurrency > 1`，**不得**把本轮数字与 TASK-152 的 18.0 ms/行 或 TASK-156 的 S(N) 并列成「优化前后」（不同实例/不同装配，判别量只有同实例同装配内的 S_prod(N)）。

## 这一轮要回答的唯一问题与预登记（照抄任务书，未放宽）

TASK-156 实测 S(2)=1.8612 / S(4)=3.3066 / S(8)=5.7056，但它用的是**每线程一条独立 `DriverManager` 自动提交连接（完全无连接池）**。生产侧硬事实：`application.yml` 的 hikari 段只设了 `initialization-fail-timeout: -1`，没有 `maximum-pool-size` ⇒ 生效值 Hikari 默认 **10**；N 个 relay worker 与 32~40 个消费线程争同一个 10 连接池。**唯一问题：S(N) 经过一个真实的 10 连接池后还剩多少？**

预登记：本轮结果**即使全绿也不足以翻默认值**。指导侧用仓库自己的实测数字做过的算术推演（Level B，非新测量）与四条假设随结果一并登记：

| 配置 | 锁内 | 周期 | 净投递 |
|---|---|---|---|
| 5000ms, N=1（现状） | 1312ms | 6315ms | **14.58 行/s**（TASK-145 实测） |
| 5000ms, N=8 | 246+183=429ms | 5429ms | **≈17.0 行/s（仅 +16%）** |
| 500ms, N=1 | 1452ms | 1941ms | **37.15 行/s**（TASK-145 实测） |
| 500ms, N=4 | 638ms | 1138ms | **≈63 行/s（+70%）** |
| 500ms, N=8 | 506ms | 1006ms | **≈72 行/s（+93%）** |

四条算术假设：① `syncSend` 不并行（保守）；② 满批 rows=100；③ **S(N) 能从 IT 迁移到生产（正是本轮要验的）**；④ 周期 = interval + 锁内。⇒ **默认 5000ms 下并发单独只值 +16%**；可交付组合是「interval 下调 + 并发」，不是并发单独。

## 预注册三支（不得事后放宽）

- **第一支**：`S_prod(4)@J=0 ≥ 2.0` **且** `S_prod(4)@J=8 ≥ 1.8` ⇒ 池不是硬约束。**实测 3.1538 ≥ 2.0 成立、1.7756 ≥ 1.8 不成立（差 0.0244）→ 未触发。**
- **第二支**：任一 ≤ 1.3 ⇒ 池是硬约束。**两值均 > 1.3 → 未触发。**
- **第三支**：介于其间、或轮间噪声大、或 S_prod 非单调 ⇒ 证据不足。**1.7756 介于 1.3 与 1.8 之间 → 本支（只报数字与噪声，不凑结论、不外推）。**

## 环境与隔离（只读登记，未改）

- 既有 scratch 容器 `task131-scratch-mysql`（MySQL **8.0.46**、宿主 **13318**）：开工前为 `Exited (255)`（任务书记载），**只 `docker start`**、未 recreate / 未改配置 / 未删卷，启动后 `Up`、端口映射 `0.0.0.0:13318->3306`。
- 实例四项只读登记（IT 启动时经 `SHOW VARIABLES` 打印，`raw/task161-06-green-run.log` 的 `TASK161-EVIDENCE server.*` 行）：
  `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`log_bin=ON`、`version=8.0.46`（另记 `event_scheduler=ON`）。**未改任何 MySQL 配置/仪器、未重置任何计数器。**
- 只**新增** schema `task161_pool_scratch`（`sed 's/verify_db/task161_pool_scratch/g' sql/03-verify-db.sql`，机械改名；`raw/task161-02-schema-create.sql`；建库前既有 schema 列表见 `raw/task161-01-schemas-before.txt`）。IT 内**每条**直取连接硬校验 `SELECT DATABASE()` = 该 schema。**演示库 3307 一个字节都未写**（本任务全部连接都指向宿主 13318 的专用 schema）。
- 收尾状态：`task161_pool_scratch.verify_event_outbox` 留存 2000 行全 SENT（末轮硬判据的收尾态，明确标注的运行证据）。

## 方法（test-only IT，装配照 TASK-153/147/156 三模板）

`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java`（新，768 行；类名以 `IT` 结尾 ⇒ Surefire 默认不收集）：

- **装配**：真实 `HikariDataSource`——`setMaximumPoolSize(10)`（生产生效默认值）、`setPoolName("task161-pool-it")`、**其余全部 Hikari 默认**（未设 `connectionTimeout`/`minimumIdle`/`maximumLifetime` 等）；经该 DataSource 建真实 MyBatis `SqlSessionFactory`（MybatisConfiguration + `JdbcTransactionFactory`）；逐行走生产 `VerifyEventOutboxMapper#markSent` 的**Mapper 代理逐字 SQL**（`@Update` 注解运行时渲染：`UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`，**无自写 UPDATE**），每次调用 `openSession(true)` 从**同一个池**取还一条 autoCommit 连接（与生产 MyBatis-Spring 无事务时「一调用一提交」同语义）。池预热：等 `getTotalConnections()` 达到 10 再开始测量（Hikari 默认 `minimumIdle = maximumPoolSize = 10`，生产池稳态即常驻 10 条）。
- **切分**：先经生产 `selectPendingBatch(M, maxRetry)` 取「已取列表」，再按列表下标 `i % N` 切不重不漏 N 份（与 TASK-160 生产代码 `shards.get(i % sendConcurrency).add(batch.get(i))` 同一切分法）。种子 M=2000 行 PENDING（id 连续 1..2000）；每轮起跑前一条批量 UPDATE 重置 PENDING（重置成本不计入臂墙钟、不落入计数窗口）。
- **臂与轮**：M=2000 行/轮、每臂 3 轮。**A 组（无池压力，J=0）**：N ∈ {1,2,4,8}；**B 组（池占用压力，证伪臂）**：N=4、J=8——从**同一个**池额外签出 8 条连接并**整轮持有不放**（空闲即可），可用连接 10−8=2 < N=4，强制排队。**J 必须明确为「池占用代理（occupancy proxy）」，不是消费者行为模型**；不得声称它等价于 32~40 个真实消费线程的负载。
- **标度量**：`S_prod(N) = 中位吞吐(N) / 中位吞吐(1)`，以 A 组 N=1 为基；B 组 `S_prod(4)@J=8` 以同一基线相除（预登记裁决要求的唯一跨组比值）。**除此之外 A 组与 B 组之间不得互相换算或相减**；绝对吞吐不在判据内。
- **每窗口硬判据（12 + 3 = 15 个窗口，全过才算数）**：① `SHOW GLOBAL STATUS` 的 `Com_update` 增量**精确 = 2000**（前值在重置/取列表/占用签出之后、barrier 之前取；后值在最后一个线程 join 之后、任何收尾 SELECT 之前取）；② 窗口内 `Com_insert` = `Com_delete` = 0；③ 收尾 `SENT=2000`、`PENDING=0`（在后值读取之后核对）；④ 会话门（本轮口径）：除本 IT 自己的池连接（≤10，`HikariPoolMXBean#getTotalConnections()` 实读）+ 1 条控制连接外，不得有任何外来会话——按 P_S `NAME='thread/sql/one_connection'` 计数 == 池连接实读 + 1，并用 `SHOW PROCESSLIST` 交叉核对（非 Daemon、非自身会话全部落在 scratch 库上）；**排除** `event_scheduler` 与 `compress_gtid_table` 两条系统 Daemon 线程（本实例 `TYPE='FOREGROUND'` 原始计数含它们，实测），FOREGROUND 原始计数照记为证据。
- **池指标（直接证据）**：采样线程（5ms 间隔）记录 `getActiveConnections()`/`getIdleConnections()`/`getTotalConnections()`/`getThreadsAwaitingConnection()` 的**轮内最大值**。**B 组自证门**：`getThreadsAwaitingConnection()` 轮内最大值必须 > 0，否则该臂作废重做，不得据以裁决第一支；A 组 N=1 应为 0。口径（TASK-146）：池指标是池级聚合、**不可**配对单次调用——只证明有无排队，不拆解单行 `markSent` 内部构成，不据此命名 fsync/锁/纯 SQL 占比。

## 直接读数（主证据 run：`raw/task161-06-green-run.log`，rc=0）

| 组 | N | 3 轮墙钟 (ms) | 中位墙钟 (ms) | 聚合吞吐 (行/s) | S_prod | 轮内 awaitMax | 轮内 activeMax/idleMax/totalMax | Com_update 增量/轮 | 收尾 SENT/PENDING |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A (J=0) | 1 | 19130.004 / 19140.740 / 18443.851 | 19130.004 | 104.55 | 1.0000 | 0 | 1/10/10 | 2000/2000/2000 | 2000/0 |
| A (J=0) | 2 | 10662.907 / 10303.397 / 10153.521 | 10303.397 | 194.11 | 1.8567 | 0 | 2/10/10 | 2000/2000/2000 | 2000/0 |
| A (J=0) | 4 | 6515.383 / 6065.684 / 5779.127 | 6065.684 | 329.72 | 3.1538 | 0 | 4/10/10 | 2000/2000/2000 | 2000/0 |
| A (J=0) | 8 | 3367.783 / 3462.659 / 3318.935 | 3367.783 | 593.86 | 5.6803 | 0 | 8/10/10 | 2000/2000/2000 | 2000/0 |
| B (J=8) | 4 | 10464.313 / 11509.764 / 10773.543 | 10773.543 | 185.64 | 1.7756 | **2** | 10/2/10 | 2000/2000/2000 | 2000/0 |

- 轮间极差/中位（噪声口径）：A N=1 **3.64%**、N=2 **4.94%**、N=4 **12.14%**、N=8 **4.27%**；B N=4 J=8 **9.70%**。
- **A 组读数解读（只描述、不外推）**：J=0 各臂 `awaitMax` 全 0、池从未达到 10 条并发在用（N ≤ 8 < 10）⇒ 本轮在**无池占用压力**下未观察到池对 S_prod 的削减；A 组 S_prod 单调不减。
- **B 组读数解读（只描述、不外推）**：J=8 占满 8 条后可用连接 2 < N=4，`awaitMax=2` 证明真实排队；S_prod(4) 由 3.1538（J=0）降到 1.7756（J=8）——**该降幅只作本臂数字记录，A/B 之间不得换算或相减**，且 1.7756 落入预注册「证据不足」区间。
- **算术事实（不构成裁决）**：`S_prod(4)@J=8 = 1.7756` 与第一支门限 1.8 的差为 **0.0244**（1.36%）；按预注册纪律**不得**以「接近」为由放宽或改判。

## 交叉校验（防假绿/防假红，全部通过）

- **15 个窗口**（A 组 4 臂 × 3 轮 + B 组 1 臂 × 3 轮）`Com_update` 增量全部**精确 = 2000**；窗口内 `Com_insert`/`Com_delete` 增量全 0；收尾 `SENT`=2000、`PENDING`=0；会话门 15/15 干净（客户会话 = 池连接实读 + 1 = 11，FOREGROUND 原始计数 13 含 2 条系统 Daemon，processlist 交叉核对非 Daemon 非自身会话全在 scratch 库）。
- **B 组自证门**：`getThreadsAwaitingConnection()` 轮内最大值 3/3 轮 = **2 > 0**，J=8 真实造成排队 ⇒ 该臂有效。**A 组 N=1** `awaitMaxOverall` = **0**（单 worker 串行取还连接无排队，符合预期）。
- **变异红 rc=1**：临时注入「shard 0 漏标首行」变异（diff 12 行，`raw/task161-07-mutation.diff`）→ **既有断言自己翻红**：
  `group=A j=0 arm=1 round=1：Com_update 增量必须精确 = M（每行恰好一次单行自动提交 UPDATE，偏离 = harness 缺陷，不得报告该臂标度）；实测=1999 ==> expected: <2000> but was: <1999>`
  （`raw/task161-07-mutation-red.log`；`mutation_run_rc=1`）→ 证明 IT 非空过。
- **还原复绿 rc=0**：`cp` 备份回写 + `cmp` rc=**0**（字节一致，`raw/task161-07-it-pristine-backup.java`；恢复后变异标记 0 处）→ 独立全量重跑 rc=0（`raw/task161-08-restore-green.log`：S_prod(2)=1.6850 / (4)=3.2883 / (8)=2.8965、S_prod(4)@J=8=1.7794）。该复绿 run 的 A N=8 臂三轮墙钟分别为 6696/8766/3554 ms（轮间极差/中位 78%），明显偏离主 run 的 ~3.4s 量级且未与任何判据读数相关；为表征该异常再跑第三组独立全量（`raw/task161-08b-restore-green2.log`，rc=0：S_prod(2)=1.6946 / (4)=3.1594 / (8)=5.2317、S_prod(4)@J=8=1.7170）。
- **跨 run 对照（只作稳定性披露，不构成裁决）**：三组独立全量的 S_prod(2)=1.8567/1.6850/1.6946、S_prod(4)=3.1538/3.2883/3.1594 高度一致；S_prod(8)=5.6803/2.8965/5.2317 中第 2 组明显偏低（与上述 N=8 墙钟异常同源，来源未定位——第 2 组的 N=8 三轮墙钟两慢一快 6696/8766/3554ms，与「某轮被机器噪声整体拖慢」形态一致但未取到 stall 证据）；S_prod(4)@J=8=1.7756/1.7794/1.7170 三组全部落在预注册区间 (1.3, 1.8) 内 ⇒ **第三支裁决对全部三组独立 run 稳健**。三组的 15/15 窗口硬判据（Com_update=2000、SENT=2000/PENDING=0、会话门、B 组 awaitMax>0）全部通过。
- **缺变量 skipped rc=0**：不设 `TASK161_IT_*` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1`、BUILD SUCCESS，**按口径不记为真库通过**（`raw/task161-05-skip-run.log`）。
- **常规套件档 rc=0（收口，`.mvn/maven.config` 删除后）**：`--mode=offline test` → 七模块 **36/41/33/103/120/59/10**、Skipped 全 0，与开工基线逐位一致（新 `*IT` 不被默认收集）。
- **静态门**：`--mode=offline --static=verify-service` → rc 与 checkstyle 违规数见 handoff G6（**不得新增**口径）；spotbugs/pmd 被 checkstyle 阻断在前 = **未覆盖**，不得写成通过。

## 调用通道（照 TASK-156 同款，可逐字复现）

`scripts/verify/mvn-verify.sh` 的 `--it` 分支**结构上跑不到** verify-service 的 IT（L36 硬编码 `IT_CLASSES="LeaderboardDailySummaryMapperMysqlIT,LeaderboardL2RedisRoundTripIT,RocketMqBrokerRoundTripIT"` + `IT_MODULE=leaderboard-service`）。本任务通道 = 仓库根 `.mvn/maven.config`（Maven 自动读取，不出现在「命令全文」），**原文两行**：

```
-Dtest=VerifyOutboxRelayPoolConcurrencyScalingMysqlIT
-Dsurefire.failIfNoSpecifiedTests=false
```

原文与跑前跑后 `git status --porcelain .mvn` 快照存证 `raw/task161-04-maven-config-evidence.txt` 与 `raw/task161-09-maven-config-removed.txt`；**任何 `git add`/`git commit` 之前已删除**（删除后 `ls` rc 与空 status 见 G8 原文）；删除后常规套件回基线数字串（见上）。未用 `MAVEN_OPTS`、未改 `mvn-verify.sh`、未改任何 pom、未裸用 mvn。环境变量 `TASK161_IT_URL/USER/PASSWORD` 在运行时同一 shell 会话内设置（运行器原文 `raw/task161-run-*.sh`）。

## 退出码与门槛汇总

| 档 | 结果 | rc |
| --- | --- | --- |
| 缺变量 skipped | Tests run: 1, Skipped: 1（不记真库通过） | 0 |
| 绿跑（主证据，A 组 4 臂 + B 组 1 臂 × 3 轮） | 15 窗口硬判据全过；S_prod(2)=1.8567 / (4)=3.1538 / (8)=5.6803；S_prod(4)@J=8=1.7756 ⇒ 第三支 | 0 |
| 变异红 | Com_update 实测=1999≠2000 断言翻红 | 1 |
| 还原复绿 | cmp 字节一致；两次独立全量重跑各 rc=0（S_prod 相互印证，见跨 run 对照） | 0 |
| 常规套件档（maven.config 删除后） | 七模块 36/41/33/103/120/59/10、Skipped 全 0 | 0 |
| 静态门（verify-service） | checkstyle ≤ 867 口径；spotbugs/pmd 未覆盖 | 见 handoff |
| `git diff --check`（工作树与暂存） | 无空白错误 | 0 |
| `mailbox-contract.sh --open TASK-161`（在途） | rc=1 为预期（过冲仅既有脏项，逐条说明见 handoff G9） | 1 |
| `mailbox-contract.sh`（收口后无参） | 契约校验通过 | 0 |
| `--mode=online` / CI | 未跑，**未达外部门槛** | — |

## 对预登记假设 ③（S(N) 可迁移）的回答（边界内）

本轮给出的是**有界答案**：在**无池占用压力**（J=0，N ≤ 8 < 10 连接）下，池未构成对 S_prod 的可观察削减（`awaitMax` 全 0、S_prod 仍随 N 单调不减 1.8567 / 3.1538 / 5.6803）；但在**池占用压力**（J=8，可用 2）下 S_prod(4) 记录为 1.7756，**落入预注册「证据不足」区间**，且 J 只是**池占用代理**、不是 32~40 个真实消费线程的负载 ⇒ **假设 ③ 未被完整回答**：本轮的池内装配与占用代理都不足以闭合「S(N) 能否迁移到生产（含 Redisson 全局 `tryLock(0)` 单跑与消费者共享池）」的问题；**不外推、不换算、不并列**（不得与本轮 A 组数字相减，更不得与 TASK-152/156 数字并列成「优化前后」）。

## 不得推出（逐条）

- **未覆盖 (a)**：并发 `syncSend`/broker 吞吐——未起四服务、未跑负载、未连 RocketMQ，**零信息**。
- J 是**池占用代理（occupancy proxy）**，不是消费者行为模型；不得声称等价于 32~40 个消费线程的真实负载。
- **零已测收益**：本轮零生产行为变化、零已测收益；不得据此在生产开启 `relay-send-concurrency > 1`。
- 不得把本轮数字与 TASK-152 的 18.0 ms/行（演示实例 3307、不同窗）或 TASK-156 的 S(N)（DriverManager 无池装配）**并列成「优化前后」**；判别量只有同实例同装配内的 S_prod(N)。
- 不改任何默认值（relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit`），不翻案 TASK-153/154，不改写 TASK-152/156 的任何数字；不实施、不外推。
- 实测边界：单容器单实例、M=2000、3 轮/臂、N ≤ 8、`i % N` 切分、J=8 单一占用档；绝对值仅本机本窗口有效；池指标是池级聚合，不配对单次调用。
