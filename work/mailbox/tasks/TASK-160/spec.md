# TASK-160：实现 outbox relay「批内并发投递」（默认关闭，语义逐行不变）+ 三件套

**这是本仓库第一个真正动生产代码的优化轮**（TASK-143~159 全是测量/判别/文档）。默认关闭 ⇒ 本轮**零生产行为变化、零已测收益**；收益须由 TASK-161 的同负载对照来定，**本轮不得声称任何吞吐/延迟改善**。

## 为何是这一件事（实测依据，不是直觉）

1. **调 interval/batch 已撞墙**：默认 5000ms 净投递 ≈**13.4 行/s**、调到 500ms ≈**37 行/s**，而 outbox 创建速率 ≈**59~96 行/s** ⇒ 积压只增不减（TASK-143/144/145，见 `work/mailbox/后端优化机会总览-2026-09-26.md` §3-P2 L120）。
2. **锁内最大段是逐行 `markSent`**：满批占锁持有 **A 79.7% / B 71.4%**（TASK-145），另一轮 **80.1%**（TASK-146）；`syncSend` 只占 18.8%/22.3%。B 臂 `markSent` 已是**整周期占比最高段 ≈53.4%**。
3. **每行一次自动提交 = 一次持久化往返**：TASK-152 同窗测得 **18.0 ms/行**，其中 MySQL 服务端语句事件占 **73.93%**。
4. **两条替代路径已被判 NO-GO**：批末统一标记 SENT（TASK-153，四类语义反对）、`markSent` 线程等待归因（TASK-154，不可拆）。
5. **并发标度成立（仅必要条件）**：TASK-156 实测 S(2)=**1.8612**、S(4)=**3.3066**、S(8)=**5.7056**，随 N 单调不减；聚合吞吐 102.30 → 583.67 行/s。
6. ⇒ **唯一还没被否掉的杠杆就是并发**。本轮把它做成**可开关的代码**，为 TASK-161 的同负载判别提供载体。

## 起点与基线（指导侧 2026-09-29 亲跑一手）

- 起点 HEAD = `6e375c93533e7377cde91d52f9d39376e6853275`（短 `6e375c9`）。**执行侧开工第一件事**：`git rev-parse HEAD` 与任务书内 SHA 核对，不一致**即停手回报**。
- `git rev-list --left-right --count origin/main...main` 开工应为 `0	4`（未推送的四笔：`310b2f1`、`da6e3ee` 为指导侧 PLAN L4 订正，`e0c3ae4`、`6e375c9` 为 TASK-159 的 C1/C2）。**本任务不得 push**。
- offline 基线（指导侧本会话已 **5 次**逐位一致）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，七模块 **36 / 41 / 33 / 103 / 110 / 59 / 10**、Skipped 全 0。
- 静态门基线（指导侧亲跑）：`bash scripts/verify/mvn-verify.sh --mode=offline --static=verify-service` → **rc=1**，第 1/2 段 `clean install -DskipTests` **BUILD SUCCESS**，第 2/2 段 `checkstyle:check` 失败于 **921 项既有违规**；**spotbugs 与 pmd 因 checkstyle 先失败而从未执行 = 未覆盖**。⇒ 本轮静态门只能是**「不得新增」口径**（见 G5），**不是**「必须全绿」。
- 关键环境事实：`verify-service/src/main/resources/application.yml` 的 `hikari` 段**只设了 `initialization-fail-timeout: -1`，没有 `maximum-pool-size`** ⇒ 生效值为 Hikari 默认 **10**；同文件 `rocketmq.consumer.consume-thread-min: 32 / consume-thread-max: 40`。
- `verify_event_outbox` 列（`sql/03-verify-db.sql` L39-L54）：`id / event_id / topic / tag / payload(JSON) / trace_id / status / retry_count / created_at / sent_at`；索引 `PRIMARY(id)`、`uk_event_id`、`idx_status_id(status,id)`。**没有 record_id 列**。
- `VerifyEventProducer.syncSend`（L88-L96）**线程安全**：类只有 `static final` 与两个 `final` 字段（`RocketMQTemplate`、`ObjectMapper`），方法内状态全是局部 `var builder`。指导侧已核，执行侧须自行复核一遍（G0）。

## 只改清单（严格 6 项）

