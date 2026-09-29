# TASK-160 handoff：outbox relay 批内并发投递（默认关闭）+ 三件套

## 1. 结论一句话

已实现**默认关闭**的批内并发投递路径：新键 `verify.outbox.relay-send-concurrency` 默认 1（不写入 application.yml），取值 1 时不创建任何线程池/线程、走与起点逐字等价的串行路径，既有 19 个 relay 测试一字未改且全绿（G1/G1b 判据成立）；取值大于 1 时懒建 daemon 线程池、把**已取到的批次**按列表下标 `i % N` 切成不重不漏的 N 份、一轮恰好 N 个任务全部 join 后才解锁，串行与并发共用同一个私有单行处理体，逐行七条不变式与单行异常 worker 兜底全部有测试锁定（G2/G3）。诊断 `BatchSummary` 增加 `sendConcurrency` 分量，并发下段值为各线程墙钟的聚合和（线程时间）、residualMs 如实可为负，javadoc 与摘要日志措辞均已写明。**默认关闭 ⇒ 零生产行为变化、零已测收益**——收益须由 TASK-161 的同负载对照来定，本轮不声称任何吞吐/延迟改善。

## 2. 起点 SHA 与三笔提交

- 起点 HEAD = `6e375c93533e7377cde91d52f9d39376e6853275`（开工 `git rev-parse` 逐位核对一致；`git rev-list --left-right --count origin/main...main` = `0	4`）。
- C1 = `0ee26df`（业务代码）、C2 = `b115897`（三件套）、C3 = 见本文件落库时的提交（台账：PLAN 纯追加 + 本文件 + TASK-160 spec.md 原样入库）。
- C1 `git diff --cached --name-only` 原文：

```
verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java
verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
verify-service/src/test/java/com/sportverify/verify/mq/RelayDiagnosticsTest.java
verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayConcurrencyTest.java
```

- C2 `git diff --cached --name-only` 原文：

```
spec/changes/add-verify-outbox-relay-send-concurrency/proposal.md
spec/changes/add-verify-outbox-relay-send-concurrency/specs/sport-record-verify/spec-delta.md
spec/changes/add-verify-outbox-relay-send-concurrency/tasks.json
```

- C3 `git diff --cached --name-only` 原文：`work/mailbox/PLAN.md`、`work/mailbox/tasks/TASK-160/spec.md`、`work/mailbox/tasks/TASK-160/handoff.md`（3 路径）。
- `git diff --shortstat 6e375c9..HEAD`（C1+C2 后实测）：`7 files changed, 1036 insertions(+), 92 deletions(-)`；C3 后全量 shortstat 见 PLAN 验收记录表。
- 全程未 push、未建 PR、未 `git stash`、未 `git add -A`/`add .`（逐路径 add）。

## 3. 只改清单逐项对齐（6 项）

以下为本轮实际改动的完整清单（契约判据 B 口径）：

```
verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java
verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java
verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayConcurrencyTest.java
verify-service/src/test/java/com/sportverify/verify/mq/RelayDiagnosticsTest.java
spec/changes/add-verify-outbox-relay-send-concurrency/proposal.md
spec/changes/add-verify-outbox-relay-send-concurrency/specs/sport-record-verify/spec-delta.md
spec/changes/add-verify-outbox-relay-send-concurrency/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-160/spec.md
work/mailbox/tasks/TASK-160/handoff.md
```

逐项说明（spec.md「只改清单」6 项对齐）：

1. `VerifyOutboxRelay.java` —— 已改（并发开关、批内并发、单行处理体抽取、诊断求和；C1）。
2. `RelayDiagnostics.java` —— 已改（`BatchSummary` 增加 `sendConcurrency` 分量，只加不改既有分量语义；并发 residual 不钳零；C1）。
3. `VerifyOutboxRelayTest.java` —— **显式声明：未改动一个字**（`git diff --numstat <起点>..HEAD` 对该文件输出为空，即 G1 判据；既有 19 个 `@Test` 全绿）。新用例全部在新文件 `VerifyOutboxRelayConcurrencyTest.java`（C1 新建，441 行、10 用例）。
4. `RelayDiagnosticsTest.java` —— 已改（仅 4 处 `batch(...)` 构造各补 1 个 `sendConcurrency` 实参，其中 1 处因行宽 80 拆为两行；既有断言语义零改动；C1）。
5. `spec/changes/add-verify-outbox-relay-send-concurrency/` —— 新建三件套，**纯 ADDED**（`## ADDED Requirements`，无任何 MODIFIED；C2）。
6. `work/mailbox/PLAN.md`（C3 纯追加 1 节，既有行零改动）+ `work/mailbox/tasks/TASK-160/spec.md`（指导侧原样首次入库，执行侧一个字未改）+ `work/mailbox/tasks/TASK-160/handoff.md`（本文件，C3 新建）。

