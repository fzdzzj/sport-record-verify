# TASK-156 回传：判别 outbox relay `markSent` 并发标度（scratch 真库 + test-only IT，只判别不实施）

## 结论

开工 HEAD `7eb609501730729d7f0a69f2bd31d4df88a04ead`（与任务书一致，作业期间未被其他会话推进；结束时 `git rev-list --count origin/main..main` = 42，即开工 40 笔 + 本任务两笔提交）。**裁决：落入预注册第一支「并发标度成立」（仅必要条件成立）**——
**S(2)=1.8612、S(4)=3.3066、S(8)=5.7056，随 N 单调不减（1.00 < 1.86 < 3.31 < 5.71，严格），轮间极差/中位 2.65%/4.70%/3.91%/8.73%**。
在逐行语义完全不变（每行各自一次自动提交、各自 PENDING→SENT、各自 `sent_at`）的前提下，把 `markSent` 从单线程改为 N 路并发（`id % N` 互不相交分片），聚合吞吐 102.30 → 190.40 → 338.26 → 583.67 行/s（M=2000、各臂 3 轮中位）——该 scratch 实例（`innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` + `log_bin=ON`）的 InnoDB/binlog 组提交确实摊薄「每行一次持久化往返」的成本，形态为单行墙钟中位随 N 上升（9.408 → 10.423 → 11.527 → 13.057 ms）而聚合吞吐近似线性上升。**各臂 ms/行 中位：N=1 9.408 / N=2 10.423 / N=4 11.527 / N=8 13.057**。
**只裁决不实施**：未改任何生产 Java/SQL/YAML/运行默认值，未起四服务，未跑 c100×2000；未翻案 TASK-153（批末统一标记 SENT = NO-GO 原样），未改写 TASK-152 数字（18.0 ms/行、73.93% 原样）；本任务不实现分区 relay、不宣称生产吞吐收益。报告 `docs/perf/判别-outbox-relay-markSent-并发标度.md`，机器摘要 `docs/perf/data/exp-outbox-relay-concurrency-scaling.json`。

**N=1 不可比声明**：本任务 N=1 的 9.408 ms/行 是 scratch 实例（宿主 13318）本窗口的读数，**不得**与 TASK-152 演示实例（宿主 3307）的 18.0 ms/行 相提并论（不同机不同窗）；判别量只有同实例内的 S(N)。

## 起点核对与隔离

- HEAD 与任务书一致；既有脏项（6 个归档删除侧 `spec/changes/{wire-verify-outbox,adopt-native-mq-retry}/*`、`spec/changes/archive/adopt-native-mq-retry/` 与 `.../wire-verify-outbox/`、`spec/changes/add-verify-degrade-status-index/` 等）与开工快照逐字一致、**未触碰**（属 TASK-157）；未 stash、未 `git add -A`/`git add .`（逐文件 `git add <path>`）、未 push、未建 PR。
- 隔离环境：既有 `task131-scratch-mysql`（MySQL **8.0.46**、宿主 **13318**）开工时为 `Up`（未被外部引擎所杀、未重启/重建/改配置/删卷）。**只新增** schema `task156_concurrency_scratch`（`sed 's/verify_db/task156_concurrency_scratch/g' sql/03-verify-db.sql`，`raw/task156-02-schema-create.sql`；IT 内每条连接硬校验 `SELECT DATABASE()`）。
- 既有 scratch schema 行数**跑前跑后逐字节一致**（task142=104 / task147=6 / task148=6 / task148_spring=0 / task149=130 / task153=3 / task154=7；`raw/task156-03-rowcounts-before.txt` vs `raw/task156-11-rowcounts-after.txt`）。本任务 schema 收尾留存 2000 行全 SENT（明确标注的运行证据）。演示库 3307 零写入。

## 环境与组提交相关配置（只读登记，未改）

