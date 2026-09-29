# TASK-161：判别「生产 Hikari 池（默认 10）下 relay 批内并发的 S_prod(N)」——廉价证伪，只判别不实施

## 这一轮要回答的唯一问题

TASK-156 实测 S(2)=1.8612 / S(4)=3.3066 / S(8)=5.7056，但它用的是**每线程一条独立 `DriverManager` 自动提交连接（完全无连接池）**；TASK-156 自己的 boundary 原文就写了「scratch 真库 + test-only DriverManager IT **≠** 生产 relay（Spring/Hikari + Redisson 全局 tryLock(0) 单跑）」。

而生产侧的硬事实（指导侧 2026-09-29 亲验）：`verify-service/src/main/resources/application.yml` 的 `hikari` 段**只设了 `initialization-fail-timeout: -1`，没有 `maximum-pool-size`** ⇒ 生效值是 Hikari 默认 **10**；同文件 `rocketmq.consumer.consume-thread-min: 32 / consume-thread-max: 40`。⇒ TASK-160 落地的 N 个 relay worker **与 32~40 个消费线程争同一个 10 连接的池**。

**唯一问题：S(N) 经过一个真实的 10 连接池后还剩多少？** 这是「并发是唯一未否掉的杠杆」这条推理链上**唯一未验的支柱**。它便宜（test-only IT，无需四服务、无需负载、无需 MQ），且**若答案是「剩不下」，就能省掉一整轮昂贵的负载实验**。

## 预登记：本轮结果**即使全绿也不足以翻默认值**（不得在收口时淡化）

指导侧用仓库自己的实测数字做过算术（TASK-145 满批 rows=100 的分段时间 + `fixedDelay` 语义「周期 = 固定等待 + 锁内」，保守假设 `syncSend` 不并行）：

| 配置 | 锁内 | 周期 | 净投递 |
|---|---|---|---|
| 5000ms, N=1（现状） | 1312ms | 6315ms | **14.58 行/s**（TASK-145 实测） |
| 5000ms, N=8 | 246+183=429ms | 5429ms | **≈17.0 行/s（仅 +16%）** |
| 500ms, N=1 | 1452ms | 1941ms | **37.15 行/s**（TASK-145 实测） |
| 500ms, N=4 | 638ms | 1138ms | **≈63 行/s（+70%）** |
| 500ms, N=8 | 506ms | 1006ms | **≈72 行/s（+93%）** |

⇒ **在默认 5000ms 下并发单独只值 +16%**（5000ms 固定等待本身占周期 79.2%），而到达率是 59~96 行/s。所以：**可交付的组合是「interval 下调 + 并发」，不是并发单独**；TASK-161 只负责回答「池会不会把 S(N) 吃掉」，**不负责也不得声称**任何吞吐收益。上表是**算术推演（Level B，基于已提交测量）不是新测量**，四个假设必须随结果一并登记：① `syncSend` 不并行（保守）；② 满批 rows=100；③ **S(N) 能从 IT 迁移到生产（正是本轮要验的）**；④ 周期 = interval + 锁内。

## 起点与基线（指导侧 2026-09-29 亲跑一手）

- 起点 HEAD = `291e686a663e78ec7a8d1faeec14b9aeb669bca8`。开工先 `git rev-parse HEAD` 核对，不一致**即停手回报**。
- `git rev-list --left-right --count origin/main...main` 开工应为 `0	1`（`origin/main` = `e1c96d6…`；未推的 1 笔是指导侧的 PLAN L4 订正）。**本任务不得 push。**
- **外部门槛已达成过（本笔之前）**：CI run `36525962432`（HEAD `e1c96d6…`）conclusion=`success`，`web` ✓16s / `build` ✓2m33s，**11 步全 success**，其中第 5 步 `Build and test` = `--mode=online verify` 是 TASK-160 并发代码的首次外部评判。
- offline 基线（指导侧本会话已 **6 次**逐位一致）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，七模块 **36 / 41 / 33 / 103 / 120 / 59 / 10**、Skipped 全 0（verify-service 的 120 含 TASK-160 新增 10 个用例）。
- 静态门基线：`--mode=offline --static=verify-service` → **rc=1**、第 1 段 `clean install -DskipTests` SUCCESS、第 2 段 `checkstyle:check` 失败于 **867 项既有违规**；**spotbugs/pmd 因 checkstyle 先失败而从未执行 = 未覆盖**。
- 脏项快照：`?? spec/changes/add-verify-degrade-status-index/`（唯一既有脏项，**不许碰**）。
- **容器状态（指导侧实测）**：`task131-scratch-mysql` 当前为 **`Exited (255)`**、端口映射 `0.0.0.0:13318->3306`。⇒ 需 `docker start`（**只 start，不得 recreate / 不得改配置 / 不得删卷**），并在 handoff 里记录启动前后状态与该实例的 `innodb_flush_log_at_trx_commit`、`sync_binlog`、`log_bin`、`version`（**只读登记，一个都不许改**）。TASK-154/156 都遇到过它被外部 Docker 引擎周期所杀。