**显式未改动声明**：`verify-service/src/main/resources/application.yml`（含 hikari/rocketmq/任何默认值）、`VerifyEventOutboxMapper.java`、`VerifyEventProducer.java`、任何 SQL/索引/schema/pom/`scripts/**`/`.github/**`、`docs/perf/**`、其他任务的信箱目录、`spec/changes/` 下其余 18 个在途目录、`?? spec/changes/add-verify-degrade-status-index/`（保持未跟踪原样）——以上全部零触碰（收口 footprint 与 `git status --porcelain` 为证，见第 4 项 G6/footprint 段）。

## 4. G0–G10 全部实测输出与退出码原文

以下为 C1/C2 后收口跑（`bash .trae/tmp/t160-final.sh`，内含 `bash .trae/tmp/task160-verify.sh 6e375c93533e7377cde91d52f9d39376e6853275`）的实测原文；C3 为纯文档提交（零 .java 改动），G4/G5/G6 读数不因 C3 变化，C3 的 `git show --check` 与无参契约读数以补记方式落在本文件末尾与 PLAN。

### G0 起点与只读事实复核（三项各一行原文）

```
  producer: all private fields   = 3
  producer: final/static-final   = 3
    41:    private static final String STATUS_PENDING = "PENDING";
    43:    private final RocketMQTemplate rocketMQTemplate;
    44:    private final ObjectMapper objectMapper;
  hikari maximum-pool-size present? -> 0 (expect 0)
  outbox CREATE TABLE block record_id count = 0 (expect 0)
  whole-file record_id count (for contrast, NOT a criterion) = 5
  relay-send-concurrency in relay source:
    37: * <p>批内并发投递（TASK-160，默认关闭）：{@code verify.outbox.relay-send-concurrency}
    96:    @Value("${verify.outbox.relay-send-concurrency:1}")
    117:     * <p>并发投递口径（TASK-160）：{@code relay-send-concurrency}
    239:            log.warn("verify.outbox.relay-send-concurrency={} 无效（< 1），"
```

producer 两计数相等（3==3）⇒ 无共享可变状态；`syncSend` 方法内状态全为局部 `builder`（类只有 static final 与两个 final 字段）。开工态该键为空、实现后显示默认 `:1`。

### G1 既有 19 个 relay 测试一字未改（硬）

```
== G1 既有 19 个 relay 测试一字未改 ==
  (expect empty output = untouched)
  existing @Test count now = 19 (expect 19)
```

`git diff --numstat <起点>..HEAD -- VerifyOutboxRelayTest.java` 输出为**空**（该文件根本不在改动集），19 个 `@Test` 全绿（G4 中 `VerifyOutboxRelayTest` = Tests run: 19, Failures: 0, Errors: 0, Skipped: 0）。

### G1b / G2 / G3 新测试存在性与用例清单（硬）

```
  new test file lines=441 @Test=10
    154:    void concurrencyOne_neverCreatesExecutorPool() throws Exception {
    178:    void partition_isExhaustiveAndDisjoint() throws Exception {
    213:    void sendFailure_isIsolated_restOfBatchStillDelivered() throws Exception {
    249:    void markSentFailure_incrRetryOnce_restProceeds() throws Exception {
    276:    void exhaustedRow_mixedInBatch_isSkippedNotDelivered() throws Exception {
    308:    void unexpectedRowException_isContainedByWorker() throws Exception {
    340:    void concurrencySummary_reportsThreadTimeAndNegativeResidual()
    373:    void poolReused_acrossRounds_daemonThreads() throws Exception {
    401:    void preDestroyShutsDownPool_serialPathSafeWithoutPool() throws Exception {
    422:    void invalidConcurrency_clampedToOneWithWarn_serialPath() throws Exception {
```

- G1b：`concurrencyOne_neverCreatesExecutorPool` —— 默认与显式 1 各跑一轮后断言执行器字段为 `null`（从未创建线程池），绿。
- G2：`partition_isExhaustiveAndDisjoint` —— batch=100、N∈{1,2,3,8} 四轮，断言 syncSend 总数 100、每个 id 的 payload 恰好出现 1 次（不重不漏、Σ子列表大小==100）、`markSent(id)` 每 id 恰好 1 次、incrRetry 从未调用；绿。
- G3：`sendFailure_isIsolated_restOfBatchStillDelivered`（某行 syncSend 抛错，其余 6 行完整处理、failed=1/success=6）、`markSentFailure_incrRetryOnce_restProceeds`（markSent 抛错恰一次 incrRetry、其余 4 行成功、success=4/failed=1）、`exhaustedRow_mixedInBatch_isSkippedNotDelivered`（耗尽行混中间不投不标、exhausted=1/success=3）、`unexpectedRowException_isContainedByWorker`（syncSend 抛错后 incrRetry 再抛错，异常被 worker 循环体兜住、同子列表其余行继续、relay 不抛）——全部绿。