1. `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` —— 加并发开关与批内并发投递路径。
2. `verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java` —— `BatchSummary` 增加 `sendConcurrency` 一个分量（**只加不改既有分量语义**）。
3. `verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java` —— **既有 19 个 `@Test` 一个字都不许改**（它们就是「默认路径未变」的判据，见 G1）；新用例**另建新文件** `VerifyOutboxRelayConcurrencyTest.java`。
4. `verify-service/src/test/java/com/sportverify/verify/mq/RelayDiagnosticsTest.java` —— 仅因 `BatchSummary` 多一个分量而做的**最小必要**改动（构造处补一个实参），不得改既有断言语义。
5. `spec/changes/add-verify-outbox-relay-send-concurrency/` —— 新建三件套（`proposal.md`、`specs/sport-record-verify/spec-delta.md`、`tasks.json`），**纯 ADDED**（见下）。
6. `work/mailbox/PLAN.md`（纯追加 1 节）+ `work/mailbox/tasks/TASK-160/spec.md`（本文件，**不得改写一个字**）+ `work/mailbox/tasks/TASK-160/handoff.md`（新建）。

**不得改**：`application.yml`（含 hikari / rocketmq / 任何默认值）、`VerifyEventOutboxMapper.java`、`VerifyEventProducer.java`、任何 SQL/索引/schema/pom/`scripts/**`/`.github/**`、`docs/perf/**`、其他任务的信箱目录、`spec/changes/` 下其余 18 个在途目录、`?? spec/changes/add-verify-degrade-status-index/`。

## 设计规格（逐条都是硬约束；有冲突即停手回报，不得自行发挥）

### D1 配置开关

- 新键 `verify.outbox.relay-send-concurrency`，`@Value("${verify.outbox.relay-send-concurrency:1}")`，**默认 1**。
- **不写进 `application.yml`**（与既有 `batch-size`/`max-retry`/`relay-interval-ms` 一致，全部靠 `@Value` 默认值；TASK-145 已登记 `applicationYmlHasVerifyOutboxKeys: false` 这一事实，本轮不得改变它）。
- 取值 `< 1` 时**钳到 1** 并在启动/首轮打一条 WARN（不得抛异常导致服务起不来）。**不设上限**，但 javadoc 必须写明「连接池默认 10、消费线程 32~40，实用上界受池约束，未经同负载测量不得调大」。

### D2 默认路径必须可证明未变（这是本轮最重要的约束）

- `concurrency == 1` 时：**不创建任何线程池、不创建任何 `ExecutorService` 对象**，走与起点**逐字等价**的串行 for 循环。判据＝既有 19 个测试不改一字仍全绿（G1）+ 新测试断言并发字段为 `null`（G1b）。
- 实现建议：把「每行处理体」抽成一个私有方法（如 `processRow(row, partials)`），**串行路径与并发路径调用同一个方法** ⇒ 语义只有一份实现，不可能漂移。抽方法时**不得改动任何一行既有语句的顺序、条件、日志文本与异常处理**。

### D3 分区方式：对**已取到的列表**按下标取模，绝不改 SQL

- 取批仍是**唯一一次** `outboxMapper.selectPendingBatch(batchSize, maxRetry)`，SQL、`ORDER BY id`、批次上限、重试上限**全部不动**。
- 拿到 `List<VerifyEventOutbox> batch` 后，按 **`i % N`**（`i` 为列表下标）切成 N 个子列表。
- **为什么不用 `id % N` 的 SQL 分区**（两条独立理由，都要写进 proposal）：
  1. `id % N` 谓词**不可用索引**，N 个 worker 各扫一遍同一段 ⇒ 引入 TASK-156 明确列为**未测**的「(c) 分区感知选取成本」，本轮不制造新的未测项。
  2. 主规格 L989 的要求原文是「WHEN relay **按 ID 顺序批量读取**待投递判定事件」；改 SQL 分区会与该已并入的权威需求冲突，需要 MODIFIED delta。按下标切分**不触碰读取语义**，故三件套可保持**纯 ADDED**。
- **必须是「不重不漏的划分」**：Σ 子列表大小 == `batch.size()`，且所有子列表的 id 集合互不相交、并集 == 原批次 id 集合。G2 直接测这一条（含 N 不整除批大小的情形，如 100 行 / N=3）。

### D4 线程池生命周期