## 可直接复用的三个模板（**先读它们再动手**，别自己发明装配）

1. `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkSafetyMysqlIT.java`（TASK-153，770 行）——**真实 `HikariConfig`/`HikariDataSource` + 真实 MyBatis `SqlSessionFactory`/`Environment`/`JdbcTransactionFactory` + 真实 Mapper 装配**的现成范式（它用 `setMaximumPoolSize(6)`、`setPoolName("task153-batch-mark-it")`）。
2. `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentProbeMysqlIT.java`（TASK-147，434 行）——**已 import `HikariPoolMXBean`**，池指标读数的现成范式（它用 `setMaximumPoolSize(4)`）。
3. `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxRelayConcurrencyScalingMysqlIT.java`（TASK-156，598 行）——**环境变量门 + `Assumptions.assumeTrue`（缺变量则 skip、不计通过）+ `SELECT DATABASE()` 硬校验 + `Worker` 线程 + `RoundResult`/`ArmResult` + `Com_update` 交叉校验 + 变异红/字节还原复绿/缺变量 skipped 三档退出码**的现成范式。

## 只改清单（严格 5 项）

1. **新增** `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java`（**必须以 `IT` 结尾**，默认 Surefire 不收集）。
2. **新增** `docs/perf/判别-outbox-relay-池内并发标度.md`（报告）与 `docs/perf/data/exp-outbox-relay-pool-concurrency-scaling.json`（机器摘要）。
3. **新增** 三件套 `spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/`（`proposal.md` + `specs/sport-record-verify/spec-delta.md` + `tasks.json`），**纯 ADDED**（判别类变更，不改任何既有需求）。
4. `work/mailbox/PLAN.md` —— **纯追加** 1 节；**不得改任何既有行（含 L4）**。
5. `work/mailbox/tasks/TASK-161/spec.md`（本文件，**一个字都不许改**）+ `work/mailbox/tasks/TASK-161/handoff.md`（新建）。

**不得改**：任何生产代码（`VerifyOutboxRelay.java`、`RelayDiagnostics.java`、`VerifyEventOutboxMapper.java`、`VerifyEventProducer.java`）、`application.yml`（**含 hikari / rocketmq / 任何默认值**）、任何 SQL/索引/schema、任何 pom、`scripts/**`、`.github/**`、既有 6 个 `*IT`、既有测试、`docs/perf/**` 既有文件、其余 18 个在途目录、其他任务的信箱目录。

## 实验设计（照做，不要自行发挥）

### E1 装配
- 真实 `HikariDataSource`：`setMaximumPoolSize(**10**)`（**必须是 10 = 生产生效默认值，不许调大调小**）、`setPoolName("task161-pool-it")`、其余用 Hikari 默认（**不得**设 `connectionTimeout`/`minimumIdle`/`maximumLifetime` 等来「让它更好过」；若要设必须在 handoff 写明并说明为何不影响判据）。
- 经该 DataSource 建真实 MyBatis `SqlSessionFactory`（照模板 1），**调用生产 `VerifyEventOutboxMapper.markSent` 的逐字 SQL**（`UPDATE verify_event_outbox SET status='SENT', sent_at=NOW() WHERE id=? AND status='PENDING'`）。**不得自己另写一条 UPDATE。**
- 每条连接 `autoCommit=true`（与生产 relay 逐行自动提交一致）。
- 环境变量 `TASK161_IT_URL/USER/PASSWORD`，缺失 ⇒ `Assumptions.assumeTrue` 跳过、**rc=0 但记为未覆盖不计通过**（照模板 3）。
- scratch schema **新建** `task161_pool_scratch`（`sed 's/verify_db/task161_pool_scratch/g' sql/03-verify-db.sql`，机械改名）；每条连接硬校验 `SELECT DATABASE()`。**演示库 3307 一个字节都不许写。**

