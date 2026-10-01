# TASK-165 spec：规格授权「有界分块标记 SENT」＋默认关闭实现（不跑负载、不改任何默认值）

## 0. 硬约束与红线（先读，违反即红）
1. 本任务书是唯一权威。开工先逐位核对 §3 的起点与开工读数；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与文件名参数）。中文一律先写 `.trae/tmp/` 下 UTF-8 无 BOM 文件再用；提交信息走 `git commit -F <file>`。
3. bash 一律**写成 .sh 文件**再用 `D:\git\Git\bin\bash.exe <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；禁裸 mvn、禁 `MAVEN_OPTS`、禁创建仓库根 `.mvn/maven.config`。跑 offline 前确认 **sports 的 java 进程为 0**（jar 锁会让 clean FAILURE）；宿主机上属于其它项目的 java 进程**不许杀**，只记录数量。
5. 不 push、不建 PR、不 `git stash`、不 `git add -A`/`add .`（逐路径 add）。既有脏项 `spec/changes/add-verify-degrade-status-index/` 与容器 `task131-scratch-mysql` **零触碰**。
6. **本轮零生产行为变化**：新键默认关闭且**不写入 `application.yml`**（照 TASK-160 先例）；不跑任何负载、不起四服务、不改 `relay-interval-ms`（已落地 500）、不改 `relay-send-concurrency`（仍 1）、不改 `batch-size`/`max-retry`/SQL 既有三方法/pom/scripts/索引。
7. 不翻案、不改写任何已入库结论与数字（TASK-143/144/145/146/152/153/154/156/159/160/161/162/163/164）。特别是 **TASK-153 的 NO-GO 在其当时的规格下依然成立、一字不动**；本轮是**新增规格授权**后另立边界，不是推翻它。
8. 任何门槛未跑一律写「未覆盖」，不得推断、不得用 offline 绿冒充外部门槛绿。

## 1. 唯一问题与本轮性质
**问题**：`markSent` 已被实测为 relay 锁内最大成本段（TASK-164：每行 7.80~9.97 ms，占每行锁内墙钟 10.59~12.89 ms 的 **72~77%**）。把「每行一条条件 UPDATE」改为「每 chunk 一条条件 UPDATE」能把 SQL 往返次数降到 1/chunk，但 TASK-153 在**现行规格**下判 **NO-GO**（扩大重复投递窗口、SENT 可见性延迟、`sent_at` 语义、批末标记失败路径）。用户已于 2026-09-30 授权提出规格变更（原话「都允许」，回答的是「能否接受崩溃时多发若干条重复事件」）。
**本轮性质**：① 建在途三件套，用规格文字**显式、有界地**授权这四项语义变化；② 落地**默认关闭**的实现（新键 `verify.outbox.relay-batch-mark-enabled:false`），关闭时与起点逐字等价；③ 用纯 JUnit 单测锁定两条路径，用 test-only 真库 IT 量化新边界（重投上界、`sent_at` 形态、条件幂等）。**不做性能判别、不跑负载、不落地默认值、不声称任何收益。**

## 2. 依据（已入库，只可引用不可改写）
- TASK-164（未定支）首次拿到生产路径逐行归因：串行臂每行锁内墙钟 10.59~12.89 ms，其中 markSent 7.80~9.97 ms（72~77%）、syncSend 2.45~2.85 ms（23~26%）、select 0.07~0.10 ms（~1%）；并发臂每行锁内墙钟 3.64/6.15 ms（3.01×/1.72×）而排空斜率只 1.36×/1.22×，markSent 线程时间每行膨胀（C2 19.64 vs A2 7.80 ms）⇒ 收益被 MySQL 单行写持久化串行化吃掉。原始：`docs/perf/data/raw/task164-*-diaglines.txt`、`docs/perf/data/exp-outbox-relay-concurrency-pool-drain.json`。
- TASK-153 NO-GO 的四项语义变化与「消费端幂等不能自动视作授权」的三条理由，见 `docs/perf/判别-outbox-批末标记SENT-语义边界.md` §4（必须逐条抄进 delta 并给出新边界）。
- TASK-156：mapper 级并发标度 S(2)=1.8612 / S(4)=3.3066 / S(8)=5.7056，实例 `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` + `log_bin=ON` ⇒ 组提交可摊薄每行一次持久化往返（**仅必要条件**）。
- 现状 SQL（`VerifyEventOutboxMapper`，40 行，三个方法，一字不许改）：
  - `selectPendingBatch`：`SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < #{maxRetry} ORDER BY id LIMIT #{limit}`
  - `markSent`：`UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = #{id} AND status = 'PENDING'`
  - `incrRetry`：`UPDATE verify_event_outbox SET retry_count = retry_count + 1 WHERE id = #{id} AND status = 'PENDING'`

