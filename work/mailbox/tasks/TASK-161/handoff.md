# TASK-161 handoff：判别生产 Hikari 池（默认 10）下 relay 批内并发的 S_prod(N)（只判别不实施）

## 1. 结论一句话

**落入预注册第三支「证据不足」**：`S_prod(2)@J=0 = 1.8567`、`S_prod(4)@J=0 = 3.1538`、`S_prod(8)@J=0 = 5.6803`（随 N 单调不减），`S_prod(4)@J=8 = 1.7756`（B 组自证门 `getThreadsAwaitingConnection()` 轮内最大值 = 2 > 0，压力真实）。第一支要求 `S_prod(4)@J=8 ≥ 1.8`，实测 **1.7756 差 0.0244（1.36%）未达**；第二支要求任一 ≤ 1.3，两值均未触发；1.7756 介于 (1.3, 1.8) 之间 ⇒ **只报数字与噪声，不凑结论、不外推**。三组独立全量（主证据 run / 两次还原复绿）的 `S_prod(4)@J=8` = 1.7756 / 1.7794 / 1.7170 **全部落在该区间内 ⇒ 第三支裁决稳健**（1.7756 距下门限 1.3 与上门限 1.8 均非「接近」量级以外的外推，按预注册纪律不得放宽或改判）。

**本轮零生产行为变化、零已测收益**：未改任何生产代码、任何默认值（`relay-send-concurrency` 保持 1、`application.yml` 一字未动）；不得据此在生产开启 `relay-send-concurrency > 1`，不得宣称任何吞吐/延迟收益。

## 2. 起点 SHA、四笔提交与 shortstat

- 起点 HEAD = `291e686a663e78ec7a8d1faeec14b9aeb669bca8`（开工 `git rev-parse HEAD` 逐位核对一致；开工 `git rev-list --left-right --count origin/main...main` = `0	1`，origin/main = `e1c96d6…`，未推 1 笔即指导侧的 PLAN L4 订正；**本任务不得 push**）。
- C1 = `604c6e9d244f3ae703d5df292f8c36977eb72e22`（test-only 真库 IT）；C2 = `040fb22d342979d0d7eff2238dea70d1f7aa9765`（报告 + 机器摘要）；C3 = `1a188658ff0836e105d6bdecd69a9a058409e0c0`（三件套纯 ADDED）；**C4 = 本文件落库时的提交**（按 TASK-159/160 先例，本文件无法预含自身哈希；`git log --oneline -1` 与执行回复可见）。
- 每笔入册文件清单——**原文**（提交后以 `git diff-tree --no-commit-id --name-only -r <SHA>` 复采，与提交时刻 `git diff --cached --name-only` 的索引快照逐字一致）：

```
C1：
verify-service/src/test/java/com/sportverify/verify/mapper/VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java

C2：
docs/perf/data/exp-outbox-relay-pool-concurrency-scaling.json
"docs/perf/\345\210\244\345\210\253-outbox-relay-\346\261\240\345\206\205\345\271\266\345\217\221\346\240\207\345\272\246.md"

C3：
spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/proposal.md
spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/tasks.json
```

（C2 第 2 行为 `core.quotepath=true` 下 git 对非 ASCII 路径的 C 式引用转义输出，即报告 `docs/perf/判别-outbox-relay-池内并发标度.md`；下同。）
- `git diff --shortstat 291e686a..HEAD`（C1+C2+C3 后实测）：` 6 files changed, 1189 insertions(+)`；逐文件 numstat：IT 768/0、JSON 181/0、报告 119/0、proposal 29/0、spec-delta 39/0、tasks.json 53/0。C4 后含台账的全量 shortstat 见本文件补记与 PLAN 验收记录（随执行回复回传）与执行回复。
- 全程未 push、未建 PR、未 `git stash`、未 `git add -A`/`add .`（逐路径 add）。

## 3. 只改清单 5 项逐项对齐

本轮实际改动路径全集（9 条，逐字；契约判据 B 口径）：

```
verify-service/src/test/java/com/sportverify/verify/mapper/VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java
docs/perf/判别-outbox-relay-池内并发标度.md
docs/perf/data/exp-outbox-relay-pool-concurrency-scaling.json
spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/proposal.md
spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-161/spec.md
work/mailbox/tasks/TASK-161/handoff.md
```

## 3b. 零修改声明（显式）与逐项说明