- `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`log_bin=ON`、`event_scheduler=ON`（IT 启动时经 `SHOW VARIABLES` 打印于 `raw/task156-06-green-run.log`，`TASK156-EVIDENCE server.*` 行；与 `raw/task156-01` 前置查询一致）。
- 组提交摊薄空间正是取决于该最严持久档：每提交 redo + binlog 双刷盘，并发提交可共享 group commit 刷盘——S(8)=5.71 与此机制自洽；**未改任何 MySQL 配置/仪器、未重置任何计数器**。
- 仪器侧实测发现（已写进 IT javadoc 与报告）：本实例 P_S `TYPE='FOREGROUND'` 计数**含两条系统 Daemon 线程**（`thread/sql/event_scheduler`、`thread/sql/compress_gtid_table`），故会话门按客户会话线程 `NAME='thread/sql/one_connection'` 计数 == N+1 并用 `SHOW PROCESSLIST` 排除 Daemon/自身交叉核对 == N；FOREGROUND 原始计数与完整 dump 照记为证据。

## 调用通道与 `.mvn/maven.config`（补 TASK-154 的台账缺口）

TASK-154 用了带外通道但台账未记载（其 `task154-08` 与 TASK-155 `task155-01`「命令全文」逐字相同而测试数不同），本任务补记如下，**可逐字复现**：

- `scripts/verify/mvn-verify.sh` 参数解析对未知参数 exit 2、**无 `-D` 透传**；`--it` 分支硬编码 `-pl leaderboard-service -am -Dtest=LeaderboardDailySummaryMapperMysqlIT,...` 并忽略 `--pl` → 无法运行 verify-service 的任何 IT；父 pom 与 verify-service pom 均无 surefire 配置 → `*IT` 默认不被收集（常规套件档 36/110/0 为证）。
- **本任务通道 = 仓库根 `.mvn/maven.config`（Maven 自动读取，不出现在「命令全文」）**，原文**两行、每行一个参数**：

```
-Dtest=VerifyEventOutboxRelayConcurrencyScalingMysqlIT
-Dsurefire.failIfNoSpecifiedTests=false
```

- 该文件**不在** `.gitignore` 内（`git check-ignore` rc=1），以 `?? .mvn/` 出现在 git status——有意保留可审计性：
  跑前 `git status --porcelain .mvn` = `?? .mvn/`、跑后删除为空输出，原文与两次快照存证 `raw/task156-04-maven-config-evidence.txt`（建文件时）与 `raw/task156-09-maven-config-removed.txt`（删除后 `ls` rc=2 + status 空）。**任何 git add/commit 之前已删除，绝未提交。**
- 环境变量在**同一 PowerShell 会话**用 `$env:` 设置后调用（运行器原文 `raw/task156-run-green.ps1`，即 `raw/task156-04` 同款通道）：

```powershell
$env:TASK156_IT_URL  = 'jdbc:mysql://127.0.0.1:13318/task156_concurrency_scratch?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:TASK156_IT_USER = 'root'
$env:TASK156_IT_PASSWORD = 'root'   # 容器 MYSQL_ROOT_PASSWORD，docker inspect 可查
& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
```

- **常规套件档在 `.mvn/maven.config` 删除之后跑**：`--mode=offline --pl verify-service test` → common **36** / verify-service **110** / skipped **0**、rc=0，与基线同数（`raw/task156-10-regular-suite.log`）——新 `*IT` 不被默认收集，通道删除后基线未被收窄。
- 未用 `MAVEN_OPTS`/`MAVEN_ARGS` 当通道；未改 `mvn-verify.sh`、未改任何 pom。

## 交叉校验直接读数（主证据 run `raw/task156-06-green-run.log`，rc=0）

| N | 3 轮总墙钟 (ms) | 中位墙钟 | 聚合吞吐 (行/s) | S(N) | 单行中位 (ms) | Com_update 增量/轮 | SENT/PENDING |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 19671.172 / 19152.815 / 19550.559 | 19550.559 | 102.30 | 1.0000 | 9.408 | 2000/2000/2000 | 2000/0 |
| 2 | 10407.698 / 10504.441 / 10901.028 | 10504.441 | 190.40 | 1.8612 | 10.423 | 2000/2000/2000 | 2000/0 |
| 4 | 5912.554 / 6115.075 / 5884.035 | 5912.554 | 338.26 | 3.3066 | 11.527 | 2000/2000/2000 | 2000/0 |
| 8 | 3552.383 / 3253.402 / 3426.585 | 3426.585 | 583.67 | 5.7056 | 13.057 | 2000/2000/2000 | 2000/0 |

- **12 个窗口**（4 臂 × 3 轮）`Com_update` 增量全部**精确 = 2000**（每行恰好一次单行自动提交 UPDATE）；窗口内 `Com_insert`/`Com_delete` 增量全 0；收尾 `SENT`=2000、`PENDING`=0（在后值读取之后核对）；会话门 12/12 干净（客户会话 N+1 + processlist 交叉 N）。
- 计数窗口纪律：前值在重置 PENDING 之后、barrier 之前取；后值在最后线程 join 之后、任何收尾 SELECT 之前取；重置/种子语句均落在窗口外。
- `Com_*` 只用 `SHOW GLOBAL STATUS` 读（本实例 `performance_schema.global_status` 不列 Com_update/Com_insert，沿用 TASK-154 实测）。
- digest 语句计数增量（若可读，仅旁证不作判据）：N=1/2 每轮恰好 2000；N=4/8 总量 5915/5584 < 6000 = digest 汇总表行淘汰的仪器表现，已如实记录。
- N=1 合理性：单行中位 9.408 ms 落在该持久档（每提交双 fsync）单行自动提交条件 UPDATE 合理量级，无 <1ms 异常、无报错。
- 还原复绿 run（`raw/task156-08-restore-green.log`）S(2)=1.7527 / S(4)=3.1688 / S(8)=5.5858，与主 run 相互印证。

## 变异与三档退出码（全部实测）

- **变异红 rc=1**：临时注入「shard 0 漏标最后一行」变异（`raw/task156-07-mutation.diff`，12 行）→ 既有断言自己翻红：`arm=1 round=1：Com_update 增量必须精确 = M…；实测=1999 ==> expected: <2000> but was: <1999>`（`raw/task156-07-mutation-red.log`）→ 证明 IT 非空过。
- **还原复绿 rc=0**：`cp` 备份回写 + `cmp` rc=0（字节一致，`raw/task156-07-it-pristine-backup.java`）→ 全量重跑 rc=0。
- **缺变量 skipped rc=0**：不设 `TASK156_IT_*` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1`、BUILD SUCCESS，**按口径不记为真库通过**（`raw/task156-05-skip-run.log`）。
- **常规套件档 rc=0**：maven.config 删除后 `--mode=offline --pl verify-service test` → common 36 / verify-service 110 / skipped 0（基线同数）。
- **harness 缺陷披露（均发生在取数前、无有效读数产出，订正后重跑）**：两次编译失败（`raw/task156-05a/05b-*.log`，泛型与缺失助手方法，属代码笔误非用例红）；会话门首版把本实例 2 条系统 Daemon 误判为外来会话（`raw/task156-06a-gate-false-positive.log`，由此实测订正门实现）；barrier 计时未接线致墙钟无效（`raw/task156-06b-broken-wallclock.log`，t0 恒 0，该轮读数作废）。订正后的 run 才是主证据。

