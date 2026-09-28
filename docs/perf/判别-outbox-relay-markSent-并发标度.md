# 判别：outbox relay `markSent` 并发标度（TASK-156，方向 B；scratch 真库 + test-only IT，只判别不实施）

- 日期：2026-09-28；开工 HEAD `7eb609501730729d7f0a69f2bd31d4df88a04ead`（与任务书一致，作业期间未被其他会话推进）
- 机器摘要：`docs/perf/data/exp-outbox-relay-concurrency-scaling.json`；原始输出：`docs/perf/data/raw/task156-*`（ignored）

## 一句裁决（预注册，未事后放宽）

**S(2)=1.8612、S(4)=3.3066、S(8)=5.7056，随 N 单调不减、轮间噪声小 → 落入预注册第一支「并发标度成立」**：
在逐行语义完全不变（每行仍各自一次自动提交、各自 PENDING→SENT、各自 `sent_at`）的前提下，把 `markSent` 从单线程改为
N 路并发（N ∈ {1,2,4,8}、`id % N` 互不相交分片），聚合投递吞吐随 N 显著上升——InnoDB 组提交（binlog group commit，
`innodb_flush_log_at_trx_commit=1` + `sync_binlog=1`）确实能摊薄「每行一次持久化往返」的成本。
**该结论仅是必要条件成立**：它只证明 DB 提交层存在并发摊薄空间，**不构成**分区 relay 的实施授权，**不宣称**生产吞吐收益，
**不得**据此翻案 TASK-153（批末统一标记 SENT = NO-GO）或改写 TASK-152 数字。

## 判据与预注册三支（照抄任务书，未放宽）

- S(N) = throughput(N) / throughput(1)（同实例、同 M=2000、各臂 3 轮中位）。
- S(8) ≥ 2.0 且 S 随 N 单调不减（容差内）→ 并发标度成立（仅必要条件）；**实测 S(8)=5.7056，1.00 < 1.8612 < 3.3066 < 5.7056 严格单调** → 本支。
- S(8) ≤ 1.2 → 每行提交成本是硬墙（未触发）。
- 1.2 < S(8) < 2.0 或噪声大/非单调 → 证据不足（未触发；轮间极差/中位 = 2.65%/4.70%/3.91%/8.73%，单调性无歧义）。

## 环境与隔离（只读登记）

- 既有 scratch 容器 `task131-scratch-mysql`（MySQL **8.0.46**、宿主 **13318**），本次开工时为 `Up` 状态（未被杀、未重启）。
  只**新增** schema `task156_concurrency_scratch`（`sql/03-verify-db.sql` 机械改名生成，见 `raw/task156-02-schema-create.sql`）；
  IT 内每条连接硬校验 `SELECT DATABASE()` = 该 schema，绝不触碰演示库。
- **组提交相关配置（只读登记，未改任何配置/仪器、未重置计数器、未重启容器）**：
  `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`log_bin=ON`、`event_scheduler=ON`。
  ——这正是组提交摊薄空间最大的持久档：每个 autocommit 提交都要 redo + binlog 刷盘，并发提交可共享 group commit 刷盘。
- 跑前跑后既有 scratch schema 行数**逐字节一致**（task142=104 / task147=6 / task148=6+0 / task149=130 / task153=3 /
  task154=7，`raw/task156-03-rowcounts-before.txt` vs `task156-11-rowcounts-after.txt`）；未起任何 Java 服务、未跑
  c100×2000、未向演示库 3307 写入。

## 调用通道（补 TASK-154 的台账缺口——可逐字复现）

`scripts/verify/mvn-verify.sh` 对未知参数 exit 2、**无任何 `-D` 透传**，其 `--it` 分支硬编码
`-pl leaderboard-service -am -Dtest=LeaderboardDailySummaryMapperMysqlIT,...` 并忽略 `--pl`——**无法**运行 verify-service
的任何 IT；父 pom 与 verify-service pom 均无 surefire 配置，`*IT` 默认不被收集。因此点名运行本任务 IT 必须经带外通道。

**本任务指定通道 = 仓库根 `.mvn/maven.config`（Maven 自动读取，不出现在「命令全文」里）**，原文两行：

```
-Dtest=VerifyEventOutboxRelayConcurrencyScalingMysqlIT
-Dsurefire.failIfNoSpecifiedTests=false
```

- 该文件**不在** `.gitignore` 内（`git check-ignore` rc=1），以未跟踪项出现在 `git status`（`?? .mvn/`）——有意保留可审计性：
  跑前 `git status --porcelain .mvn` = `?? .mvn/`，跑后删除，`git status --porcelain .mvn` 空输出（`raw/task156-04-maven-config-evidence.txt`、
  `raw/task156-09-maven-config-removed.txt`）。**任何 git add/commit 之前已删除，绝未提交。**
- 环境变量在同一 PowerShell 会话用 `$env:` 设置后调用（运行器脚本原文见 `raw/task156-run-green.ps1` 等）：

```powershell
$env:TASK156_IT_URL  = 'jdbc:mysql://127.0.0.1:13318/task156_concurrency_scratch?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:TASK156_IT_USER = 'root'
$env:TASK156_IT_PASSWORD = 'root'   # 与容器 MYSQL_ROOT_PASSWORD 一致（docker inspect 可查）
& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
```

- **常规套件档必须在 `.mvn/maven.config` 删除之后跑**：`--mode=offline --pl verify-service test` 回到
  common **36** / verify-service **110** / skipped **0**、rc=0，与基线同数（`raw/task156-10-regular-suite.log`）——证明新
  `*IT` 不被默认收集、通道删除后基线未被收窄污染。
- 未用 `MAVEN_OPTS`/`MAVEN_ARGS` 当通道；未改 `mvn-verify.sh` 或任何 pom。

## 方法（test-only IT，镜像 TASK-154 口径）

`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxRelayConcurrencyScalingMysqlIT.java`（新）：

- SQL 逐字取自生产 `VerifyEventOutboxMapper#markSent` 的 `@Update` 注解运行时渲染（`#{id}` → `?`）：
  `UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`（不增删 WHERE）。