1. `VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java` —— **新增**（C1，768 行；类名以 `IT` 结尾，Surefire 默认不收集 ⇒ 常规套件收集数 0、verify-service 仍 120）。对齐任务书第 1 项。
2. `docs/perf/判别-outbox-relay-池内并发标度.md`（119 行）与 `docs/perf/data/exp-outbox-relay-pool-concurrency-scaling.json`（181 行）—— **新增**（C2）。对齐任务书第 2 项。
3. `spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/` 三件套（proposal 29 行 + spec-delta 39 行 + tasks.json 53 行）—— **新增，纯 ADDED**（C3；delta 只有 `## ADDED Requirements` 两个需求、无任何 MODIFIED）。对齐任务书第 3 项。
4. `work/mailbox/PLAN.md` —— **纯追加 1 节**（C4；`git diff --numstat -- work/mailbox/PLAN.md` 实测 **29 insertions / 0 deletions**；既有行（含 L4）零改动）。对齐任务书第 4 项。
5. `work/mailbox/tasks/TASK-161/spec.md`（指导侧任务书**原样首次入库，执行侧一个字未改**）+ `work/mailbox/tasks/TASK-161/handoff.md`（本文件新建）—— C4。对齐任务书第 5 项。

**显式未改动声明（全部零触碰）**：任何生产代码 `VerifyOutboxRelay.java` / `RelayDiagnostics.java` / `VerifyEventOutboxMapper.java` / `VerifyEventProducer.java`（`git diff --numstat 291e686a..HEAD -- 'verify-service/src/main/java/**'` 空输出为证）；`application.yml`（含 hikari/rocketmq/任何默认值；`maximum-pool-size` 全文命中 **0** 次；hikari 段原文仅 L39-40 `initialization-fail-timeout: -1` 一项）；任何 SQL/索引/schema；任何 pom；`scripts/**`；`.github/**`；既有 6 个 `*IT`；既有测试；`docs/perf/**` 既有文件；其他任务的信箱目录；`spec/changes/` 下其余 18 个在途目录；`?? spec/changes/add-verify-degrade-status-index/`（保持未跟踪原样）。未用 `MAVEN_OPTS`、未改 `mvn-verify.sh`、未裸用 mvn；`.mvn/maven.config` 在任何 `git add`/`commit` 之前已删除（见 G8）。

## 4. G0–G10 全部实测输出与退出码原文

三档退出码与门槛汇总（原文形态，逐项明细见下）：

```
缺变量 skipped rc=0（Tests run: 1, Skipped: 1；不记真库通过）
主绿跑 rc=0（15 窗口硬判据全过；第三支）
变异红 rc=1（Com_update 实测=1999 被既有断言抓住）
还原复绿 rc=0 + 第三次全量 rc=0（cp 回写后 cmp rc=0 字节一致）
常规套件 rc=0（七模块 36/41/33/103/120/59/10、Skipped 全 0）
静态门 rc=1（checkstyle 867 ≤ 867「不得新增」；spotbugs/pmd 未覆盖）
git diff --check rc=0
在途契约 rc=1（清单多报 0 条；逐条说明见 G9）
收口后无参契约 rc=0（见文末补记）
--mode=online / CI 未跑 —— 未达外部门槛
```

### G0 起点与环境（只读，各读数原文）

```
  HEAD = 291e686a663e78ec7a8d1faeec14b9aeb669bca8（== 起点，逐位一致）
  origin/main...main = 0	1
  container task131-scratch-mysql：前态 Exited (255)（任务书记载）→ 只 docker start → Up、
    端口映射 0.0.0.0:13318->3306；未 recreate / 未改配置 / 未删卷
  实例四项只读登记（raw/task161-06-green-run.log 的 TASK161-EVIDENCE server.* 行）：
    innodb_flush_log_at_trx_commit=1、sync_binlog=1、log_bin=ON、version=8.0.46
    （另记 event_scheduler=ON；未改任何 MySQL 配置/仪器、未重置计数器）
  application.yml：hikari maximum-pool-size 命中 0（grep -c = 0）；verify-service/src/main/java/** 自 BASE 零 diff
  隔离：只新增 schema task161_pool_scratch（sed 's/verify_db/task161_pool_scratch/g' 机械改名；
    raw/task161-01-schemas-before.txt / raw/task161-02-schema-create.sql）；演示库 3307 零字节写入
```

