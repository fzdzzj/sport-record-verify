# TASK-166 handoff：补跑真库 IT 把 TASK-165 的授权上界抬到实测 ＋ 三项订正（F1/F2/F3）

## 1. 一句话结论

**IT 真库实跑全绿**：`VerifyEventOutboxBatchMarkMapperMysqlIT` 6 个用例在真实 MySQL（容器 sport-verify-mysql、宿主端口 3307、独立 scratch 库 task165_batch_mark_scratch、`sql/03-verify-db.sql` 原文 DDL 建表）上实跑，日志原文 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`、退出码 0，Skipped=0 非 skip；**§1-C 四项上界全部从设计意图抬到实测原文**（① 崩溃重投=已发未标数、eventId 逐字稳定、3 ≤ chunk-size 25 < batch-size 100；② 同 chunk `sent_at` 同值 `2026-10-01 09:49:21`；③ 同批 id 二次标记 `affected=0`；④ 已 SENT 行与耗尽行不被重选）；**三项订正 F1/F2/F3 全部落地**（F2+F3 提交 `8286289`、F1 提交 `c839c7f`、sendRow 统一欠账登记 PLAN）；**零生产行为变化**（`application.yml` 零写入、两新键默认值不动、批次标记保持默认关闭）。

## 2. 起点全 SHA 与分批提交（每笔路径与 shortstat）

起点（与任务书 §2 逐位一致）：
- `HEAD = 30c5ab5b93bdf0ce7e7c652b56cfd6bea83a20c3`
- `origin/main = b85098ae0eaa71ec7740b70c19b9a74b2c759352`
- `git rev-list --left-right --count origin/main...main` = `0	4`（本地领先 4 笔、未推送）
- CI run `36736221648`：`{"conclusion":"success","headSha":"b85098ae0eaa71ec7740b70c19b9a74b2c759352","status":"completed"}`（gh 实测原文）

分批提交（逐路径 add，无 `git add -A`/`add .`）：

| 批次 | SHA（全） | 主题 | 逐路径清单 | shortstat |
| --- | --- | --- | --- | --- |
| C1 订正 | `828628909d099b7b88b787ddc297716dc5c5e15a` | fix(verify): 订正分块标记日志字段名与 processRow javadoc 如实化（TASK-166 F2/F3） | `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` | 1 file changed, 4 insertions(+), 2 deletions(-) |
| C2 三件套 | `c839c7f22c3147c9e8a1d78d07ec9af121f63fec` | spec(verify): 批量标记三件套补录第 5 项被授权变化与真库 IT 实跑证据（TASK-166 F1） | `spec/changes/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md`、`spec/changes/add-verify-outbox-relay-batch-mark/proposal.md`、`spec/changes/add-verify-outbox-relay-batch-mark/tasks.json` | 3 files changed, 6 insertions(+) |
| C3 台账 | 本笔（终版哈希以 `git log --oneline -1` 为准） | docs(mailbox): 登记 TASK-166 验收记录与任务两件套 | `work/mailbox/PLAN.md`、`work/mailbox/tasks/TASK-166/handoff.md`、`work/mailbox/tasks/TASK-166/spec.md` | 3 files changed, 202 insertions(+)（PLAN 30 ＋ handoff 109 ＋ 任务书 63 行——任务书末行无换行符，`wc -l`=62 而 numstat=63） |

总 shortstat（base `30c5ab5` → C3 终态）：见文末「总 shortstat 回填」行（提交前按工作树终态实测）。

## 3. 只改清单

- spec/changes/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md
- spec/changes/add-verify-outbox-relay-batch-mark/proposal.md
- spec/changes/add-verify-outbox-relay-batch-mark/tasks.json
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-166/handoff.md
- work/mailbox/tasks/TASK-166/spec.md

## 4. 只改清单逐项对齐与零修改声明

- 与任务书 §6 只改清单 7 项逐项对齐，无超出行（`git diff --name-only 30c5ab5..HEAD` ＋ 新增两件套 = 恰上述 7 路径；既有脏项 `spec/changes/add-verify-degrade-status-index/` 全程零触碰）。
- 三个保护件 numstat 为空原文（`git diff --numstat 30c5ab5..HEAD -- <三保护件>` 空输出）：`VerifyOutboxRelayTest`、`VerifyOutboxRelayConcurrencyTest`、`VerifyEventOutboxMapperSqlContractTest` 均零改动且全绿（19/10/1）。
- `application.yml` numstat 为空原文（`git diff --numstat 30c5ab5..HEAD -- verify-service/src/main/resources/application.yml` 空输出）：零改动；`git diff --exit-code 30c5ab5..HEAD -- verify-service/src/main/resources/application.yml` rc=0。
- 未触碰：`VerifyEventOutboxMapper.java`、任何测试文件（含 IT）、主规格、pom、scripts、`docs/perf/` 既有报告与 JSON、容器 `task131-scratch-mysql`、演示库 `verify_db`。

## 5. G0–G10 逐门实测退出码与关键读数原文

- **G0 起点核对**：§2 全 SHA 逐位一致（见 §2 起点行）；rc 语义：`git rev-parse` 两 SHA 与任务书逐字同。
- **G1 开工读数**：逐项一致，停手条款未触发。关键实测：offline 全量 rc=0、七模块 `36/41/33/103/137/59/10`、`Skipped: 0`、`BUILD SUCCESS`；`VerifyOutboxRelayBatchMarkTest` 10 绿、`VerifyOutboxRelayBatchMarkConfigTest` 4 绿、三保护件 19/10/1 绿（原文 `[INFO] Tests run: 19, ... -- in com.sportverify.verify.mq.VerifyOutboxRelayTest` 等）；静态门 rc=1、`You have 867 Checkstyle violations`；行数锚点 783/61/391/115/257 全对、L315/L563 原文与任务书引文逐字同；application.yml 196 行 / `^verify:`=1 / `^spring:`=1 / `relay-batch-mark` 0 命中 rc=1 / L123 `relay-interval-ms: 500`；主规格 121 Requirement、PLAN 1309 行 CR=0、在途 24 / archive 43；DDL 67 行、L39 `CREATE TABLE IF NOT EXISTS \`verify_event_outbox\``；词面门正则现场提取 RE_CHARS=26、RE_PIPES=7、8 分支，四形态（CI 原样 / `C` / `zh_CN.UTF-8` / `C.UTF-8`）全 `ZERO_HIT rc=1`、正向对照 rc=0 命中 9/9、探针删后 `git status --porcelain` 逐字还原；契约无参 rc=1（TASK-166 仅 spec 未声明，预期）＋ `--open TASK-166 --baseline=30c5ab5b93bdf0ce7e7c652b56cfd6bea83a20c3` rc=0；磁盘 Free 194619600896 字节（≥100 GB）；sports 的 java 进程 0/0；Docker daemon 开工为关（`failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`，与任务书原文逐字同）；17 受保护 token 开工校准与任务书逐位一致（scope=PLAN.md 行命中法）。
- **G2 保护件**：numstat 为空（§4）＋ 19/10/1 全绿（G1 offline 原文）。
- **G3 默认关闭等价**：`git diff --exit-code 30c5ab5..HEAD -- verify-service/src/main/resources/application.yml` rc=0；`grep -c relay-batch-mark` = 0（rc=1）。
- **G4 IT 实跑（本轮最强判据）**：按 §3 规程逐步执行——Docker Desktop 启动 5 s 内 `docker info` rc=0；`docker start sport-verify-mysql` healthy、`docker port` 原文 `3306/tcp -> 0.0.0.0:3307`；scratch 库按原文 DDL 建表（SHOW CREATE TABLE 留档）；`.mvn/maven.config` 跑前快照原文 `?? .mvn/`、内容 `-Dtest=VerifyEventOutboxBatchMarkMapperMysqlIT` ＋ `-Dsurefire.failIfNoSpecifiedTests=false`（第 2 行偏差见 §7）；环境变量 `TASK165_IT_URL=jdbc:mysql://127.0.0.1:3307/task165_batch_mark_scratch?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`、USER=root、PASSWORD=root；IT 日志原文两行：`[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.491 s -- in com.sportverify.verify.mapper.VerifyEventOutboxBatchMarkMapperMysqlIT` 与 `[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，`IT_RUN_RC=0`、`[INFO] BUILD SUCCESS`；surefire XML 6 用例名：test1_conditionalIdempotency_secondUpdateAffectedZero、test2_partialHit_mixedSentAndPending、test3_sentAt_sameWithinChunkAndNonNull、test4_qualificationInteraction_selectPendingBatchFiltersCorrectly、test5_autoCommitSemanticsMatchesSingleRow、test6_counterexampleReArbitration_upperBoundIsChunkSize；Hikari `task165-batch-mark-it` 6 次起停对应 6 用例；防误指断言（SELECT DATABASE()=scratch、URL 含 schema）通过。通道删除后快照原文为空；`.mvn/maven.config` 在任何 git add/commit 之前删除。
- **G5 全量 offline**：两轮（开工、收口提交态）均 rc=0、`BUILD SUCCESS`、`36/41/33/103/137/59/10`、Skipped 0；§3.7 删通道后复跑同数（通道未污染基线证明）。
- **G6 静态门**：两轮 rc=1（预期形态）且 `You have 867 Checkstyle violations` 恒 867 ≤ 867；改动文件 `VerifyOutboxRelay.java` 违规 27→27（逐行对比仅行号 +2 位移：327→329 等，零新违规）。
- **G7 词面门**：开工轮与收口轮四形态全 `ZERO_HIT rc=1`（三态判定、`--untracked` 置于 pattern 之前）；正向对照 rc=0 命中 9/9；探针已删、status 逐字还原。
- **G8 空白检查**：`git diff --check` rc=0；`git show --check` C1/C2 实测 rc=0（C3 提交后复验同 rc=0，见回传）。
- **G9 契约**：在途 `--open TASK-166 --baseline=30c5ab5b…` rc=0（G1 时取证）；收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=0（C3 提交后实测，TASK-166 足迹已入库「视为已收口」）。
- **G10 只改清单与受保护数字**：只改清单恰 7 项逐路径一致（§3/§4）；17 个受保护 token base→终态行命中计数（scope=work/mailbox/PLAN.md，方法与开工校准一致）无一减少，实测终值：13.4=12、18.0=14、73.93=13、68.8=9、6315=10、1.8612=9、3.3066=9、5.7056=9、9.408=9、36525962432=9、36586847965=8、36438897772=9、36399582548=8、36098038547=8、2806=14、598=8、36736221648=7（基线 30c5ab5 态为 11/13/12/8/9/8/8/8/8/8/7/8/7/7/13/7/5）。

**§7 收尾硬条实测**：`.mvn` 删净（porcelain 空）→ sports java 进程=0 → 收口 offline rc=0（137）→ 收口静态门 867 → 词面门四形态＋对照 → 无参契约 rc=0 → `git diff --check` rc=0 ＋ 三笔 `git show --check` rc=0 → `docker ps -a` 收口存证（仅 sport-verify-mysql Up healthy；task131-scratch-mysql Exited(255) 零触碰；postgis 未启动）→ `verify_db` 跑前跑后快照一致（SHOW DATABASES 同清单、84100 行 / max_id 84100）→ scratch 保留（1 表 11 行 = IT 用例遗留 3 行 ＋ 探针 8 行，不清库、不删表）→ PLAN 纯追加验收记录（含四项实测原文、F1/F2/F3 订正说明、17 token 计数、外部门槛栏「未达外部门槛（本次不 push，待下次授权由 CI 复验）」）。

## 6. §1-C 四项实测证据逐条原文 ＋ F1/F2/F3 订正前后对照

**① 崩溃/未标记场景下轮 `selectPendingBatch` 重投行数实测上界**
- IT 第一手证据：test6 通过，断言原文「未标记行全部被下轮重选重投，上界实测恰为已发未标数 3（<= chunk-size 25）」——3 行 `crash-evt-1/2/3` 在 `selectPendingBatch(100, 16)` 下全部重选，且断言「重发沿用行内 eventId，消费端幂等键逐字稳定」通过。
- 探针补强（IT 后同一 scratch 库、Mapper 原文 SQL，`--table` 原文）：植入 3 行 crash 模拟行后 `SELECT ... WHERE status = 'PENDING' AND retry_count < 16 ORDER BY id LIMIT 100` 返回 5 行（`qual-3`、`probe-pending`、`probe-crash-1`、`probe-crash-2`、`probe-crash-3`），其中 3 行 crash 模拟行全部在列、eventId 逐字稳定；`re_picked_rows=5`。
- **实测上界结论**：重投行数＝已发未标行数（IT 实测 3；探针 3/3 全重选），受取批 `LIMIT 100` 与重试资格过滤约束；对照 chunk-size=25（3 ≤ 25）与 batch-size=100（上界即 SQL LIMIT）。

**② 同 chunk 内 `sent_at` 同值（实测时间戳原文）**
- IT 第一手证据：test3 通过（`assertEquals(t1, t2, "同 chunk 内的一条 UPDATE 保证 sent_at 完全同值")`，且非 NULL 断言通过）。
- 探针原文（Mapper 原文 markSentBatch SQL 对 2 行执行后回读）：
  `| 7 | probe-time-a | SENT | 2026-10-01 09:49:21 | 2026-10-01 09:49:21.000000 |`
  `| 8 | probe-time-b | SENT | 2026-10-01 09:49:21 | 2026-10-01 09:49:21.000000 |`
  两行 `sent_at` 逐字同值（秒级与微秒位均同）。

**③ 同一批 id 二次调用 `markSentBatch` 的 affected 实测=0（条件幂等）**
- IT 第一手证据：test1 通过（首次 1 行、二次 `assertEquals(0, second, "重复标记受 status='PENDING' 保护，影响 0 行")`）。
- 探针原文（同一 UPDATE 两次执行，mysql -vv）：首次 `Query OK, 2 rows affected / Rows matched: 2 Changed: 2`；二次 `Query OK, 0 rows affected / Rows matched: 0 Changed: 0`。

**④ 已 SENT 行与耗尽行不被重选（实测）**
- IT 第一手证据：test4 通过（`markSentBatch([id1])` 后 `selectPendingBatch(10, 16)` 仅返回 `qual-3`，断言「仅 id3 有资格入批」；`qual-1` 已 SENT、`qual-2` 耗尽 16 均不在列）。
- 探针原文：植入 `probe-exhausted`(PENDING,16)、`probe-sent`(SENT,0)、`probe-pending`(PENDING,0) 后同一 selectPendingBatch SQL 返回仅 `qual-3` 与 `probe-pending` 两行；`qual-1`/`probe-sent`（SENT）与 `qual-2`/`probe-exhausted`（耗尽）全部不入批。

**F2 订正前后对照（L563，单行 diff）**
- 前：`log.info("outbox 事件分块批量标记成功：chunkSize={}, affected={}",`（实参 `count, affected`）
- 后：`log.info("outbox 事件分块批量标记成功：rows={}, affected={}",`（实参不变＝该次 flush 的实际行数 `idsToMark.size()` 与受影响行数）
- 不动项：日志级别 INFO、调用次数、`affected={}` 字段、异常路径日志。

**F3 订正前后对照（L315 javadoc）**
- 前：`* 单行处理体：串行与并发共用的唯一实现（语义只有一份，不可能漂移）.`
- 后：`* 单行处理体：仅关闭态（relay-batch-mark-enabled=false，默认）的串行与` ＋ `* 并发路径共用；开启分块标记态改走 {@link #sendAndCollect}，两者的` ＋ `* 发送/耗尽/incrRetry 语义必须同步维护，存在漂移风险.`
- 欠账登记（PLAN 追加节）：**后续把两份单行语义统一为一个 `sendRow`**；本轮不做统一重构（超范围）。

**F1 补录内容（纯追加，0 删行）**
- delta 第 5 项：开启态下成功行不再有逐行 INFO 日志（原 `outbox 事件投递成功：id=…, eventId=…, tag=…` 由每 chunk 一条汇总日志取代；失败行的逐行 WARN 日志保持不变）；两点影响：① 运维无法再按 `eventId` 从日志定位单条投递时刻；② TASK-163/164 的 M1 空档机制门依赖逐行日志时间戳，在开启态不可用，后续任何判别必须改用诊断口径的锁内吞吐。
- proposal Impact 节追加同一条（「日志观测面（TASK-166 F1 补录的被授权变化）」bullet）。
- numstat：spec-delta.md +1/-0、proposal.md +1/-0（0 删行=纯追加）；tasks.json +4/-0（据实追加真库实跑证据行）。

## 7. 通道偏差登记（待指导侧裁定）

任务书 §3.4 规定临时 `.mvn/maven.config` 两行中的第 2 行为 `-DfailIfNoSpecifiedTests=false`。按字面执行首轮通道跑失败（rc=1，在 common 模块即中断，IT 未得执行），报错原文：

`[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test (default-test) on project sport-verify-common: No tests matching pattern "VerifyEventOutboxBatchMarkMapperMysqlIT" were executed! (Set -Dsurefire.failIfNoSpecifiedTests=false to ignore this error.) -> [Help 1]`

原因：surefire 3.1.2 已移除短属性名 `failIfNoSpecifiedTests` 的识别，须用带 `surefire.` 前缀形式。仓内三处权威材料均为前缀形式：TASK-156 spec L65（本任务书引用的通道先例，并注明「后者必需：`-am` 会带上 common/api，那里没有该类，缺它则报 No tests matching pattern」）、`scripts/verify/mvn-verify.sh` L190（`--it` 分支）、`scripts/verify/README.md` L59。执行侧裁定：该偏差可由仓内权威材料消解、不触碰任何红线（仅临时通道文件内容、任何 add/commit 前已删除、不改断言/测试/生产代码/任务书一字），故按 TASK-156 先例改用 `-Dsurefire.failIfNoSpecifiedTests=false` 重跑，IT 随即真跑全绿（G4）。本登记不订正任务书，留指导侧裁定；首轮失败的报错原文即上引文字，第二轮通道运行日志留档 `.trae/tmp/task166-logs/it-run.log`（其开头 DEVITATION-NOTE 段记录了本偏差与改用前缀形式的依据）。

## 8. 未覆盖项与不得推出的结论

照任务书 §8：spotbugs/pmd 未覆盖（被 checkstyle 阻断）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛；IT 只在**单实例、无并发消费者、无真实 broker**的 scratch 库上验证 Mapper 语义 ⇒ 不含多实例竞争、不含 RocketMQ 重投、不含端到端；**不得**据 IT 通过就开启 `relay-batch-mark-enabled=true`（须另立判别轮，且必须用诊断口径的锁内吞吐，禁用 ~2s 排空采样器——其跨会话方差约 2×）；**不得**把 IT 结果或 markSent 占 72~77% 写成吞吐/延迟收益；**不得**与 `relay-send-concurrency>1` 组合开启；不翻案 TASK-153/154，不改写 TASK-152/156/161/162/163/164/165 任何数字。

补充：§1-C 四项的探针为 IT 之后的补充测量（同一 scratch 库、Mapper 原文 SQL、mysql 客户端执行），用于取得 IT 未打印的字面值（时间戳原文、affected 行数原文），IT 六用例本身为第一手证据；IT 用例执行顺序为 JUnit 决定（非源码序），故 IT 结束后 scratch 遗留行为 test4 的 3 行。

**总 shortstat（base `30c5ab5` → C3 终态，`git diff --shortstat 30c5ab5..HEAD` 实测）**：7 files changed, 212 insertions(+), 2 deletions(-)（C1 4+2 / C2 6 / C3 202；任务书末行无换行符使 numstat 行数=63、`wc -l`=62）