## FIFO 反例核查结论（只读，未改任何代码）

- `verify-service/.../consumer/VerifyEventConsumer.java`（SUBMITTED）：eventId Redis SETNX 去重 + `verifyService.verify(recordId)` 以 `verification_result` 主键幂等兜底；失败走 MQ 原生 `RECONSUME_LATER` 重投（天然重排）。**无同 recordId 顺序依赖**。
- `leaderboard-service/.../mq/LeaderboardEventConsumer.java`（VERIFIED/REJECTED，含申诉改判）：`applyVerified` **读记录现状态**做 level-based 门（非 pass 一律不入榜 → 乱序迟到的 VERIFIED 被现状态拦下），锚点行乐观迁移（INSERT IGNORE 首建 / ACTIVE↔ROLLED_BACK 双向恰好一次）、`rollbackOnRejected` 对无锚点行跳过、同 recordId 由 Redisson 锁串行化；消费端本就 `MessageListenerConcurrently` **并发消费**——现网语义从不假设投递按 id 全局 FIFO。**无同 recordId 顺序依赖**。
- **结论：最强反例（失去按 id 全局 FIFO）在两个消费端均不成立**；依据是消费端幂等与现状态门使顺序无关。**但消费端幂等不自动等于授权改 relay**；若未来分区 relay 引入顺序假设或审计要求保序，必须**按 recordId 分区**（而非 round-robin / `id % N`）。本任务未据此改任何代码。
- 只读边界备注：逐行 markSent 保序使 `sent_at` 随 id 单调；并发化后该单调性不再成立——当前代码未见任何消费方依赖它（仅 relay 取批自身按 id 排序），记录备查。

## 判据与退出码汇总