### G1 装配正确性（源码级原文）

```
  IT L144: private static final int PRODUCTION_EFFECTIVE_POOL_SIZE = 10;
  IT L218: cfg.setMaximumPoolSize(PRODUCTION_EFFECTIVE_POOL_SIZE);   // 实参 = 10（生产生效默认值）
  IT L219: cfg.setPoolName("task161-pool-it");
  其余全部 Hikari 默认：全文无 connectionTimeout/minimumIdle/maximumLifetime 任何 setter（grep 空）
  IT L171/L175-177: markSent 的 SQL 由生产 VerifyEventOutboxMapper#markSent 的 @Update 注解运行时渲染
    （Mapper 代理逐字；运行期自证 "markSent.source = production VerifyEventOutboxMapper#markSent via
    mapper proxy（每次调用 openSession(true) 从共享池取还连接），无自写 UPDATE"）
  IT L252/L486/L645: 每次调用 openSession(true)（逐行自动提交，从同一池取还连接；
    L227 运行期断言 assertTrue(c.getAutoCommit(), "池连接默认应为 autocommit")；
    L173 控制连接同断言）
  IT L167-168: 缺 TASK161_IT_URL/USER/PASSWORD ⇒ Assumptions.assumeTrue 跳过（rc=0 但不记真库通过）
  IT L672-673: 每条直取连接硬校验 SELECT DATABASE()
  IT L230: 池预热至 getTotalConnections() = 10 再开始测量
```

### G2 15 个窗口硬判据（主证据 run rc=0）

```
  Com_update 增量（A: 1/2/4/8 臂 ×3 轮 + B: N=4,J=8 ×3 轮 = 15 窗口）：
    A N=1: 2000/2000/2000   A N=2: 2000/2000/2000   A N=4: 2000/2000/2000
    A N=8: 2000/2000/2000   B N=4,J=8: 2000/2000/2000     （全部精确 = 2000）
  Com_insert / Com_delete：15 窗口全 0 / 全 0
  收尾 SENT=2000、PENDING=0：15/15 轮成立
  会话门 15/15 干净：客户会话 P_S NAME='thread/sql/one_connection' 计数 = 11 = 池连接 MXBean 实读 10 + 1 控制连接；
    SHOW PROCESSLIST 交叉核对（非 Daemon、非自身会话全部落在 scratch 库）；
    FOREGROUND 原始计数 13（含 event_scheduler 与 compress_gtid_table 两条系统 Daemon，照记为证据）
```

### G3 池指标与 B 组自证（`HikariPoolMXBean` 轮内最大值）

```
  B 组 N=4,J=8：getThreadsAwaitingConnection() 轮内最大 = 2 / 2 / 2（3/3 轮 > 0 ⇒ 该臂有效；
    可用连接 10-8=2 < N=4 强制排队，自证门通过）
  A 组 N=1：awaitMaxOverall = 0（单 worker 串行取还连接无排队，符合预期；A 其余臂同 0）
  activeMax/idleMax/totalMax 轮内最大：A1 1/10/10、A2 2/10/10、A4 4/10/10、A8 8/10/10、B 10/2/10
  口径（TASK-146）：池指标是池级聚合、不可配对单次调用——只用它证明有无排队，
    不拆解单行 markSent 内部构成，不据此命名 fsync/锁/纯 SQL 占比
```

### G4 三档退出码（原文）

```
  变异红 rc=1：注入「shard 0 漏标首行」变异（diff 12 行，raw/task161-07-mutation.diff）→ 既有断言自己翻红：
    org.opentest4j.AssertionFailedError: group=A j=0 arm=1 round=1：Com_update 增量必须精确 = M
    （每行恰好一次单行自动提交 UPDATE，偏离 = harness 缺陷，不得报告该臂标度）；实测=1999 ==>
    expected: <2000> but was: <1999>          （raw/task161-07-mutation-red.log；mutation_run_rc=1）
  字节还原：cp 备份回写（raw/task161-07-it-pristine-backup.java）；恢复后变异标记 0 处；cmp rc=0 字节一致
  还原复绿 rc=0：独立全量重跑（raw/task161-08-restore-green.log）
    S_prod(2)=1.6850 / (4)=3.2883 / (8)=2.8965、S_prod(4)@J=8=1.7794
    （该 run 的 A N=8 臂三轮墙钟 6696.664/8766.349/3554.296 ms 异常，来源未定位，如实登记）
  第三次独立全量 rc=0（raw/task161-08b-restore-green2.log）
    S_prod(2)=1.6946 / (4)=3.1594 / (8)=5.2317、S_prod(4)@J=8=1.7170
  缺变量 skipped rc=0：Tests run: 1, Failures: 0, Errors: 0, Skipped: 1；BUILD SUCCESS
    （raw/task161-05-skip-run.log）—— 按口径不记为真库通过
```