### G4 offline 全绿

`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，七模块汇总行原文：

```
Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

6 个非 verify-service 模块与基线 **36/41/33/103/59/10** 逐位一致；verify-service = **120 = 110 + 10 新增用例**；Failures/Errors/Skipped 全 0。

### G5 静态门（「不得新增」口径）

`bash scripts/verify/mvn-verify.sh --mode=offline --static=verify-service` → **rc=1**，第 1/2 段 `clean install -DskipTests` BUILD SUCCESS，第 2/2 段 checkstyle 失败于：

```
You have 867 Checkstyle violations
```

**867 ≤ 921**（基线 921）。本轮在使新增违规归零的同时，顺带修掉了部分既有违规（record 组件 javadoc 补 @param、构造器/新方法参数补 final、RelayDiagnostics 文件末补换行等，净减 54 项）。**spotbugs 与 pmd 因 checkstyle 先失败而从未执行＝未覆盖**（BUILD FAILURE 的 rc=1 正保证这一点），如实登记，不写成通过。

### G6 词面门（三态 + 正向对照）

```
  extracted regex length=26 (expect 26)
  G6[ci-exact] ZERO_HIT rc=1 <== 期望
  G6[C] ZERO_HIT rc=1 <== 期望
  G6[zh_CN.UTF-8] ZERO_HIT rc=1 <== 期望
  G6[C.UTF-8] ZERO_HIT rc=1 <== 期望
  probe written with 9 banned forms
  G6_positive_control rc=0 hits=9 (expect rc=0 hits=9)
  probe removed; git status --porcelain:
      ?? spec/changes/add-verify-degrade-status-index/
      ?? work/mailbox/tasks/TASK-160/
```

正则从 ci.yml 现场提取（长度 26、非空断言通过；其字面量不入任何要入库的文件）。四形态全部 ZERO_HIT rc=1（无任一 TOOL_ERROR）；正向对照探针命中 9/9 后已删除，`git status --porcelain` 只剩两个既有未跟踪目录。

### G7 空白

```
  G7_diffcheck_rc=0 expect=0
  show_check b115897 rc=0
  show_check 0ee26df rc=0
```

`git diff --check` rc=0；C1/C2 两笔 `git show --check` rc=0（C3 的 `git show --check` 见文末补记）。

### G8 契约

- 在途（C1/C2 已提交、handoff 未建）：`bash scripts/verify/mailbox-contract.sh --open TASK-160 --baseline=6e375c93533e7377cde91d52f9d39376e6853275` → **rc=1（判据 A=0、判据 B=1）**。判据 A 的 TASK-160 已声明放行；判据 B=1 的过冲**仅**来自：① 既有脏项 `?? spec/changes/add-verify-degrade-status-index/` 的未跟踪文件（任务书明令不得触碰）；② TASK-160 自身当时尚未入库的 `work/mailbox/tasks/TASK-160/spec.md` 与 C2 刚入库的三件套文件（--baseline 模式把起点以来全部改动算进实际改动集）；③ TASK-131/142/145/146/147/155 对主规格/PLAN 等公共文件的历史清单交叠（TASK-159 验收记录已登记的既知模式，不属本任务改动）。无任何一项来自本任务对他人文件的超范围改动。
- 收口后无参复跑：**rc=0**（见文末补记原文；TASK-160 足迹全部入库后视为已收口）。

### G9 外部门槛

**未达外部门槛**（本次不 push，待下次授权由 CI 复验）。PLAN 验收记录该栏原文同此。

### G10 不得声称收益（硬）

本文件与 PLAN 验收记录均含原句「**默认关闭 ⇒ 零生产行为变化、零已测收益**」；S(2)/S(4)/S(8)、18.0 ms/行、13.4/37 行/s 等历史数字只出现在「预登记反例」的逐条转述中（第 5 项反例 5 明文禁止当作本轮收益），全文不存在把任何历史测量表述为本轮成果的句子。

### footprint（C1+C2 后）

```
  34	0	spec/changes/add-verify-outbox-relay-send-concurrency/proposal.md
  101	0	spec/changes/add-verify-outbox-relay-send-concurrency/specs/sport-record-verify/spec-delta.md
  52	0	spec/changes/add-verify-outbox-relay-send-concurrency/tasks.json
  63	13	verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java
  340	75	verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
  5	4	verify-service/src/test/java/com/sportverify/verify/mq/RelayDiagnosticsTest.java
  441	0	verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayConcurrencyTest.java
   7 files changed, 1036 insertions(+), 92 deletions(-)
```

## 5. 预登记反例 5 条逐条复述（原样收录，未删改、未淡化）

