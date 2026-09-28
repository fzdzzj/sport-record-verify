# TASK-156：判别 outbox relay `markSent` 并发标度（方向 B；scratch 真库 + test-only IT，只判别不实施）

## 目标与基线

开工 HEAD `7eb609501730729d7f0a69f2bd31d4df88a04ead`（执行 agent 开工先 `git rev-parse HEAD` 核对一致并把全 SHA 记进 handoff；不一致即停并回传，不得擅自 rebase/checkout/pull）。

裁决**一个且仅一个**问题：在**逐行语义完全不变**（每行仍各自一次自动提交、各自 `status` PENDING→SENT、各自 `sent_at`）的前提下，把 `markSent` 从单线程改为 N 路并发，**总投递吞吐是否随 N 显著上升**（InnoDB 组提交能否摊薄「每行一次持久化往返」的成本）。这是 relay 容量**唯一尚未测过**的杠杆——TASK-141 测的是整系统连接池 10 vs 20，不是 `markSent` 的并发标度。

**只裁决，不实施优化**：不改任何生产 Java/SQL/YAML/运行默认值（relay-interval / batch-size / max-retry / 锁 / 并发），不起四服务、不跑 c100×2000，不改任何仪器；**不得**借此翻案 TASK-153（批末统一标记 SENT = NO-GO）或改写 TASK-152 数字；本任务**不实现**分区 relay。

## 边界与判据

### 环境与隔离

- 用既有 scratch 容器 `task131-scratch-mysql`（MySQL 8.0.46、宿主 13318），**只新增** schema `task156_concurrency_scratch`（由仓库 `sql/03-verify-db.sql` 机械改名生成；IT 内硬校验 `SELECT DATABASE()` 命中该 schema）。跑前跑后既有 scratch schema 行数不变（task131/142/147/148/149/153/154 等），如实记录。
- **只读登记**（不得修改）scratch 实例的 `innodb_flush_log_at_trx_commit`、`sync_binlog`、binlog 开关——组提交摊薄空间取决于这些，须写进回传作为边界。**不得**改 MySQL 配置/仪器、不重置全局计数器（读 `Com_update` 用前后差）、不重启/重建容器；若开工时容器为 `Exited`（外部引擎周期所杀），**只启动**既有容器（不重建、不改配置、不删卷），收尾如实回传。
- **不起任何 Java 服务**、不向演示库 `sport-verify-mysql`(3307) 写入、不跑真实 relay 负载。

### 受控实验（test-only IT）

- 新建 test-only IT `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxRelayConcurrencyScalingMysqlIT.java`，**镜像 TASK-154 `VerifyEventOutboxMarkSentWaitAttributionMysqlIT` 口径**：`*IT` 默认不被 Surefire 收集；缺 `TASK156_IT_URL/USER/PASSWORD` 即 `Assumptions.assumeTrue` 跳过且**不记为真库通过**；用独立 `DriverManager` 连接、`autoCommit=true`（与生产「一调用一提交」同语义）。
- **SQL 逐字取自生产** `VerifyEventOutboxMapper#markSent`：`UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`（与 TASK-154 同法从 Mapper 注解运行时渲染，或硬编码并注明出处；**不得**自造 SQL、不得增删 WHERE 条件）。
- 种子：每臂在 `task156_concurrency_scratch.verify_event_outbox` 灌 **M=2000** 行 PENDING（与 TASK-143/152 样本量同阶），`id` 连续。
- 臂：N ∈ {1, 2, 4, 8}。**第 N 臂用 N 个线程、每线程一条独立连接**，按 `id % N` 划分**互不相交**的行集（避免同行锁竞争混淆组提交效应），各线程循环对己方行执行上述单行 `markSent` 直至本线程行集全部 SENT。用 barrier 同步起跑、`join` 收尾；**臂总墙钟 = 起跑到全部线程完成**。每臂跑前把全部 M 行重置为 PENDING（一条批量 UPDATE 或重灌均可，**重置成本不计入臂墙钟**）。
- 每臂重复 **3 轮**取中位与极差（抗单轮噪声）。每臂采集：总墙钟(ms)、**聚合吞吐 = M / 总墙钟 (行/s)**、单行墙钟分布(均值/中位)、`Com_update` 前后差、目标 digest 语句计数前后差（若可读）。