### G5 offline 零扰动（开工与收口各一次，逐位一致）

```
  开工基线 run（raw/task161-03b-baseline-offline.log；完成于 13:50:07，早于 .mvn/maven.config 创建时刻 14:14，
    未受通道影响）rc=0 / BUILD SUCCESS
  收口 run（raw/task161-10-regular-suite.log；通道删除之后）rc=0 / BUILD SUCCESS
  两次七模块数字串逐位一致：36 / 41 / 33 / 103 / 120 / 59 / 10（Skipped 全 0）
  新 *IT 收集数 = 0（verify-service 仍 120；若被默认收集将 > 120）via 收口 log 内
    "Tests run: 120 ... in com.sportverify.verify.*" 汇总与 IT 类名 grep 计数 0
```

### G6 静态门（「不得新增」口径）

```
  --mode=offline --static=verify-service → rc=1：第 1/2 段 clean install -DskipTests BUILD SUCCESS；
  第 2/2 段 checkstyle 失败于 "You have 867 Checkstyle violations"（raw/task161-11-static-gate.log）
  867 ≤ 867（基线 867；不得新增）。spotbugs/pmd 因 checkstyle 先失败而从未执行 = 未覆盖，不得写成通过
```

### G7 词面门（三态 ×4 + 正向对照；权威解释器）

```
  正则现场提取：sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1
    → 长度 26（非空断言通过）；其字面量不内嵌任何入库文件
  四形态全部 ZERO_HIT rc=1：ci-exact / C / zh_CN.UTF-8 / C.UTF-8（无任一 TOOL_ERROR）
  正向对照：探针 rc=0 命中 9/9；探针已删（git status 无残留）
  权威解释器：G7 一律以 D:\git\Git\bin\bash.exe（git 2.20.1.windows.1）执行。已实测登记 harness 事实：
    本机 qoder 自带 git 2.52 + C.UTF-8 会把字节 0x8E/0x9E 折成 CP1252 Ž/ž 大小写对，
    在既有 api/.../MapMatchResultDTO.java 产生 2 处伪命中；权威解释器复现全零命中
  收口态（C4 后）重跑同结果，见文末补记
```

### G8 通道披露（`.mvn/maven.config` 已按纪律删除）

```
  两行原文（逐字节核验，raw/task161-04-maven-config-evidence.txt；cat -A 无 ^M ⇒ LF；wc = 2 行 / 94 字节）：
    -Dtest=VerifyOutboxRelayPoolConcurrencyScalingMysqlIT
    -Dsurefire.failIfNoSpecifiedTests=false
  跑前快照：git status --porcelain .mvn → "?? .mvn/"（同存 raw/task161-03-mvn-status-before.txt）
  跑后快照（四档 run 全部完成之后、删除之前）：git status --porcelain .mvn → "?? .mvn/"（内容未变）
  删除后（raw/task161-09-maven-config-removed.txt）：ls .mvn/maven.config rc=2、ls .mvn rc=2、
    git status --porcelain .mvn 空输出 —— 且发生在任何 git add/commit 之前
  删除后常规套件回基线（见 G5）。未用 MAVEN_OPTS、未改 mvn-verify.sh、未改任何 pom、未裸用 mvn
```

### G9 空白与契约

```
  git diff --check rc=0（工作树与暂存；LF 纪律：CR 计数以 tr -cd '\r' | wc -c 口径核过）
  git show --check：C1 604c6e9 rc=0、C2 040fb22 rc=0、C3 1a18865 rc=0；C4 见文末补记
```