### E2 臂与轮次
- **M = 2000 行/轮，每臂 3 轮**（与 TASK-156 同纪律）。
- **A 组（无池压力，J=0）**：N ∈ {1, 2, 4, 8}，按**已取列表下标 `i % N`** 切成不重不漏的 N 份（与 TASK-160 生产代码同一切分法），每 worker 从**同一个 Hikari 池**取连接。
- **B 组（池占用压力，证伪臂）**：**N=4、J=8**——从**同一个** DataSource 额外签出 8 条连接并**在整个轮次内持有不放**（空闲即可），使可用连接 = 10 − 8 = 2 < N=4，强制排队。
  - **J 必须明确标注为「池占用代理（occupancy proxy）」，不是消费者行为模型**；不得声称它等价于 32~40 个真实消费线程的负载。
- `S_prod(N) = 中位吞吐(N) / 中位吞吐(1)`，**同组内**比较；**A 组与 B 组之间不得互相换算或相减**。

### E3 每窗口硬判据（12 + 3 = 15 个窗口，**全过才算数**）
- `SHOW GLOBAL STATUS` 的 **`Com_update` 增量精确 = 2000**（前值在重置后/barrier 前取，后值在 join 后/收尾 SELECT 前取）；`Com_insert` = `Com_delete` = **0**。
- 收尾 `SENT = 2000`、`PENDING = 0`。
- **会话门（本轮口径与 TASK-156 不同，必须按下面写）**：因为池会**常驻持有**连接，会话数不再等于 N。判据改为「**除本 IT 自己的池连接（≤ 10）+ 1 条控制连接外，不得有任何外来会话**」；按 `performance_schema.threads` 的 `NAME='thread/sql/one_connection'` 计数，并用 `SHOW PROCESSLIST` 交叉核对；**必须排除** `event_scheduler` 与 `compress_gtid_table` 两条系统 Daemon 线程（TASK-156 首版就是在这里误判过）。FOREGROUND 原始计数照记为证据。
- **池指标（本轮新增的直接证据）**：每轮记录 `HikariPoolMXBean` 的 `getActiveConnections()` / `getIdleConnections()` / `getTotalConnections()` / **`getThreadsAwaitingConnection()`** 的**轮内最大值**。B 组必须能看到 `getThreadsAwaitingConnection() > 0`，**否则说明 J=8 没真正造成压力，该臂作废重做**（这是 B 组的自证门）。
  - 口径提醒：TASK-146 已判定「Hikari 指标是池级聚合、**不可**配对单次调用」⇒ 本轮**只用它证明有无排队，不用它拆解单行 `markSent` 内部构成**，也**不得**据此命名 fsync/锁/纯 SQL 占比。

### E4 三档退出码（照 TASK-156，缺一不可）
1. **变异红**：注入「某 worker 漏标一行」变异 ⇒ 必须被 `Com_update == 2000` 断言抓住，实测 **rc=1**；随后**字节还原**（`cmp` rc=0）。
2. **还原复绿**：还原后独立全量重跑 **rc=0**，S_prod(N) 与主证据 run 相互印证（回传两组数字）。
3. **缺变量 skipped**：不设 `TASK161_IT_*` ⇒ `Tests run: 1, Skipped: 1`、**rc=0 但不记为真库通过**。