### 交叉校验（防假绿，硬判据）

- **每臂 `Com_update` 增量必须精确 = M（2000）**，且收尾 `SELECT COUNT(*) ... status='SENT'` = M、`status='PENDING'` = 0 → 证明「每行恰好一次成功标记、无重复无丢失」。任一臂不满足 → 判 harness 有缺陷，**停并回传**，不得报告该臂标度。
- **计数窗口必须干净（防假红，硬前置）**：`Com_update` 是**全局**计数器，故 (i) 每臂的 `Com_update` 前值**必须在重置 PENDING 之后、barrier 起跑之前**读取——重置用的批量 UPDATE / 重灌 INSERT 一律不得落进窗口，否则增量必然 > M 而误判 harness 有缺陷；(ii) 后值必须在**最后一个工作线程 join 之后、任何收尾核对 SELECT 之前**读取；(iii) 每臂起跑前用 `SELECT COUNT(*) FROM performance_schema.threads WHERE TYPE = 'FOREGROUND'` 与 `SHOW PROCESSLIST` 记录在跑会话，要求除本臂自有 N+1 条连接外**无外来前台会话**；若有 → 等其结束或**停并回传**，不得报告该臂标度；(iv) 一并记录窗口内 `Com_insert` / `Com_delete` 增量（应为 0）作为污染证据。**沿用 TASK-154 已实测事实**：本 scratch 实例的 `performance_schema.global_status` **不列** `Com_update` / `Com_insert`，只能用 `SHOW GLOBAL STATUS` 读取（见 `VerifyEventOutboxMarkSentWaitAttributionMysqlIT` L337-348）；不得改用 P_S 视图后把「读不到」误报成「harness 缺陷」。
- **变异红（镜像 TASK-154）**：故意制造一处错误（如某线程重复标记同一行 / 漏标一行），断言应翻红（`Com_update` ≠ M 或 SENT 计数 ≠ M）以证明 IT 非空过；还原后复绿。记录变异红与还原复绿的退出码。
- **N=1 基线合理性**：N=1 单行墙钟应落在「单行自动提交条件 UPDATE」合理量级；若 <1ms/行或报错 → 先查 harness/连接语义（是否真 autocommit、是否命中行）再信 N>1。**注意**：本任务在 scratch 实例(13318)，与 TASK-152 演示实例(3307)不同机不同窗，**不得**把本任务 N=1 的绝对 ms/行等同于 TASK-152 的 18.0ms/行；判别量是**同实例内的标度比 S(N)=throughput(N)/throughput(1)**，对实例差异稳健。

### 一句裁决（预注册，不得事后放宽）

- 令 S(N) = throughput(N) / throughput(1)（同实例、同 M、各臂中位）。
- **S(8) ≥ 2.0 且 S 随 N 单调不减（容差内）** → 判「并发标度成立」：在不放宽 TASK-153 四项语义前提下，分区 relay 是**有希望**的容量杠杆（**仅必要条件成立**，见「不得推出」）；据实记录 S(2)/S(4)/S(8) 与各臂 ms/行。
- **S(8) ≤ 1.2（基本持平）** → 判「每行提交成本是硬墙、并发不摊薄」：relay 容量决策收敛为 A（放宽语义批量标记，需翻案 TASK-153）或 C（接受 ~13 行/s 上界）二选一；据实记录持平数字。
- **1.2 < S(8) < 2.0，或轮间噪声大 / 非单调** → 判「证据不足以定标度」，只报数字与噪声，不凑结论、不外推。
- 无论哪一支：**本任务不改任何默认值、不实现分区 relay、不宣称生产吞吐收益。**

### FIFO 反例（只核查并记录，不实现）