- **懒建**：仅在首次遇到 `concurrency > 1` 时创建；`volatile` 字段持有；**不得每轮新建**。
- `Executors.newFixedThreadPool(n, threadFactory)`；线程**必须是 daemon**、名字带可识别前缀（如 `verify-outbox-relay-`），以免泄漏线程阻塞 JVM 退出、并便于线程转储定位。
- `@PreDestroy` 里 `shutdown()` → 有界 `awaitTermination` → 超时则 `shutdownNow()`；关闭异常只 WARN 不抛。
- **不得**用无界队列提交超过本轮批次的任务；一轮提交**恰好 N 个**任务并 `invokeAll`/`Future.get` 全部 join 后才解锁（锁语义不变：整批仍在 Redisson 锁内完成）。

### D5 逐行语义不变式（**任一条被破坏即判契约红**）

每行仍然、且只仍然：
1. 先恰好一次 `verifyEventProducer.syncSend(row)`；
2. 成功后恰好一次 `outboxMapper.markSent(row.getId())`；
3. `syncSend` 抛错 ⇒ 恰好一次 `outboxMapper.incrRetry(row.getId())`、`failed++`、原样 WARN 日志、**continue**；
4. `markSent` 抛错 ⇒ 恰好一次 `incrRetry`、`failed++`、原样 WARN 日志（既有行为，见起点 L164-L177，**不得改**）；
5. 耗尽行防御分支（`retryCount >= maxRetry`）⇒ `exhausted++` + 原样 ERROR 日志 + **不投递不标记**；
6. `eventId` **永不重新生成**，`topic/tag/payload/traceId` 原样透传；
7. **单行异常绝不允许逃出 worker**（否则一行坏数据会废掉整个子列表）——每个 worker 的循环体必须自己兜住所有异常。

### D6 诊断口径（**本轮最容易做错的地方**）

- 每个 worker 用**自己的局部变量**累计 `sendNanos / markNanos / incrRetryNanos / success / failed / exhausted`，join 后由主线程求和。**不要引入 `AtomicLong`/锁**——局部量求和既正确又零争用。
- `selectNanos`、`lockWaitMs`、`lockProcessingMs`、`lockHoldMs` 仍由主线程单点计时，口径**不得改**（TASK-146/147 已两次订正过这些口径，别推倒）。
- `BatchSummary` **增加 `sendConcurrency` 分量**并在摘要日志里输出。
- **必须在 javadoc 与日志措辞里写明**：并发模式下 `sendMs/markMs/incrRetryMs` 是**各线程墙钟的聚合和（线程时间）**，不再是单条时间轴上的墙钟，因此 `residualMs = lockProcessingMs − Σ段` **可能为负**，**不得**再被读作「未归因的墙钟」。这是 TASK-146 修过的同一类归因错误的复发预防。

### D7 三件套（`spec/changes/add-verify-outbox-relay-send-concurrency/`，**纯 ADDED**）

- `spec-delta.md` 只含 `## ADDED Requirements`，至少 1 条需求 + 覆盖下列场景：默认 1 时不创建线程且行为与引入前一致；`>1` 时 D5 的七条不变式；取批 SQL/批次/重试/锁/周期不变；诊断在并发下标明 `sendConcurrency` 且段值为线程时间聚合、`residual` 可为负。
- **不得**写 MODIFIED（诊断相关需求尚未并入主规格——它属 `measure-verify-outbox-relay-cost` 起的 3 深链，仍是在途未归档目录）。若你发现主规格里确有被你改动的需求，**停手回报**，不要自行改成 MODIFIED。
- `proposal.md` 必须**逐条写入下面「预登记反例」5 条**，并写明「本轮默认关闭、不构成收益」。
- `tasks.json` 照既有格式（数组，每项含 `steps[]`（`completed`）与 `passes`）。

## 预登记反例（**必须原样进 proposal 与 handoff，不得在收口时删改或淡化**）