### E5 调用通道（**必须照此披露，TASK-154/156 都在这上面栽过**）
`--it` 分支**结构上不可能**跑到 verify-service 的 IT：`scripts/verify/mvn-verify.sh` **L36 硬编码** `IT_CLASSES="LeaderboardDailySummaryMapperMysqlIT,LeaderboardL2RedisRoundTripIT,RocketMqBrokerRoundTripIT"` + `IT_MODULE=leaderboard-service`（指导侧亲读脚本确认）。所以只能用 TASK-156 的同款通道：
- 仓库根临时建 `.mvn/maven.config`（**只两行**：`-Dtest=VerifyOutboxRelayPoolConcurrencyScalingMysqlIT` 与 `-Dsurefire.failIfNoSpecifiedTests=false`），**两行原文入库到 `docs/perf/data/raw/task161-*`**；
- 跑前跑后各存一份 `git status --porcelain .mvn` 快照；
- **任何 `git add`/`git commit` 之前必须删除**，并回传删除后的 `ls` rc 与空 status；
- 删除后**必须再跑一次常规套件**证明基线未被收窄（应回到 **36/41/33/103/120/59/10**、rc=0）。
- **不得**用 `MAVEN_OPTS`、**不得**改 `mvn-verify.sh`、**不得**改任何 pom。

## 预注册裁决（**不得事后放宽**）

- **第一支**：`S_prod(4)@J=0 ≥ 2.0` **且** `S_prod(4)@J=8 ≥ 1.8` ⇒ 判「**池不是硬约束**」⇒ 下一轮（TASK-162）做 3 格负载因子实验 `(5000,1) / (500,1) / (500,4)`。
- **第二支**：`S_prod(4)@J=0 ≤ 1.3` **或** `S_prod(4)@J=8 ≤ 1.3` ⇒ 判「**池是硬约束**」⇒ TASK-162 改为「池容量 × 并发」**交互**判别（注意 TASK-141 已证「单调池 10→20 无稳定收益」，所以那是交互问题不是单调问题），且**在此之前生产不得开启 `>1`**。
- **第三支**：介于其间、或轮间噪声大、或 S_prod 非单调 ⇒ 判「**证据不足**」，只报数字与噪声，**不凑结论、不外推**。
- 无论哪一支：**不改任何默认值、不实施、不宣称吞吐收益、不翻案 TASK-153/154、不改写 TASK-152/156 的任何数字。**

## 明确不做（红线）

- **不 push、不建 PR、不 `git stash`、不 `git add -A`/`add .`**（逐路径 add）。
- **不改任何生产代码与任何默认值**；`relay-send-concurrency` 保持 **1**；`application.yml` 一字不动。
- **不 Flyway、不 Testcontainers、不加新插件/依赖**（HikariCP 与 MyBatis 已在 verify-service 测试类路径上，三个模板 IT 已在用）。
- **不起四服务、不跑负载、不连 RocketMQ**（本轮无 MQ ⇒ 对「(a) 并发 `syncSend`/broker 吞吐」**零信息**，必须记为未覆盖）。
- **不写演示库 3307**；scratch schema 收尾状态如实登记。
- **不裸用 `mvn`**；Maven 只走 `& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh`。
- 未达环境一律如实记「**未覆盖**」，**绝不造假绿**；`--mode=online`/CI 本轮不跑 ⇒ 记「未达外部门槛」。

## 关键陷阱（指导侧本会话逐个亲踩/亲验，照做即可）