- 并发/分区 relay 的最强反例 = **失去按 id 的全局 FIFO**。本任务**只读核查**两个消费者是否依赖**同 recordId 的事件顺序**（初判 → 申诉终判改判 的先后）：`leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java`、`verify-service/src/main/java/com/sportverify/verify/consumer/VerifyEventConsumer.java`（含其幂等/去重与状态机假设）。
- 据实记录：若**存在**同 recordId 顺序依赖 → 未来分区 relay 必须**按 recordId 分区**（而非 round-robin / `id % N`）以保序，把该约束写进回传供后续设计；若**不存在** → 记录依据（如消费端 SETNX/锚点幂等已使顺序无关，但**消费端幂等不自动等于授权改 relay**）。**本任务不据此改任何代码。**

## 证据与交付

- 报告 `docs/perf/判别-outbox-relay-markSent-并发标度.md`；低基数机器摘要 `docs/perf/data/exp-outbox-relay-concurrency-scaling.json`（每臂：N、3 轮总墙钟、聚合吞吐、S(N)、ms/行 中位、Com_update 增量、SENT/PENDING 收尾计数；不含 payload/eventId/token）；原始输出放 ignored `docs/perf/data/raw/task156-*`。
- **不改业务行为**：除新增 test-only IT 与文档/JSON 外，不改任何生产 `.java`/`.sql`/`.yml`/`.properties`、不改任何默认值；不 push、不建 PR；不 `git stash`、不 `git add -A`/`git add .`（逐文件 `git add <path>`）。
- **既有脏项原状保留**：6 个归档删除侧文件、`spec/changes/archive/*`、`spec/changes/add-verify-degrade-status-index/`、两个 0/5 在途目录——一律不触碰（属 TASK-157 规格合并轮）。
- **OpenSpec 三件套**：新建 `spec/changes/prove-verify-outbox-relay-concurrency-scaling/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}`，镜像 TASK-154 `prove-verify-mark-sent-wait-attribution` 的结构与「只判别不优化」口径；tasks.json 据实勾选。
- **顺带订正 PLAN 陈旧计数（TASK-155 遗留漂移）**：`work/mailbox/PLAN.md` L4 一句话现写「HEAD `6016d92` 领先 `origin/main` **38 笔**（TASK-138~154…）」，与实测不符（本任务开工时实为 HEAD `7eb6095`、领先 **40** 笔）。两笔提交完成后按 `git rev-list --count origin/main..main` 的**实测值**整行替换该一句话：更新 HEAD 短 SHA、笔数、任务区间（…~156），并把句末「唯一未测杠杆=并发标度判别（方向待用户授权）」改写为本任务预注册三支之一的**实测裁决**（含 S(2)/S(4)/S(8) 与各臂 ms/行 中位）。机会总览 §3-P2 / §4 只做**追加**，不改既有历史数字（`13.4 行/s`、`18.0 ms/行`、`73.93%`、`68.8s`、`6315ms` 的出现次数不得减少）。
- **命令行不得出现中文**（exit 127）：提交信息写 UTF-8 文件再 `git commit -F <file>`；两笔本地提交——(1) 业务证据：IT + 报告 + JSON + 三件套；(2) 台账：TASK-156 spec/handoff + PLAN + 机会总览。
- **【真库 IT 的唯一可复现调用通道 —— 硬约束】（指导侧已实测确认；TASK-154 未记载，本任务必须补上，否则收口门槛无法执行）**

实测事实（指导侧 2026-09-28 复核，勿再自行摸索）：

1. `scripts/verify/mvn-verify.sh` 的参数解析对未知参数 **exit 2**（`*)` 分支），**没有任何 `-D` 透传**；其 `--it` 分支在 L190 **硬编码** `-pl leaderboard-service -am -Dtest=LeaderboardDailySummaryMapperMysqlIT,LeaderboardL2RedisRoundTripIT,RocketMqBrokerRoundTripIT`，并**忽略 `--pl`**。故 `--it` **无法**运行 verify-service 的任何 IT。
2. 父 `pom.xml` 与 `verify-service/pom.xml` **均无 surefire 配置**（实测 grep 命中 0）→ 走 Surefire 默认 includes，`*IT` **默认不被收集**（TASK-155 实测 offline `--pl verify-service` = common 36 / verify-service 110、skipped 0 即为证）。
3. 因此要点名运行本任务的 IT，**必须**经带外通道给出 `-Dtest=`。对照证据：TASK-154 `docs/perf/data/raw/task154-08-it-run.log` 与 TASK-155 `task155-01-mvn-verify.log` 的「命令全文」**逐字相同**（`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service -am`），但前者全反应堆只跑 1 个 IT、后者跑 36+110 —— 说明 TASK-154 用了带外通道且**未在台账记载**，属既有审计缺口。