1. **连接池会吃掉一部分标度**：`hikari` 未设 `maximum-pool-size` ⇒ 生效 **10**；而 `consume-thread-min/max = 32/40` 的消费者线程也要连接。N 个 relay worker 与消费者**争同一个 10 连接的池**。TASK-156 的 S(N) 用的是**每线程独立 `DriverManager` 自动提交连接（完全无池）**，故**生产 S(N) 必然低于 IT 的 S(N)**；TASK-156 自己的 boundary 原文就写了「scratch 真库 + test-only DriverManager IT ≠ 生产 relay（Spring/Hikari + Redisson 全局 tryLock(0) 单跑）」。
2. **不得靠调大池来兑现 S(N)**：TASK-141 已实测「仅调池容量 10→20 **不存在稳定收益**」。
3. **批内发送顺序不再按 id 升序**（跨 worker 交错）。依据是 TASK-156 的只读核查（`VerifyEventConsumer`/`LeaderboardEventConsumer` 无同 recordId 顺序依赖、本就 `MessageListenerConcurrently` 并发消费、`RECONSUME_LATER` 重投天然重排），**但「消费端幂等不自动等于授权改 relay」** ⇒ 必须以 spec delta 明示，不得当作既成事实。
4. **「按 recordId 分区」在当前 schema 下不可直接实现**：`verify_event_outbox` **没有 record_id 列**，recordId 只在 `payload` JSON 里。本轮用**已取批列表的下标取模**，既不碰 SQL 也不碰 schema。若将来确需按 recordId 保序分区，须另立**含 schema 变更**的提案。
5. **默认关闭 ⇒ 本轮零已测收益**：不得引用 S(8)=5.7056、18.0 ms/行、13.4/37 行/s 中任何一个当作**本轮**的收益；那些是历史测量，证据等级不因本轮而升级。

## 收口门槛（每条回传实测退出码/数字原文，不接受散文）

- **G0 起点与只读复核**：`git rev-parse HEAD` == 任务书 SHA；自行复核 `syncSend` 无共享可变状态、`hikari` 无 `maximum-pool-size`、`verify_event_outbox` 无 `record_id` 列，三项各回传一行实测原文。
- **G1 默认路径未变（硬，本轮最强判据）**：`VerifyOutboxRelayTest.java` 的 **19 个既有 `@Test` 一字未改**（回传 `git diff --numstat` 对该文件应为 **0 0** 或该文件根本不在改动清单里）且全绿；**G1b**：新测试断言 `concurrency=1` 时并发执行器字段为 `null`（从未创建线程），回传该用例名与结果。
- **G2 不重不漏划分（硬）**：新测试覆盖 `batch=100, N∈{1,2,3,8}`，断言 Σ子列表大小 == 100、id 集合互不相交且并集完整、**每行 `syncSend` 与 `markSent` 各恰好一次**（用 mock 计数）。回传用例名与断言数。
- **G3 失败隔离（硬）**：新测试覆盖「某行 `syncSend` 抛错」「某行 `markSent` 抛错」「耗尽行混在中间」三种，断言其余行仍被完整处理、`failed/exhausted/success` 计数正确、异常未逃出 worker。
- **G4 offline 全绿**：`bash scripts/verify/mvn-verify.sh --mode=offline test` rc=**0**、BUILD SUCCESS；**6 个非 verify-service 模块数字与基线逐位一致（36/41/33/103/59/10）**；verify-service = **110 + 新增用例数**，Failures/Errors/Skipped 全 **0**。回传完整七模块串与新增用例数。
- **G5 静态门（「不得新增」口径，非全绿）**：`bash scripts/verify/mvn-verify.sh --mode=offline --static=verify-service` 的 checkstyle 违规数 **≤ 921**（回传实测数与该行的 ERROR 原文）。**spotbugs/pmd 仍未覆盖**（被 checkstyle 阻断在前），如实登记为未覆盖，**不得写成通过**。
- **G6 词面门（三态 + 正向对照）**：正则**从 `ci.yml` 现场提取**（`sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1`，长度应为 **26**），断言非空否则 `exit 9`。跑 4 形态（CI 原样用 `env -u LC_ALL -u LANG`、`LC_ALL=C`、`zh_CN.UTF-8`、`C.UTF-8`），**全部须 rc=1**；任一 rc∉{0,1} 判**门槛失败**而非通过。**必须做正向对照**（临时探针文件 → 期望命中 → 删除 → 回传删除后的 `git status`）。**任何要入库的文件都不得内嵌该正则的字面量**（TASK-158 的 C4 就是因此把 CI 门槛打破过）。
- **G7 空白**：`git diff --check` rc=**0**；每笔提交 `git show --check` rc=**0**。
- **G8 契约**：在途 `bash scripts/verify/mailbox-contract.sh --open TASK-160 --baseline=<起点SHA>` 记 rc（rc=1 时须逐条说明过冲**仅**来自既有脏项）；**收口后无参复跑必须 rc=0**。
- **G9 外部门槛**：不 push ⇒ PLAN 验收记录该栏写「**未达外部门槛**（本次不 push，待下次授权由 CI 复验）」。
- **G10 不得声称收益（硬）**：`handoff.md` 与 PLAN 记录里必须出现「默认关闭 ⇒ 零生产行为变化、零已测收益」，且**不得**出现把 S(N)/18.0 ms/13.4 行/s 当作本轮成果的表述。指导侧复验收时会逐字搜。