## 3. 起点与开工读数（指导侧 2026-09-30 23:38 亲跑值）
- `HEAD = a79729f8264c95487363a97e133ce7bfb4f5cea4`（PLAN L4 第五次外部门槛订正笔）；`origin/main = b85098ae0eaa71ec7740b70c19b9a74b2c759352`；`git rev-list --left-right --count origin/main...main` = **`0	1`**。CI run **`36736221648`** conclusion=success（web ✓20s / build ✓2m33s，build 档 11 步全 ✓，第 5 步 `--mode=online verify`、第 10 步词面门）。
- 工作树脏项**仅** `?? spec/changes/add-verify-degrade-status-index/`（零触碰）＋本任务目录。
- `bash scripts/verify/mvn-verify.sh --mode=offline` → rc=**0**，七模块 **36/41/33/103/123/59/10**，Skipped 全 0。
- `--mode=offline --static=verify-service` → rc=**1**，`You have 867 Checkstyle violations`（门槛：收口 ≤**867** 且新增文件零违规；spotbugs/pmd 被阻断＝未覆盖）。
- `verify-service/src/main/resources/application.yml`：**196** 行、CR=0、`^spring:` 根键 **1**、`^verify:` 根键 **1**、`maximum-pool-size` 命中 **0**、`relay-interval-ms: 500` 在 **L123**、`relay-send-concurrency` 裸词命中 **1**（L121 注释行，键形态 **0**）。**本轮此文件零改动**。
- `VerifyEventOutboxMapper.java` = **40** 行；`VerifyOutboxRelay.java` = **493** 行（锚点：L68 `batch-size:100`、L72 `max-retry:16`、L79 `relay-diagnostics-enabled:false`、L83-84 `relay-diagnostics-window-ms:10000`、L96 `relay-send-concurrency:1`、L126-127 `@Scheduled` 默认 5000/10000、L157 单次 `selectPendingBatch`（无排空循环）、L214 批次摘要日志格式、L257 起 `processRow`、L414 建池日志）。
- 保护件：`mq/VerifyOutboxRelayTest.java` = **484** 行 / **19** 个 `@Test`；`mq/VerifyOutboxRelayConcurrencyTest.java`（**10** 用例）；`mapper/VerifyEventOutboxMapperSqlContractTest.java` = **44** 行 / **1** 用例。**三者 numstat 必须为空且全绿。**
- 主规格 `spec/specs/sport-record-verify/spec.md` = **2806** 行 / **121** 个 `### Requirement:`（blob 存 LF、工作树检出 CRLF；报数写明测的是哪一面）。**本轮零改动、不合并任何 delta。**
- `work/mailbox/PLAN.md` = **1282** 行、CR=**0**（纯追加，既有行含 L4 零改动）。`spec/changes`：在途 **23** 目录 / archive **43**。
- 词面门：正则从 `.github/workflows/ci.yml` 现场提取，len=**26**、pipes=7（8 分支）；含 `--untracked` 的四形态（原样 / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）全 **ZERO_HIT rc=1**；正向对照 rc=**0**（探针必须植在非忽略路径，用完 `git rm --cached` + 删文件 + `git status --porcelain` 逐字还原）。**任何入库文件不得内嵌该正则字面量。**
- 契约门：本任务书落盘后**无参 rc=1 属预期**，`--open TASK-165 --baseline=a79729f8264c95487363a97e133ce7bfb4f5cea4` 应 rc=**0**；收口后**无参必须 rc=0**。
- 环境：磁盘 D 盘 Free = **196029489152** 字节（≈182.6 GiB，门槛 ≥100 GB）；sports 的 java 进程 **0**；Docker daemon **在跑**，`sport-verify-mysql`(3307)/`rocketmq-namesrv`/`rocketmq-broker`/`redis`/`nacos` 均 Up (healthy)，`task131-scratch-mysql` Exited(255)（**不许碰**）、`sport-verify-postgis` Exited（不起）。
- PLAN.md 受保护数字 token 基线计数（收口不得减少）：`13.4`=9、`18.0`=11、`73.93`=10、`68.8`=6、`6315`=7、`1.8612`=6、`3.3066`=6、`5.7056`=6、`9.408`=6、`36525962432`=7、`36586847965`=6、`36438897772`=6、`36399582548`=5、`36098038547`=5、`2806`=14、`598`=5、**`36736221648`=2**（新增受保护项）。
## 4. 交付物 A：在途三件套 `spec/changes/add-verify-outbox-relay-batch-mark/`
- `proposal.md`（Why / What Changes / Impact / 判定与停止条件）、`specs/sport-record-verify/spec-delta.md`、`tasks.json`（据实勾选，未做的步 `completed=false`、`passes=false`，照 TASK-164 诚实写法）。**纯新增目录，不合并进主规格、不动 archive。**
- delta 必须包含（缺一条即 G9 红）：
  1. **ADDED Requirement「relay 可选有界分块标记 SENT」**：WHEN `verify.outbox.relay-batch-mark-enabled=true`（默认 false）THEN 已成功发送行的 SENT 标记 MAY 按 chunk（`relay-batch-mark-chunk-size`，默认 25，钳位 `[1, batch-size]`）合并为**一条**条件 UPDATE，条件 MUST 保持 `status = 'PENDING'`（幂等），并 MUST 逐条登记被授权的四项语义变化及其上界：① 崩溃后重复投递窗口上界 = **chunk-size**（逐行路径为 1，TASK-153 候选为 batch-size=100）；② SENT 对独立连接的可见性延迟上界 = 一个 chunk 的发送时长；③ `sent_at` = 该 chunk 的标记时间、**chunk 内同值**（与 DDL 注释「投递成功时间」的偏差被显式授权，且要求 javadoc 与日志披露）；④ chunk 标记 SQL 真失败 ⇒ 该 chunk 内已投递行**全部留 PENDING**、下轮整块重投，且 MUST 对每个 id 各调用一次 `incrRetry` 以保持失败计数语义。
  2. **ADDED Requirement「默认关闭等价性」**：false（默认）时 MUST NOT 调用新 SQL、MUST NOT 创建新对象、调用序列与引入前**逐字等价**；`application.yml` MUST NOT 出现该键（默认值只存在于 `@Value`）。
  3. **MODIFIED（仅当确有必要）**：若主规格既有需求文字**逐字强制**「每行投递成功后立即标记 SENT」，则用 MODIFIED 精确改写该句并在 delta 里给出旧句/新句对照；若既有文字并未强制逐行标记，则**只用 ADDED**，并在 proposal 里写明「已逐字核对主规格相关需求，无需 MODIFIED」＋引用行号。**不得为凑格式硬改主规格文字。**
  4. **必须显式引用 TASK-153** 的 NO-GO 与其 §4 四条停止条件，逐条说明新规格如何授权与收窄；必须写明「消费端 `eventId` SETNX 与业务锚点幂等**不是**本授权的依据，本授权来自产品决策（用户 2026-09-30 原话『都允许』，所答问题为『能否接受崩溃时多发若干条重复事件』）」；并把 TASK-153 指出的**三条去重无法消除的影响**（重复网络投递与消费端 SETNX 命中日志、下轮重选已送达行的额外负载、崩溃恢复后库内不可区分已送达/未送达）**逐条登记为已接受的运维代价**。
  5. delta 与 proposal 内 **MUST NOT 出现任何性能收益数字**（本轮未测），也不得把 72~77% 写成可获得收益。