**本任务指定通道 = 仓库根 `.mvn/maven.config`（Maven 自动读取，不出现在「命令全文」里）**，硬性要求：

- 内容**两行、每行一个参数**（兼容 Maven 3.8/3.9 解析）：`-Dtest=VerifyEventOutboxRelayConcurrencyScalingMysqlIT` 与 `-Dsurefire.failIfNoSpecifiedTests=false`（后者必需：`-am` 会带上 common/api，那里没有该类，缺它则报 No tests matching pattern）。
- `.mvn/maven.config` **不在 `.gitignore` 内**（实测 `git check-ignore` rc=1）→ 它会以未跟踪项出现在 `git status`。这是**有意**的可审计性：跑前跑后各记一次 `git status --porcelain .mvn`，并把 `Get-Content .mvn/maven.config` 原文**粘进原始日志与 handoff**，使本任务的 IT 运行**可被他人逐字复现**。
- **任何 `git add` / `git commit` 之前必须删除该文件**，并以 `Test-Path .mvn/maven.config` = `False` + `git status --porcelain .mvn` 空输出作证。**绝不得提交它**。
- 环境变量：`TASK156_IT_URL`（指向 13318 的 `task156_concurrency_scratch`）/ `TASK156_IT_USER` / `TASK156_IT_PASSWORD`，在**同一 PowerShell 会话**用 `$env:` 设置后再调用 `& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`。
- **常规套件档必须在 `.mvn/maven.config` 已删除之后跑**（否则会被 `-Dtest=` 收窄成 1 个测试而误判基线）：`--mode=offline --pl verify-service` 应回到 common 36 / verify-service 110、skipped 0，与基线同数。
- **不得**用 `MAVEN_OPTS` 当通道（不入 `git status`、不可审计）；若万不得已使用，必须在 handoff 显式披露原文。**不得**为了让 IT 跑起来而修改 `scripts/verify/mvn-verify.sh` 或任何 pom —— 用被审对象改审判门槛属利益冲突，该结构性缺口由指导侧另立任务处理。

- **收口门槛**：显式真库 IT 经 `bash scripts/verify/mvn-verify.sh`（Git Bash 入口 `D:\git\Git\bin\bash.exe`，**不得裸用 mvn**）**rc=0**；变异红 **rc=1**；还原复绿 **rc=0**；缺变量 skipped **rc=0 但不记真库通过**；offline `--pl verify-service` 常规套件 **rc=0** 且与基线同数（新 `*IT` 不被默认收集）。`git diff --check` rc=0；无参数 `bash scripts/verify/mailbox-contract.sh` 两件套提交后 **rc=0**（进行中可 `--open TASK-156`）。`--mode=online`/CI 不跑，**未达外部门槛**。
- **回传**：据实填 `work/mailbox/tasks/TASK-156/handoff.md`（结论与 S(N) / 起点全 SHA / 环境与隔离披露（含组提交相关配置）/ 各臂直接读数与交叉校验 / FIFO 核查结论 / 判据与退出码 / 实际改动「只改」逐文件清单 / 未覆盖与不得推出）。
- **不得推出（务必逐条写进 handoff）**：scratch 真库 + test-only `DriverManager` IT ≠ 生产 relay（Spring/Hikari + Redisson 全局 `tryLock(0)` 单跑）；正标度结果只是**必要非充分**——本任务隔离的是 `markSent` 的 DB 提交成本（TASK-146 测得约占锁内处理段 80%），**未测**并发 `syncSend`/RocketMQ、未测 relay 锁改造、未测多实例竞争；不得据此改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 任何默认值；不得宣称生产延迟或吞吐收益；不得翻案 TASK-153；消费端幂等不自动等于授权改 relay。