- 在途（C1–C3 已提交、handoff 已建、PLAN 已追加未提交）：`bash scripts/verify/mailbox-contract.sh --open TASK-161 --baseline=291e686a663e78ec7a8d1faeec14b9aeb669bca8`（权威解释器）→ **rc=1**（末行原文：`[contract] 契约校验失败（退出码 1）：判据 A=0 判据 B=1`）。判据 A=0 的原文为 `两件套齐全：TASK-161`（无「进行中」行、无「待办进行中任务 N 个」行 ⇒ 0 个待办进行中）。
  - TASK-161 自身：**「清单多报」0 条**；「改动集未声明」**5 条** = ① 既有脏项 `?? spec/changes/add-verify-degrade-status-index/` 的未跟踪 4 文件（verification.md / tasks.json / proposal.md / specs/sport-record-verify/spec-delta.md；任务书明令不得触碰，原样保留）② 报告 `docs/perf/判别-outbox-relay-池内并发标度.md` 1 条——该路径在改动集侧以 `core.quotepath=true` 的 C 式引用转义形态出现（`"docs/perf/\345…\246.md"`），而契约工具的清单 token 类 `[A-Za-z0-9_./-]+\.(ext)` 无法表达非 ASCII 路径 ⇒ 恒无法进入「只改清单」侧；此条系契约 tool 的表达边界（同一文件恰为任务书第 2 项必交件、已在 C2 入库），**非工作树额外改动**。
  - 整体 rc=1 的其余来源：**53 个历史任务**（TASK-018、TASK-106、TASK-109–122、TASK-124–160）同报判据 B 失败。对 TASK-018/127/159/160 逐任务实测（`.trae/tmp/t161-intersect.sh` 复现 ACTUAL 13 条并与各回传清单求交），与当前 ACTUAL 的**唯一交叠均为公共文件 `work/mailbox/PLAN.md`**——PLAN.md 是本任务 C4 的必改件，在途即天然使全部曾改 PLAN.md 的历史任务「在途」；各任务的「清单多报」皆为其自身历史文件，其「改动集未声明」皆为 TASK-161 在途件与既有脏项（均非本任务额外改动）。此为 TASK-127/159/160 已登记的既知模式，不属本任务。**本任务无任何超范围改动**（原文见 `docs/perf/data/raw/task161-13-contract-inflight.txt`，1219 行）。
- 收口后无参复跑 rc=0（原文见文末补记）。

### G10 外部门槛与不得声称

- **未达外部门槛**（本次不 push，待下次授权由 CI 复验）；offline 绿不得表述为外部门槛绿。
- 本节与 PLAN 验收记录均含原句「**本轮零生产行为变化、零已测收益**」「**未覆盖 (a) 并发 syncSend/broker 吞吐**」「**池占用代理（occupancy proxy）**」；本轮数字未与 TASK-152 的 18.0 ms/行或 TASK-156 的 S(N) 并列成「优化前后」。

### footprint（C1–C3 实测原文；C4 前）

```
  181	0	docs/perf/data/exp-outbox-relay-pool-concurrency-scaling.json
  119	0	"docs/perf/\345\210\244\345\210\253-outbox-relay-\346\261\240\345\206\205\345\271\266\345\217\221\346\240\207\345\272\246.md"
   29	0	spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/proposal.md
   39	0	spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/specs/sport-record-verify/spec-delta.md
   53	0	spec/changes/prove-verify-outbox-relay-pool-concurrency-scaling/tasks.json
  768	0	verify-service/src/test/java/com/sportverify/verify/mapper/VerifyOutboxRelayPoolConcurrencyScalingMysqlIT.java
   29	0	work/mailbox/PLAN.md（工作树追加，C4 提交）
  6 files changed, 1189 insertions(+)      （git diff --shortstat 291e686a..HEAD，C1–C3）
```

## 5. 预登记四条算术假设与 5 行推演表（逐条复述，原样未删改）

预登记：本轮结果**即使全绿也不足以翻默认值**。指导侧用仓库自己的实测数字做过的算术（TASK-145 满批 rows=100 的分段时间 + `fixedDelay` 语义「周期 = 固定等待 + 锁内」，保守假设 `syncSend` 不并行）：

| 配置 | 锁内 | 周期 | 净投递 |
|---|---|---|---|
| 5000ms, N=1（现状） | 1312ms | 6315ms | **14.58 行/s**（TASK-145 实测） |
| 5000ms, N=8 | 246+183=429ms | 5429ms | **≈17.0 行/s（仅 +16%）** |
| 500ms, N=1 | 1452ms | 1941ms | **37.15 行/s**（TASK-145 实测） |
| 500ms, N=4 | 638ms | 1138ms | **≈63 行/s（+70%）** |
| 500ms, N=8 | 506ms | 1006ms | **≈72 行/s（+93%）** |