## 5. 交付物 B：生产代码（默认关闭，零行为变化）
- `VerifyEventOutboxMapper`：纯新增 `int markSentBatch(@Param("ids") List<Long> ids);`，用 `@Update` + `<script>` + `<foreach>` 实现 `UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE status = 'PENDING' AND id IN (...)`；**必须** `#{id}` 参数占位（禁字符串拼接）；空列表由调用方拦截（MUST NOT 发出 `IN ()`）。**既有三个方法与既有 SQL 一字不改。**
- `VerifyOutboxRelay`：新增 `@Value("${verify.outbox.relay-batch-mark-enabled:false}")` 与 `@Value("${verify.outbox.relay-batch-mark-chunk-size:25}")` 两个字段（含 javadoc：默认关闭、chunk 上界、`sent_at` 语义、与 `relay-send-concurrency` 正交、**未经判别不得开启**）；chunk-size 非法（<1 或 >batch-size）时钳位到 `[1,batch-size]` 并**只打一条 WARN**（照 `effectiveSendConcurrency()` 先例，MUST NOT 抛异常导致服务起不来）。
- **关闭路径**：`processRow` 与既有调用序列**逐字不变**（不得重构既有语句；如确需抽取，必须保证关闭时调用序列等价，并在 handoff 贴出 diff 逐行说明）。
- **开启路径**：成功发送行的 id 累积到**调用方/worker 局部**列表（禁共享可变状态、禁 `AtomicLong`/额外锁），每满 chunk 或批末 flush 一次 `markSentBatch`；返回 `affected < ids.size()` ⇒ 记 WARN（含差值与 id 数）**不抛、不 incrRetry**（行已投递，可能已被并发标记或已耗尽）；抛异常 ⇒ 对该 chunk **每个 id 各一次** `incrRetry`、记 ERROR、异常**不外逃**（与逐行 `markSent` 失败路径同语义）；`totals.success`/`failed` 仍**按行**计数，MUST NOT 改成按 chunk。
- 诊断：`RelayDiagnostics`/`BatchSummary` 可**纯追加** `markBatchCalls`、`markBatchRows` 两个有界低基数分量；诊断关闭时 MUST NOT 产生任何新日志；并发下按线程时间聚合的口径照既有 javadoc 说明。**不得改既有分量语义。**
- 与 `relay-send-concurrency>1` 正交：chunk 列表是 worker 局部、join 语义不变；**本轮不测组合、不开并发**。