- 种子 M=2000 行 PENDING（合成 event_id/payload，自增 id 连续 1..2000）；每轮起跑前一条批量 UPDATE 重置 PENDING
  （重置成本不计入臂墙钟、不落入计数窗口）。
- 臂 N ∈ {1,2,4,8} × 3 轮：第 N 臂 N 个线程、每线程一条独立 `DriverManager` 连接（autoCommit=true，与生产
  「一调用一提交」同语义），按 `id % N` 领互不相交行集（避免同行锁竞争混淆组提交效应）；CyclicBarrier 同步起跑、
  `join` 收尾；臂总墙钟 = 起跑到全部线程完成。
- **计数窗口（硬前置）**：每轮 `Com_update` 前值在重置 PENDING 之后、barrier 之前取；后值在最后线程 join 之后、
  任何收尾 SELECT 之前取；一并记录窗口内 `Com_insert`/`Com_delete`（应 0）。`Com_*` 只用 `SHOW GLOBAL STATUS` 读
  （本实例 `performance_schema.global_status` 不列 Com_update/Com_insert，TASK-154 已实测）。
- **会话门（实测订正）**：本实例 P_S 的 `TYPE='FOREGROUND'` 计数**含两条系统 Daemon 线程**
  （`thread/sql/event_scheduler`、`thread/sql/compress_gtid_table`，首次绿跑前实测发现，见 `raw/task156-06a-gate-false-positive.log`），
  故客户会话按 `NAME='thread/sql/one_connection'` 计数 == N+1（与 TASK-154 的连接线程判据同源），
  并用 `SHOW PROCESSLIST`（排除 Daemon 与本门自身连接）交叉核对 == N；FOREGROUND 原始计数与完整 dump 照记为证据。
  这是仪器语义订正而非放宽——外来客户会话仍会被两道核对同时抓住。

## 直接读数（主证据 run：`raw/task156-06-green-run.log`，rc=0）

| N | 3 轮总墙钟 (ms) | 中位墙钟 (ms) | 聚合吞吐 (行/s) | S(N) | 单行墙钟中位 (ms) | Com_update 增量/轮 | 收尾 SENT/PENDING |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 19671.172 / 19152.815 / 19550.559 | 19550.559 | 102.30 | 1.0000 | 9.408 | 2000/2000/2000 | 2000/0 |
| 2 | 10407.698 / 10504.441 / 10901.028 | 10504.441 | 190.40 | 1.8612 | 10.423 | 2000/2000/2000 | 2000/0 |
| 4 | 5912.554 / 6115.075 / 5884.035 | 5912.554 | 338.26 | 3.3066 | 11.527 | 2000/2000/2000 | 2000/0 |
| 8 | 3552.383 / 3253.402 / 3426.585 | 3426.585 | 583.67 | 5.7056 | 13.057 | 2000/2000/2000 | 2000/0 |

- 形态解读（只描述、不命名）：N 增大时**单行墙钟上升**（9.4 → 13.1 ms）而**聚合吞吐仍近似线性上升**——并发提交在
  服务端刷盘点上排队共享 group commit，单行变慢、整批变快，正是组提交摊薄的特征形态。
- **N=1 合理性**：单行中位 9.4 ms 落在该持久档（每提交两次 fsync）单行自动提交 UPDATE 的合理量级，无 <1ms 异常；
  **不得**把本任务 N=1 的 9.4 ms/行 与 TASK-152 的 18.0 ms/行 相提并论（不同实例不同窗），判别量只有同实例内的 S(N)。
- digest 语句计数增量（若可读，仅记录不作判据）：N=1/2 每轮恰好 2000；N=4/8 低于 2000（总计 5915/5584）= digest
  汇总表行淘汰的仪器表现；硬判据是 `Com_update` 精确增量。

## 交叉校验（防假绿/防假红，全部通过）