四条算术假设：① `syncSend` 不并行（保守）；② 满批 rows=100；③ **S(N) 能从 IT 迁移到生产（正是本轮要验的）**；④ 周期 = interval + 锁内。⇒ **默认 5000ms 下并发单独只值 +16%**（5000ms 固定等待占周期 79.2%）；可交付组合是「interval 下调 + 并发」，不是并发单独。上表是**算术推演（Level B，基于已提交测量）不是新测量**。

**本轮结果对假设 ③（S(N) 可迁移）给出的答案（有界）**：在**无池占用压力**（J=0，N ≤ 8 < 10）下池未构成对 S_prod 的可观察削减（`awaitMax` 全 0、S_prod 随 N 单调不减 1.8567 / 3.1538 / 5.6803）；但在**池占用压力**（J=8、可用 2）下 `S_prod(4)` 记录为 1.7756，**落入预注册「证据不足」区间**，且 J 只是**池占用代理（occupancy proxy）**、不是 32~40 个真实消费线程的负载 ⇒ **假设 ③ 未被完整回答**：本轮的池内装配与占用代理都不足以闭合「S(N) 能否迁移到生产（含 Redisson 全局 `tryLock(0)` 单跑与消费者共享池）」；不外推、不换算、不并列——不构成本轮任何吞吐收益声明，也不构成 relay 改造或改默认值的授权。**下一步（第一支门限所要求的 3 格负载因子实验）不属本任务，须由指导侧另立任务指派。**

## 6. 未覆盖项与不得推出的结论

- **未覆盖 (a) 并发 `syncSend`/broker 吞吐** —— 未起四服务、未跑负载、未连 RocketMQ，**零信息**。
- J 是**池占用代理（occupancy proxy）**，不是消费者行为模型；不得声称等价于 32~40 个消费线程的真实负载。
- **spotbugs/pmd 未覆盖**（被 checkstyle 阻断在前），不得写成通过。
- **未 push、未过 CI**：本轮为本地 offline 实跑，外部门槛未达；不得声称已过 CI。
- **不得**据此在生产开启 `relay-send-concurrency > 1`；**不得**宣称生产延迟或吞吐收益（**本轮零生产行为变化、零已测收益**）。
- **不得**把本轮数字与 TASK-152 的 18.0 ms/行（演示实例 3307、不同窗）或 TASK-156 的 S(N)（同 scratch 实例但无池 `DriverManager` 装配）**并列成「优化前后」**；判别量只有同实例同装配内的 S_prod(N)；A 组与 B 组之间不得换算或相减（唯一例外：预登记要求的 `S_prod(4)@J=8` 比值）。
- 不改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 任何默认值；不翻案 TASK-153/154；不改写 TASK-152/156 的任何数字；不实施、不外推。
- 实测边界：单容器单实例、M=2000、3 轮/臂、N ≤ 8、已取列表下标 `i % N` 切分、J=8 单一占用档；绝对值仅本机本窗口有效；池指标为池级聚合、不配对单次调用、不命名 fsync/锁/纯 SQL 占比；S8 跨 run 不稳定（5.6803 / 2.8965 / 5.2317）与一次 A N=8 墙钟异常的来源未定位（如实登记；第三支裁决不依赖 S8）。
- scratch 收尾状态如实登记：`task161_pool_scratch.verify_event_outbox` 留存 2000 行全 SENT；演示库 3307 零字节写入。

## 补记（C4 提交后终检实测，随执行回复回传）

- C4 `git show --check` rc=**0**；`git diff --shortstat 291e686a..HEAD`（含 C4）与四笔入册清单原文随执行回复回传指导侧（本文件提交时无法预含自身哈希，按 TASK-159/160 先例以补记形式登记）。
- 收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=**0**（TASK-161 足迹不在工作树，视为已收口；原文随执行回复回传）。
- 终检 `bash .trae/tmp/task161-verify.sh 291e686a663e78ec7a8d1faeec14b9aeb669bca8`（权威解释器）：G7 收口态四形态 ZERO_HIT + 正向对照 9/9、G9 无参 rc=0、C1–C4 `git show --check` 全 rc=0、footprint 与「零已测收益」「未覆盖 (a)」「池占用代理」三处必备表述计数；全量输出随执行回复回传指导侧。