## 6. 交付物 C：测试
- **保护件**：`mq/VerifyOutboxRelayTest.java`（484 行/19 用例）、`mq/VerifyOutboxRelayConcurrencyTest.java`（10 用例）、`mapper/VerifyEventOutboxMapperSqlContractTest.java`（44 行/1 用例）——三者 **numstat 必须为空**且全绿（G2）。
- 新增 `mq/VerifyOutboxRelayBatchMarkTest`（纯 JUnit 5 + Mockito，**≥10 用例**）：① 关闭 ⇒ `markSentBatch` 零调用且 `markSent` 次数=成功行数；② 开启 chunk=25、成功 100 行 ⇒ 恰 4 次、id 不重不漏且顺序与取批一致；③ 成功 107 行 ⇒ 5 次（尾块 7）；④ 部分行发送失败 ⇒ 失败行不进 chunk、仍走 `incrRetry`；⑤ 耗尽行（`retryCount>=maxRetry`）不进 chunk；⑥ `markSentBatch` 返回 < ids.size() ⇒ WARN、不抛、不 incrRetry；⑦ `markSentBatch` 抛异常 ⇒ chunk 内每 id 各一次 `incrRetry`、不外逃、后续 chunk 继续；⑧ chunk-size 非法（0/-1/>batch-size）⇒ 钳位且只 WARN 一次；⑨ 与 `sendConcurrency=4` 组合 ⇒ 四 worker 的 chunk 并集不重不漏；⑩ 诊断关闭 ⇒ 无新日志分量，开启 ⇒ `markBatchCalls/markBatchRows` 与实际一致。
- 新增 `config/VerifyOutboxRelayBatchMarkConfigTest`（纯 JUnit 5，照 `VerifyOutboxRelayIntervalDefaultTest` 模板）：① 反射断言两个 `@Value` 字面为 `${verify.outbox.relay-batch-mark-enabled:false}` 与 `${verify.outbox.relay-batch-mark-chunk-size:25}`；② `YamlPropertySourceLoader` 断言 classpath `application.yml` **不含**这两个键；③ 断言 `^verify:` 与 `^spring:` 根键各恰 1 个；④ 断言 `relay-interval-ms` 仍为 **500**（保护 TASK-163 落地件）。
- 新增 `mapper/VerifyEventOutboxBatchMarkMapperMysqlIT.java`（test-only，`*IT` 不被 Surefire 默认收集）：环境变量缺失 ⇒ `Assumptions` **skip 且不得记通过**；在**独立 scratch schema**（如 `task165_batch_mark_scratch`，用仓库真实 DDL 初始化）跑真实 SQL：条件幂等（第二次 affected=0）、部分命中（混入已 SENT 行与耗尽行）、`sent_at` chunk 内同值且非 NULL、与 `selectPendingBatch` 资格交互（耗尽行不被选中、已 SENT 行不被重选）、自动提交语义与逐行 `markSent` 一致；**不得触碰演示库 `verify_db`**、不得动 `task131-scratch-mysql`；Docker/MySQL 不可用 ⇒ 如实记「未覆盖」。
- **复裁 TASK-153 最强反例**（新规格草案下，test-only）：模拟「chunk 内前 k 行已发送、flush 前进程退出」⇒ 实测下轮 `selectPendingBatch` 重投行数上界 = chunk-size（登记实测值）并登记 `eventId` 逐字不变；**不翻案 TASK-153**，只登记「新规格授权下该反例被显式接受，且上界由 batch-size(100) 收窄到 chunk-size(默认 25)」。