1. **词面门正则一律现场提取，任何要入库的文件都不得内嵌它的字面量**（TASK-158 的 C4 就是内嵌了它、入库后自命中，把 CI 门槛打破过；C4 首版连「描述禁词」都用了禁词字面量，又自造 2 处命中）。提取式：`sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1`，长度应为 **26**，**必须断言非空否则 `exit 9`**。
2. **词面门三态判定**：`rc=0` 有命中 / `rc=1` 无命中 / **其他 rc = 工具错误，判门槛失败而不是通过**。`if git grep …; then HITS else ZERO_HIT` 这种二分支会把 fatal 当干净。
3. **`git grep --untracked` 必须放在 pattern 之前**：写成 `… -iE "$RE" --untracked -- <pathspec>` 时 git 2.20.1.windows.1 把它当 revision，报 `fatal: unable to resolve revision`、返回 **rc=128**。
4. **`git grep` 默认不搜未跟踪文件**：正向对照若用未跟踪探针，必须加 `--untracked`（位置正确）或改用 `grep` 直扫，否则得到**假 ZERO_HIT**（指导侧本轮亲自踩过一次）。
5. **行尾：计数只用 `tr -cd '\r' | wc -c` / `tr -cd '\n' | wc -c`；定位单行 CR 只用字节偏移扫描**（`od -An -tx1 -v` 或 Python 二进制读）。`grep -c $'\r$'` 是**假阳性**（GNU grep 3.1 匹配全部行）；`awk '$0 ~ /\r$/'` 是**假阴性**（GNU Awk 4.2.1 剥记录尾 CR）。合成对照 `line1\nline2\nline3\r\n` 实测：awk **0**（真值 1）、grep **3**（真值 1）、字节扫描**正确**。
6. **`set -e` 与 `rc=$?` 冲突**：需要抓非零 rc 的段落**不要**放在 `set -e` 下（指导侧本会话踩了三次）。
7. **PowerShell 命令行全程不得出现中文**（exit 127 或静默剥离）。中文正则/提交信息写入 `.trae/tmp/` 下 UTF-8 无 BOM 文件再引用，提交用 `git commit -F`。需要 bash 一律**写成 `.sh` 文件**再 `& 'D:\git\Git\bin\bash.exe' <路径>`，**禁止** `bash -lc "..."` 内联（PowerShell 会破坏 `$?`、`<`、`$'...'`）。
8. **POSIX grep 无 lookahead**：想表达「非 final 字段数 = 0」时用**两个计数相等**（`private` 总数 == `private (static final|final)` 数），不要写 `(?!…)`。
9. **不同实例/不同窗的数字不可比**：本轮 scratch 实例（宿主 13318）的 ms/行**不得**与 TASK-152 演示实例（3307）的 18.0 ms/行、也**不得**与 TASK-156 同实例但**不同装配**（DriverManager vs Hikari）的数字并列成「优化前后」。判别量只有**同实例同装配内的 S_prod(N)**。
10. **B 组自证门**：若 `getThreadsAwaitingConnection()` 轮内最大值始终为 0，说明 J=8 没造成真实排队 ⇒ **该臂作废重做**，不得拿一个没有压力的「压力臂」去支撑第一支裁决。

## 收口门槛（每条回传实测退出码/数字原文，不接受散文）

- **G0 起点与环境**：`git rev-parse HEAD` == 起点 SHA；`docker start` 前后状态；实例四项只读登记（`innodb_flush_log_at_trx_commit` / `sync_binlog` / `log_bin` / `version`）；`application.yml` 的 hikari 段与 `maximum-pool-size` 命中数 **0**（证明未改）。
- **G1 装配正确性**：回传 IT 里 `setMaximumPoolSize` 的实参（必须 **10**）、`poolName`、`autoCommit` 设置处、以及 `markSent` 的 SQL 来源证明（走的是生产 Mapper 而非自写 UPDATE）。
- **G2 15 个窗口硬判据**：逐窗口回传 `Com_update` 增量（全部 **= 2000**）、`Com_insert`/`Com_delete`（全部 **0**）、`SENT`/`PENDING`、会话门读数（含被排除的 2 条 Daemon 的原始 FOREGROUND 计数）。
- **G3 池指标与 B 组自证**：逐轮 `getThreadsAwaitingConnection()` 最大值；**B 组必须 > 0**；A 组 N=1 应为 0。
- **G4 三档退出码**：变异红 **rc=1**（回传断言原文与实测值）、字节还原 `cmp` **rc=0**、复绿 **rc=0**（回传两组 S_prod）、缺变量 **Tests run: 1, Skipped: 1 / rc=0**。
- **G5 offline 零扰动**：开工与收口各一次 `mvn-verify.sh --mode=offline test`，两次 rc=**0** 且七模块 **36/41/33/103/120/59/10** 逐位一致（新 `*IT` 不被默认收集 ⇒ 数字不变；回传两次的完整数字串与「新 IT 收集数 = 0」的证明）。
- **G6 静态门不得新增**：`--mode=offline --static=verify-service` 的 checkstyle 违规数 **≤ 867**；spotbugs/pmd 记**未覆盖**（被 checkstyle 阻断在前），**不得写成通过**。
- **G7 词面门**：按陷阱 1–4 执行，4 形态全 **rc=1**、**正向对照必须命中**（回传 rc 与命中数）、探针已删（回传删除后 `git status`）。
- **G8 通道披露**：`.mvn/maven.config` 两行原文 + 跑前跑后 `git status --porcelain .mvn` 快照 + 删除后 `ls` rc 与空 status + 删除后常规套件回到基线的数字串。
- **G9 空白与契约**：`git diff --check` **rc=0**；每笔提交 `git show --check` **rc=0**；在途 `bash scripts/verify/mailbox-contract.sh --open TASK-161 --baseline=<起点SHA>` 记 rc（rc=1 须逐条说明过冲仅来自既有脏项）；**收口后无参复跑必须 rc=0**。
- **G10 外部门槛与不得声称**：不 push ⇒ PLAN 该栏写「**未达外部门槛**（本次不 push，待下次授权由 CI 复验）」；且 handoff 与 PLAN 必须出现「**本轮零生产行为变化、零已测收益**」与「**未覆盖 (a) 并发 syncSend/broker 吞吐**」。