1. **连接池会吃掉一部分标度**：`hikari` 未设 `maximum-pool-size` ⇒ 生效 **10**；而 `consume-thread-min/max = 32/40` 的消费者线程也要连接。N 个 relay worker 与消费者**争同一个 10 连接的池**。TASK-156 的 S(N) 用的是**每线程独立 `DriverManager` 自动提交连接（完全无池）**，故**生产 S(N) 必然低于 IT 的 S(N)**；TASK-156 自己的 boundary 原文就写了「scratch 真库 + test-only DriverManager IT ≠ 生产 relay（Spring/Hikari + Redisson 全局 tryLock(0) 单跑）」。—— 本轮触碰与否：**未触碰**（application.yml 与任何池参数零改动；该约束已写入 relay javadoc 与 proposal，提醒调大并发前必须先做同负载测量）。
2. **不得靠调大池来兑现 S(N)**：TASK-141 已实测「仅调池容量 10→20 **不存在稳定收益**」。—— 本轮触碰与否：**未触碰**（无任何池调参动作，也未提出调参建议）。
3. **批内发送顺序不再按 id 升序**（跨 worker 交错）。依据是 TASK-156 的只读核查（`VerifyEventConsumer`/`LeaderboardEventConsumer` 无同 recordId 顺序依赖、本就 `MessageListenerConcurrently` 并发消费、`RECONSUME_LATER` 重投天然重排），**但「消费端幂等不自动等于授权改 relay」** ⇒ 必须以 spec delta 明示，不得当作既成事实。—— 本轮触碰与否：**按反例所述如实实现并以 delta 明示**（并发路径子列表内保 ID 相对序、跨 worker 交错；spec-delta 的 Requirement 3/4 与 proposal What Changes 第 3/4 条写明划分方式与顺序语义，未做任何保序承诺）。
4. **「按 recordId 分区」在当前 schema 下不可直接实现**：`verify_event_outbox` **没有 record_id 列**，recordId 只在 `payload` JSON 里。本轮用**已取批列表的下标取模**，既不碰 SQL 也不碰 schema。若将来确需按 recordId 保序分区，须另立**含 schema 变更**的提案。—— 本轮触碰与否：**未触碰**（零 schema/SQL 改动；G0 实测 outbox 建表块 record_id 计数 0）。
5. **默认关闭 ⇒ 本轮零已测收益**：不得引用 S(8)=5.7056、18.0 ms/行、13.4/37 行/s 中任何一个当作**本轮**的收益；那些是历史测量，证据等级不因本轮而升级。—— 本轮触碰与否：**未触碰**（`relay-send-concurrency` 默认 1 且未写入 application.yml，生产行为零变化；上述数字仅以反例身份出现，未构成任何收益表述）。

## 6. 未覆盖项与不得推出的结论

- **spotbugs/pmd 未覆盖**：静态三件套中 checkstyle 先失败（867 项既有违规使 `checkstyle:check` 退出非零），spotbugs 与 pmd 从未执行；本轮如实登记为未覆盖，不得写成通过。
- **未跑同负载 A/B ⇒ 无任何吞吐/延迟结论**：本轮零性能实验（未跑 run-perf、未压测、无任何 A/B），并发开关的收益与代价完全未知；S(2)/S(4)/S(8) 是 TASK-156 在无池 DriverManager IT 环境的历史测量，不构成本轮证据。
- **未起四服务、未连真 broker/真库**：全部验证为 offline 单测 + 静态扫描；未启动任何服务、未连真实 RocketMQ broker、未连真实 MySQL（连 offline 基线的 IT 也未跑）。
- **未 push、未过 CI**：三笔提交仅在本地 main；外部门槛未达（G9），offline 绿不得表述为外部门槛绿。
- **生产 S(N) 必然低于 TASK-156 的 IT S(N)，具体低多少未知**：生产 relay 走 Spring/Hikari（池默认 10，与消费线程 32~40 共享）+ Redisson 全局 tryLock(0)，与 IT 的每线程独立连接完全不可比；在 TASK-161 同负载对照给出实测前，任何生产并发数与吞吐预期都无从声称。
- 追加：并发路径仅由 mock 单测覆盖（G2/G3），未经真实中间件与生产流量验证；TASK-161 判别完成前，**不得在生产环境开启 `>1`**。

## 补记（C3 提交后终检实测，随本文件同笔落库后于执行回复回传）

- C3 `git show --check` rc=0；`git diff --shortstat 6e375c9..HEAD`（含 C3）与三笔 `git diff --cached --name-only` 见执行回复。
- 收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=0（原文见执行回复）。
- 终检 `bash .trae/tmp/task160-verify.sh 6e375c93533e7377cde91d52f9d39376e6853275` 全量输出见执行回复（G7 三笔 show_check 全 0、G8 rc=0、G10 必备句计数）。