| 档 | 结果 | rc |
| --- | --- | --- |
| 缺变量 skipped | Tests run: 1, Skipped: 1（不记真库通过） | 0 |
| 绿跑（主证据，4 臂 × 3 轮） | 12 窗口硬判据全过、S(8)=5.7056 | 0 |
| 变异红 | Com_update 1999≠2000 断言翻红 | 1 |
| 还原复绿 | cmp 0 字节一致；S(8)=5.5858 印证 | 0 |
| 常规套件档（maven.config 删除后） | common 36 / verify-service 110 / skipped 0 | 0 |
| `git diff --check`（工作树与暂存） | 无空白错误 | 0 |
| `mailbox-contract.sh --open TASK-156`（在途） | rc=1 为预期（见下） | 1 |
| `mailbox-contract.sh`（两笔提交后无参数） | 契约校验通过 | 0 |
| `--mode=online` / CI | 未跑，**未达外部门槛** | — |

JSON 校验（`exp-outbox-relay-concurrency-scaling.json`、三件套 `tasks.json`）解析 rc=0；两笔提交哈希：业务证据 `82d2a9c…`（完整 SHA 见 `git rev-parse 82d2a9c`）、台账 = 本提交（哈希由任务回传惯例由指导侧查验，`git log --oneline -2` 可见）。

## 实际改动清单（只改）

- **业务证据提交 `82d2a9c`（6 文件）**：
  `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxRelayConcurrencyScalingMysqlIT.java`（新）·
  `docs/perf/判别-outbox-relay-markSent-并发标度.md`（新）·
  `docs/perf/data/exp-outbox-relay-concurrency-scaling.json`（新）·
  本变更三件套 `spec/changes/prove-verify-outbox-relay-concurrency-scaling/proposal.md`（新）·
  `.../tasks.json`（新）· `.../specs/sport-record-verify/spec-delta.md`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-156/spec.md`（新，任务书原样入库）·
  `work/mailbox/tasks/TASK-156/handoff.md`（新，本文件）· `work/mailbox/PLAN.md`（改，仅 L4 一行实测订正）·
  `work/mailbox/后端优化机会总览-2026-09-26.md`（改，仅 §3-P2 追加一条 bullet + §4 第 3 条末尾追加一段，既有历史数字出现次数经 grep 计数**无一减少**：PLAN 五数字 2/1/1/1/5 持平、机会总览 3/3/2/3/4，其中 18.0 ms/行 2→3 为追加所致）。
- **数据库侧（不入库）**：只新增 schema `task156_concurrency_scratch` 及其 4 张表与 2000 行种子/SENT 证据。
- **临时文件（已删，未提交）**：仓库根 `.mvn/maven.config`（带外通道，原文与跑前跑后 status 已存证 raw；git add/commit 之前删除并以 `ls` rc=2 + `git status --porcelain .mvn` 空输出取证）。
- 原始输出在 ignored `docs/perf/data/raw/task156-*`（不提交）。既有脏项原样保留；未用 `git stash`、未 `git add -A`/`git add .`、未 push、未建 PR。

## 未覆盖 / 不得推出（逐条照任务书 + 实测边界）

- **未覆盖**：并发 `syncSend`/RocketMQ 发送侧（本任务只测 markSent 的 DB 提交层）；relay 锁改造与 Redisson 全局 `tryLock(0)` 语义；多实例竞争；生产 Spring/Hikari 装配（本 IT 为裸 `DriverManager`，无池）；c100×2000 真实负载与四服务；N>8、M≠2000、更多轮次；`--mode=online`/CI（未达外部门槛）；分区 relay 的实现与验收（本任务只判别）。
- **不得推出**：
  - scratch 真库 + test-only `DriverManager` IT ≠ 生产 relay（Spring/Hikari + Redisson 全局 `tryLock(0)` 单跑）；
  - 正标度结果只是**必要非充分**——本任务隔离的是 `markSent` 的 DB 提交成本（TASK-146 测得约占锁内处理段 80%），未测并发 `syncSend`/RocketMQ、未测 relay 锁改造、未测多实例竞争；
  - 不得据此改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 任何默认值；
  - 不得宣称生产延迟或吞吐收益；
  - 不得翻案 TASK-153（批末统一标记 SENT = NO-GO）；
  - 消费端幂等不自动等于授权改 relay；
  - 不得把本任务 N=1 的 9.408 ms/行 与 TASK-152 的 18.0 ms/行 相提并论（不同实例不同窗；判别量只有同实例内的 S(N)）。
- **实测边界补充**：N ≤ 8、M=2000、3 轮/臂、单容器、`id % N` 分片（生产分区若实施应按 recordId，见 FIFO 节）；digest 计数在 N≥4 受汇总表行淘汰影响仅作旁证；绝对值仅本机本窗口有效；组提交摊薄读数依赖该实例的最严持久档（flush=1/sync_binlog=1/binlog=ON），更宽松持久档下的标度未测。