## 可直接使用的自检脚本（写到 `.trae/tmp/task161-verify.sh`，UTF-8 无 BOM，用 bash 跑）

覆盖 G0 的只读事实、G7 词面门（三态 + 正向对照）、G9 空白/契约/足迹。G5（offline 双跑）与 G6（静态门）是长命令，请单独跑再用本脚本尾部的解析器取数字。**需要抓非零 rc 的段落不要放在 `set -e` 下。**

```bash
#!/usr/bin/env bash
# 用法： bash .trae/tmp/task161-verify.sh <起点SHA>
cd /d/code/sports || exit 9
BASE="${1:?need base sha}"
mkdir -p .trae/tmp
IT=verify-service/src/test/java/com/sportverify/verify/mapper/VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java

echo '== G0 只读事实 =='
echo "  HEAD=$(git rev-parse HEAD)"
echo "  BASE_match=$([ "$(git rev-parse HEAD)" != "$BASE" ] && echo yes || echo BASE-is-HEAD)"
echo "  ahead/behind=$(git rev-list --left-right --count origin/main...main)"
echo "  application.yml changed lines (expect 0) = $(git diff --numstat "$BASE" HEAD -- verify-service/src/main/resources/application.yml | wc -l)"
echo "  hikari maximum-pool-size in yml (expect 0) = $(grep -c 'maximum-pool-size' verify-service/src/main/resources/application.yml)"
echo "  relay-send-concurrency default in prod code:"
grep -n 'relay-send-concurrency' verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java | sed 's/^/    /'
echo "  prod code changed since BASE (expect EMPTY):"
git diff --numstat "$BASE" HEAD -- 'verify-service/src/main/java/**' | sed 's/^/    /'
echo '== G1 装配事实（池必须是 10）=='
if [ -f "$IT" ]; then
  echo "  IT lines=$(wc -l < "$IT")"
  grep -n 'setMaximumPoolSize\|setPoolName\|setAutoCommit\|TASK161_IT_\|assumeTrue\|SELECT DATABASE()' "$IT" | sed 's/^/    /'
  echo "  markSent via production mapper (expect >=1):"
  grep -n 'markSent\|VerifyEventOutboxMapper' "$IT" | sed 's/^/    /'
  echo "  pool MXBean usage (expect >=1 for B-arm self-proof):"
  grep -n 'HikariPoolMXBean\|getThreadsAwaitingConnection\|getActiveConnections' "$IT" | sed 's/^/    /'
else echo "  MISSING $IT  <== 判红"; fi

echo '== G7 词面门：现场提取 + 三态 x4 + 正向对照 =='
RE=$(sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1)
if [ -z "$RE" ]; then echo "  G7_FATAL: extraction empty"; exit 9; fi
echo "  extracted regex length=${#RE} (expect 26)"
rep3() { case $2 in
    0) echo "  G7[$1] HITS rc=0 <== 判红"; sed 's/^/      /' "$3" ;;
    1) echo "  G7[$1] ZERO_HIT rc=1 <== 期望" ;;
    *) echo "  G7[$1] TOOL_ERROR rc=$2 <== 判失败不是通过"; head -2 "$3" | sed 's/^/      /' ;; esac; }
env -u LC_ALL -u LANG git grep -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > .trae/tmp/g7a.txt 2>&1
rep3 ci-exact $? .trae/tmp/g7a.txt
for loc in C zh_CN.UTF-8 C.UTF-8; do
  LC_ALL=$loc git grep -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > ".trae/tmp/g7-$loc.txt" 2>&1
  rep3 "$loc" $? ".trae/tmp/g7-$loc.txt"
done
python - <<'PY'
ws=["\u9762\u8bd5","\u5f39\u836f","\u5927\u5382","\u516b\u80a1","\u7b80\u5386","\u6c42\u804c","\u7a81\u51fb","\u9644\u5f55A","\u9644\u5f55 A"]
open("g7probe-DELETEME.txt","w",encoding="utf-8").write(
  "line1 clean\n"+"".join("line%d has %s\n"%(i+2,w) for i,w in enumerate(ws))+"tail clean\n")
print("  probe written with %d banned forms" % len(ws))
PY
git grep --untracked -n -I -iE "$RE" -- g7probe-DELETEME.txt > .trae/tmp/g7p.txt 2>&1
echo "  G7_positive_control rc=$? hits=$(wc -l < .trae/tmp/g7p.txt) (expect rc=0 hits=9)"
rm -f g7probe-DELETEME.txt
echo '  probe removed; git status --porcelain:'; git status --porcelain | sed 's/^/      /'

echo '== G9 空白 / 契约 / 足迹 =='
git diff --check; echo "  G9_diffcheck_rc=$? expect=0"
for c in $(git rev-list "$BASE"..HEAD); do git show --check "$c" >/dev/null 2>&1; echo "  show_check $(git rev-parse --short $c) rc=$?"; done
bash scripts/verify/mailbox-contract.sh > .trae/tmp/g9.log 2>&1; echo "  G9_contract_noarg_rc=$? expect=0"; tail -2 .trae/tmp/g9.log | sed 's/^/    /'
echo '  footprint:'; git diff --numstat "$BASE" HEAD | sed 's/^/    /'
git diff --shortstat "$BASE" HEAD | sed 's/^/    /'

echo '== G5/G6 日志解析器（跑完 maven 后用）=='
echo '  offline 七模块串：'
echo "    grep -E '^\\[INFO\\] Tests run: [0-9]+, Failures' .trae/tmp/YOUR-offline.log | grep -v -- '-- in'"
echo '  静态门违规数：'
echo "    grep -o 'You have [0-9]* Checkstyle violations' .trae/tmp/YOUR-static.log"
echo '  基线：offline = 36/41/33/103/120/59/10 rc=0；static checkstyle = 867（不得新增）'
```