## 提交结构

- **C1 = 业务代码**：第 1~4 项（`VerifyOutboxRelay.java`、`RelayDiagnostics.java`、新测试文件、`RelayDiagnosticsTest.java` 最小改动）。
- **C2 = 三件套**：第 5 项。
- **C3 = 台账**：第 6 项（PLAN 纯追加 + 本文件 + handoff）。
每笔 `git diff --cached --name-only` 只含预期路径，回传三份清单。**不 push、不建 PR、不 `git stash`、不 `git add -A`/`add .`**（逐路径 add）。

## 可直接使用的自检脚本（写到 `.trae/tmp/task160-verify.sh`，UTF-8 无 BOM，用 bash 跑）

```bash
#!/usr/bin/env bash
# 用法： bash .trae/tmp/task160-verify.sh <起点SHA>
# 注意：需要抓非零 rc 的段落不要放在 set -e 下（指导侧已两次亲踩）。
cd /d/code/sports || exit 9
BASE="${1:?need base sha}"
RELAY=verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
OLDTEST=verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java
NEWTEST=verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayConcurrencyTest.java
mkdir -p .trae/tmp

echo '== G0 只读事实复核 =='
P=verify-service/src/main/java/com/sportverify/verify/mq/VerifyEventProducer.java
echo "  producer: all private fields   = $(grep -cE '^[[:space:]]*private ' "$P")"
echo "  producer: final/static-final   = $(grep -cE '^[[:space:]]*private (static final|final) ' "$P")"
echo "    (two numbers must be EQUAL => no shared mutable state; POSIX grep has no lookahead, so compare counts)"
grep -nE '^[[:space:]]*private ' "$P" | sed 's/^/    /'
echo "  hikari maximum-pool-size present? -> $(grep -c 'maximum-pool-size' verify-service/src/main/resources/application.yml) (expect 0)"
# record_id 必须只在 outbox 的 CREATE TABLE 块内查（该 SQL 文件里别的表确有 record_id 列，
# 全文件 grep 会给 5 而不是 0 —— 指导侧首版就写错了这个判据，已订正）
echo "  outbox CREATE TABLE block record_id count = $(awk '/CREATE TABLE IF NOT EXISTS .verify_event_outbox./,/^\) ENGINE/' sql/03-verify-db.sql | grep -c 'record_id') (expect 0)"
echo "  whole-file record_id count (for contrast, NOT a criterion) = $(grep -c 'record_id' sql/03-verify-db.sql)"
echo "  relay-send-concurrency in relay source:"
grep -n 'relay-send-concurrency' "$RELAY" | sed 's/^/    /'
echo "    (expect EMPTY before implementation; after implementation must show default :1)"

echo '== G1 既有 19 个 relay 测试一字未改 =='
git diff --numstat "$BASE" HEAD -- "$OLDTEST"; echo "  (expect empty output = untouched)"
echo "  existing @Test count now = $(grep -c '@Test' "$OLDTEST") (expect 19)"
echo '== G1b / G2 / G3 新测试存在性与用例清单 =='
if [ -f "$NEWTEST" ]; then
  echo "  new test file lines=$(wc -l < "$NEWTEST") @Test=$(grep -c '@Test' "$NEWTEST")"
  grep -n '    void ' "$NEWTEST" | sed 's/^/    /'
else echo "  MISSING $NEWTEST  <== 判红"; fi

echo '== G4 offline 全绿（数字串比对）=='
echo '  请单独跑： bash scripts/verify/mvn-verify.sh --mode=offline test'
echo '  基线七模块串： 36 / 41 / 33 / 103 / 110 / 59 / 10'

echo '== G5 静态门（不得新增，基线 921）=='
echo '  请单独跑： bash scripts/verify/mvn-verify.sh --mode=offline --static=verify-service'
echo '  从日志取： grep -o "You have [0-9]* Checkstyle violations"'

echo '== G6 词面门：提取 + 三态 + 正向对照 =='
RE=$(sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1)
if [ -z "$RE" ]; then echo "  G6_FATAL: extraction empty"; exit 9; fi
echo "  extracted regex length=${#RE} (expect 26)"
rep3() { case $2 in
    0) echo "  G6[$1] HITS rc=0 <== 判红"; sed 's/^/      /' "$3" ;;
    1) echo "  G6[$1] ZERO_HIT rc=1 <== 期望" ;;
    *) echo "  G6[$1] TOOL_ERROR rc=$2 <== 判失败不是通过"; head -2 "$3" | sed 's/^/      /' ;; esac; }
env -u LC_ALL -u LANG git grep -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > .trae/tmp/g6a.txt 2>&1
rep3 ci-exact $? .trae/tmp/g6a.txt
for loc in C zh_CN.UTF-8 C.UTF-8; do
  LC_ALL=$loc git grep -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > ".trae/tmp/g6-$loc.txt" 2>&1
  rep3 "$loc" $? ".trae/tmp/g6-$loc.txt"
done
python - <<'PY'
ws=["\u9762\u8bd5","\u5f39\u836f","\u5927\u5382","\u516b\u80a1","\u7b80\u5386","\u6c42\u804c","\u7a81\u51fb","\u9644\u5f55A","\u9644\u5f55 A"]
open("g6probe-DELETEME.txt","w",encoding="utf-8").write(
  "line1 clean\n"+"".join("line%d has %s\n"%(i+2,w) for i,w in enumerate(ws))+"tail clean\n")
print("  probe written with %d banned forms" % len(ws))
PY
git grep --untracked -n -I -iE "$RE" -- g6probe-DELETEME.txt > .trae/tmp/g6p.txt 2>&1
echo "  G6_positive_control rc=$? hits=$(wc -l < .trae/tmp/g6p.txt) (expect rc=0 hits=9)"
rm -f g6probe-DELETEME.txt
echo '  probe removed; git status --porcelain:'; git status --porcelain | sed 's/^/      /'

echo '== G7 =='
git diff --check; echo "  G7_diffcheck_rc=$? expect=0"
for c in $(git rev-list "$BASE"..HEAD); do git show --check "$c" >/dev/null 2>&1; echo "  show_check $(git rev-parse --short $c) rc=$?"; done

echo '== G8 =='
bash scripts/verify/mailbox-contract.sh > .trae/tmp/g8.log 2>&1; echo "  G8_contract_noarg_rc=$? expect=0"; tail -2 .trae/tmp/g8.log | sed 's/^/    /'

echo '== G10 不得声称收益：搜 handoff 与 PLAN 的必备句 =='
grep -c '默认关闭' work/mailbox/tasks/TASK-160/handoff.md 2>/dev/null | sed 's/^/  handoff 含「默认关闭」次数=/'
echo '== footprint =='
git diff --numstat "$BASE" HEAD | sed 's/^/  /'
git diff --shortstat "$BASE" HEAD | sed 's/^/  /'
```