- **12 个窗口**（4 臂 × 3 轮）`Com_update` 增量全部**精确 = 2000**（每行恰好一次单行自动提交 UPDATE，无重复无丢失）；
  `Com_insert`/`Com_delete` 增量全 0（窗口无污染）；每轮收尾 `SENT`=2000、`PENDING`=0；会话门 12/12 干净。
- **变异红**：临时注入「shard 0 漏标最后一行」变异（diff 见 `raw/task156-07-mutation.diff`）→ 既有断言自己翻红：
  `arm=1 round=1：Com_update 增量必须精确 = M …；实测=1999 ==> expected: <2000> but was: <1999>`，**rc=1**
  （`raw/task156-07-mutation-red.log`）→ 证明 IT 非空过。
- **还原复绿**：字节还原（`cmp` rc=0，`raw/task156-07-it-pristine-backup.java`）后全量重跑 **rc=0**，且 S(N)=
  1.7527 / 3.1688 / 5.5858 与主 run 相互印证（`raw/task156-08-restore-green.log`）。
- **缺变量 skipped**：不设 `TASK156_IT_*` → `Tests run: 1, Skipped: 1`、**rc=0**，按口径**不记为真库通过**
  （`raw/task156-05-skip-run.log`）。
- 首次绿跑尝试的两处 harness 缺陷已留档：会话门把本实例 2 条系统 Daemon 误判为外来会话（`raw/task156-06a-*.log`）、
  barrier 计时未接线致墙钟无效（`raw/task156-06b-*.log`）——两轮均无有效读数产出，订正后重跑取数；编译期两次失败
  留档 `raw/task156-05a/05b-*.log`。这些都不是用例红（变异红另有专门取证）。

## FIFO 反例（只读核查，不实现）

两个消费者均**不存在同 recordId 事件顺序依赖**：

- `VerifyEventConsumer`（SUBMITTED）：eventId Redis SETNX 去重 + `verifyService.verify(recordId)` 以
  `verification_result` 主键幂等兜底——校验语义与事件先后无关；重投经 MQ 原生 `RECONSUME_LATER`，顺序本就无保证。
- `LeaderboardEventConsumer`（VERIFIED/REJECTED，含改判）：`applyVerified` **读记录现状态**做 level-based 门
  （非 pass 一律不入榜——乱序迟到的 VERIFIED 会被现状态拦下），锚点行乐观迁移（INSERT IGNORE 首建 / ACTIVE↔ROLLED_BACK
  双向各恰好一次）保证只加/扣一次，`rollbackOnRejected` 对无锚点行跳过；同 recordId 操作由 Redisson 互斥锁串行化；
  且消费端本就是 `MessageListenerConcurrently` **并发消费**——现网语义从不假设「投递按 id 全局 FIFO」。
- **对未来分区 relay 的约束（记录备查）**：消费端幂等不自动等于授权改 relay；若未来设计引入顺序假设（或审计要求
  保序），必须**按 recordId 分区**（而非 round-robin / `id % N`）以保同 recordId 事件有序。本任务未据此改任何代码。

## 退出码与门槛汇总

| 档 | 命令 | 结果 | rc |
| --- | --- | --- | --- |
| 缺变量 skipped | 同通道、不设 `TASK156_IT_*` | Tests run: 1, Skipped: 1（不记真库通过） | 0 |
| 绿跑（主证据） | `& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` + maven.config 通道 | 4 臂 × 3 轮全判据过、S(8)=5.7056 | 0 |
| 变异红 | 同上 + 漏标一行变异 | Com_update 1999≠2000 断言翻红 | 1 |
| 还原复绿 | 同上（字节还原） | S(8)=5.5858 相互印证 | 0 |
| 常规套件档 | maven.config 删除后同命令 | common 36 / verify-service 110 / skipped 0（基线同数） | 0 |
| `git diff --check` | 工作树 + 暂存 | 无空白错误 | 0 |
| `mailbox-contract.sh`（无参数） | 两笔提交后 | 契约校验通过 | 0 |
| `--mode=online` / CI | 未跑 | **未达外部门槛** | — |

## 不得推出（逐条，照任务书）

- scratch 真库 + test-only `DriverManager` IT ≠ 生产 relay（Spring/Hikari + Redisson 全局 `tryLock(0)` 单跑）。
- 正标度结果只是**必要非充分**——本任务隔离的是 `markSent` 的 DB 提交成本（TASK-146 测得约占锁内处理段 80%），
  **未测**并发 `syncSend`/RocketMQ、未测 relay 锁改造、未测多实例竞争。
- 不得据此改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 任何默认值。
- 不得宣称生产延迟或吞吐收益。
- 不得翻案 TASK-153（批末统一标记 SENT = NO-GO）；TASK-152 的 18.0 ms/行、73.93% 未改写。
- 消费端幂等不自动等于授权改 relay。
- **实测边界补充**：N ≤ 8、M=2000、3 轮、单容器、连接为裸 `DriverManager`（无池）、分片按 `id % N`（生产分区若实施
  应按 recordId，见 FIFO 节）；digest 计数在 N≥4 受汇总表行淘汰影响只作旁证；绝对值仅本机本窗口有效。