注：脚本里 `BASE_match` 那行只是提示——真正的判据是**开工时** `git rev-parse HEAD` 必须等于起点 SHA，不等就停手回报。

## 交回物（`handoff.md` 必含，缺一项即视为未收口）

1. 结论一句话：落入预注册**哪一支**，S_prod(2)/S_prod(4)/S_prod(8)@J=0 与 S_prod(4)@J=8 各是多少。
2. 起点 SHA 与各笔提交 SHA；每笔 `git diff --cached --name-only` 原文；`git diff --shortstat <起点>..HEAD`。
3. 只改清单逐项对齐（5 项，含「未改动」的显式声明，特别是**生产代码零改动**与 `application.yml` 零改动）。
4. **G0–G10 全部实测输出与退出码原文**。
5. **预登记的四条算术假设与那张 5 行推演表逐条复述**，并说明本轮结果对第 ③ 条假设（S(N) 可迁移）给出了什么答案。
6. 未覆盖项与不得推出的结论（至少：(a) 并发 syncSend/broker 吞吐**零信息**；J 是**池占用代理不是消费者模型**；未起四服务、未跑负载；spotbugs/pmd 未覆盖；未 push 未过 CI；**不得**据此在生产开启 `>1`；**不得**把本轮数字与 TASK-152 的 18.0 ms/行 或 TASK-156 的 S(N) 并列成优化前后）。

## 指导侧复验收方式

指导侧将**亲自重跑** offline 套件与静态门、逐字 diff 每笔提交、并**逐字搜**「零已测收益」「未覆盖 (a)」「池占用代理」三处必备表述与预登记四假设。判红的四种情形：① 任何生产代码或 `application.yml` 被改；② `setMaximumPoolSize` 不是 10；③ B 组 `getThreadsAwaitingConnection()` 全 0 却仍据以裁决第一支；④ 出现把本轮结果当作吞吐收益、或把 S_prod 与 TASK-156 的 S 并列成「优化前后」的表述。任一即**契约红、要求回滚重做**。