## 7. 预登记反例 5 条（逐字抄进 proposal 与 handoff，并逐条说明触碰情况）
① 分块扩大崩溃后重复投递窗口（上界 chunk-size）；② `sent_at` chunk 内同值，偏离 DDL 注释「投递成功时间」；③ chunk UPDATE 真失败 ⇒ 整块已投递行留 PENDING、下轮整块重投（大于逐行）；④ 与 `relay-send-concurrency>1` 叠加时多 worker 并发 UPDATE 可能加剧 fsync 争用（TASK-164 已见 markSent 线程时间每行膨胀）⇒ 本轮不测、不开并发，**不得据此开启 concurrency>1**；⑤ MyBatis `<foreach>` 若用字符串拼接会引入 SQL 注入面 ⇒ 必须 `#{id}` 占位并在单测/IT 验证参数化。

## 8. 门槛 G0–G12（全部报实测退出码与关键读数原文）
G0 起点核对（§3 全 SHA 逐位）；G1 开工读数逐项一致（offline / 静态 / yml / Mapper 与 relay 行数与锚点 / 三个保护件 / 主规格 2806-121 / PLAN 1282-CR0 / delta 23-43 / 词面门 / 契约两形态 / 磁盘 / java / Docker）；G2 保护件 numstat 为空且全绿；G3 默认关闭等价性（单测 ＋ `git diff --exit-code <base>..HEAD -- verify-service/src/main/resources/application.yml` rc=**0**）；G4 offline 全量 rc=0、七模块 36/41/33/103/**123+n**/59/10、Skipped 0；G5 静态门 rc=1 且违规 **≤867**、新增文件零违规；G6 词面门四形态 ZERO_HIT rc=1 ＋ 正向对照 rc=0 ＋ 探针删除后 `git status --porcelain` 逐字还原；G7 `git diff --check` rc=0 且每笔 `git show --check` rc=0；G8 契约（在途 `--open TASK-165 --baseline=<SHA>` rc=0；**收口后无参 rc=0**）；G9 只改清单逐路径一致（§9）；G10 受保护数字 **17** 个 token base vs HEAD 不减；G11 IT 诚实性（skip ≠ pass，`Tests run: x, Skipped: y` 原文留档）；G12 外部门槛栏写「未达外部门槛（本次不 push，待下次授权由 CI 复验）」。