注：G0 的「无共享可变状态」判据用**两个计数相等**表达（POSIX grep 无 lookahead，不能写否定模式）：`private` 字段总数必须等于 `private (static final|final)` 字段数，并回传 `grep -nE` 的字段原文供指导侧逐行核。

## 交回物（`handoff.md` 必含，缺一项即视为未收口）

1. 结论一句话（是否实现了默认关闭的并发投递路径、语义不变式是否守住、**明确写「零已测收益」**）。
2. 起点 SHA 与三笔提交 SHA；每笔 `git diff --cached --name-only` 原文；`git diff --shortstat <起点>..HEAD`。
3. 只改清单逐项对齐（6 项，含「未改动」的显式声明，特别是 `application.yml` / Mapper / Producer / SQL / pom）。
4. **G0–G10 全部实测输出与退出码原文**（含 G1 的 `numstat` 空输出、G2/G3 的用例名、G4 完整七模块串、G5 的 921 对比行原文、G6 四形态 rc + 正向对照 rc/命中数 + 探针已删的 `git status`）。
5. **预登记反例 5 条逐条复述**（不得删改、不得淡化），并逐条说明本轮是否触碰。
6. 未覆盖项与不得推出的结论（至少：spotbugs/pmd 未覆盖；未跑同负载 A/B ⇒ **无任何吞吐/延迟结论**；未起四服务、未连真 broker/真库；未 push 未过 CI；生产 S(N) 必然低于 TASK-156 的 IT S(N)，具体低多少**未知**）。

## 指导侧复验收方式

指导侧将**亲自重跑** offline 套件与静态门、逐字 diff 三笔提交、并**逐字搜 G10 的必备句与预登记反例 5 条**。判红的三种情形：① 既有 19 个 relay 测试被改动（哪怕只是重排）；② `application.yml` 或任何默认值被改；③ handoff/PLAN 出现把历史数字当本轮收益的表述。任一即**契约红、要求回滚重做**。