## 9. 只改清单（超出即红）
1 `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`（纯新增一个方法）；2 `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`（两键 ＋ 分块路径 ＋ 诊断纯追加分量）；3 新 `verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayBatchMarkTest.java`；4 新 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkConfigTest.java`；5 新 `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkMapperMysqlIT.java`；6 新 `spec/changes/add-verify-outbox-relay-batch-mark/{proposal.md, specs/sport-record-verify/spec-delta.md, tasks.json}`；7 `work/mailbox/PLAN.md`（纯追加）；8 新 `work/mailbox/tasks/TASK-165/handoff.md`（`spec.md` 已由指导侧落盘，**执行侧一字不改**）。原始证据写 ignored 的 `.trae/tmp/` 或 `docs/perf/data/raw/task165-*`。提交分批（业务代码 / 三件套 / 台账），逐路径 add，提交信息主题行＋空行＋正文要点。

## 10. 收尾硬条（缺一条即未收口）
本轮不起服务 ⇒ 无需 `stop-services`；但必须：确认 sports 的 java 进程=0 → 复跑 `--mode=offline`（rc=0）→ 复跑静态门（≤867）→ 词面门四形态＋对照 → **无参 `mailbox-contract.sh` rc=0** → `git diff --check` rc=0 ＋ 每笔 `git show --check` rc=0 → `docker ps -a` 留档（证明未动 `task131-scratch-mysql`）→ PLAN 纯追加验收记录（含 17 token base vs HEAD 计数、外部门槛栏、§7 反例触碰说明）。

## 11. 禁止事项（除 §0 外）
不合并任何 delta 进主规格、不改主规格一字；不新增除这两个键以外的任何配置；不写 `application.yml`；不跑 c100×2000 或任何负载、不起四服务；不改演示库数据、不清库；不动 `docs/perf/` 既有报告与 JSON；不新增依赖；不用 Mockito 预置数据库结果冒充真实 SQL（IT 必须真库，否则如实记未覆盖）。

## 12. 未覆盖与不得推出（照抄进 handoff）
spotbugs/pmd 未覆盖（被 checkstyle 阻断）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛；**零已测收益**（本轮不测性能，不得声称任何延迟/吞吐改善，不得把 markSent 占 72~77% 写成可获得收益）；未起四服务 ⇒ 无端到端证据；未测与 `relay-send-concurrency>1` 的组合；IT 若 skip ⇒ 真库语义未覆盖；默认关闭 ⇒ 零生产行为变化；**不得据此开启 `relay-batch-mark-enabled=true`**（须另立判别轮，且判别必须改用诊断口径的锁内吞吐，MUST NOT 再用 ~2s 排空采样器——其跨会话方差约 2×、每窗仅 6~12 样本）；不翻案 TASK-153/154，不改写 TASK-152/156/161/162/163/164 任何数字。

## 13. handoff.md 六项交回物
① 一句话结论（规格授权是否自洽、实现是否默认关闭等价、IT 是否真跑）；② 起点全 SHA、每笔提交 SHA 与逐路径清单、shortstat；③ 只改清单逐项对齐与零修改声明（含三个保护件 numstat 为空的原文）；④ G0–G12 逐门实测退出码与关键读数原文；⑤ §7 五条反例逐条触碰情况 ＋ 新规格授权的四项语义变化上界实测值；⑥ 未覆盖项与不得推出的结论。

## 14. 工具与陷阱
`git grep --untracked` 必须置于 pattern 之前且不搜 ignored 文件；从 mvn/Java 日志提数字先 `tr -d '\r'`（CRLF 让 grep 3.1 行尾锚点失配）；行数用 bash `wc -l`，**不要**用 PowerShell `Measure-Object -Line`（不数空行）；`git show <sha>:<path>` 取到 LF blob、工作树是 CRLF，报数写明测的是哪一面；`git ls-tree -d` 只列含 tracked 文件的目录（未跟踪的 `add-verify-degrade-status-index/` 不计入）；脚本写文件时 `String.replace` 的替换串里 `` $` `` 是反向引用 ⇒ 含 `$` 的内容用 `split().join()`；提交信息用主题行＋空行＋正文要点。