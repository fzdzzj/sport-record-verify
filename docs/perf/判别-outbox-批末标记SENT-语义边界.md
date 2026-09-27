# 判别 outbox「批末统一标记 SENT」的语义边界（TASK-153：只裁决可行性，不实施批量化）

- 开工基线 HEAD：`abfc477c1cad64c4885125e7cf10830fdc94e540`（与任务书一致；既有脏项原状保留，未 stash / 未 add -A）
- 结论：**NO-GO**——在现行规范（relay 唯一投递 · 判定事件经待发行表投递 · 投递失败保留行）下，隔离 scratch 真库对照
  出现**未经授权的重复投递窗口扩大（≤在飞 1 行 → ≤整批）**、**SENT 独立连接可见性推迟**、**`sent_at` 语义漂移/压平**与
  **聚合归因丢失**四类差异，任一即触发停止条件，故**停止生产批量化方向**
- 本任务**未**改生产 relay/Mapper、**未**加开关、**未**跑 c100×2000、**未**改任何默认值；**不**声称吞吐或 P99 改善；
  TASK-152 的 73.93%（markSent 同窗占比）**不得**解读为批量化可获得收益（见 §6 纪律）
- 机器摘要：`docs/perf/data/exp-outbox-batch-mark-safety.json`
- 原始证据（gitignored）：`docs/perf/data/raw/task153-01..09-*.log|txt`
- 判别 IT（test-only，默认不收集）：`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkSafetyMysqlIT.java`

## 1. 现行逐行语义基线（事实盘点：代码 / DDL / 消费者 / 规范）

### 1.1 现行逐行行为（源码为准）

| # | 事实 | 出处（源码） |
| --- | --- | --- |
| F1 | relay 按 `SELECT ... WHERE status='PENDING' AND retry_count < #{maxRetry} ORDER BY id LIMIT #{limit}` 取批（默认 100），随后循环内**逐行按 id 升序**处理 | `VerifyEventOutboxMapper.selectPendingBatch`；`VerifyOutboxRelay.java:117,121` |
| F2 | 每行 `syncSend` **成功后立即**执行条件 `markSent`（`SET status='SENT', sent_at=NOW() WHERE id=? AND status='PENDING'`），**该行标记返回后本行处理结束** | `VerifyOutboxRelay.java:134,156`；Mapper `markSent` |
| F3 | relay 方法**无 `@Transactional`**：逐行 `markSent` / `incrRetry` 各自为**独立自动提交**调用；第 k 行标记提交后，第 k+1 行才开始发送 | `VerifyOutboxRelay.java:84`（无注解）；§3 真库实测 `[[PENDING,PENDING,PENDING],[SENT,PENDING,PENDING],[SENT,SENT,PENDING]]` |
| F4 | 发送失败（`catch Exception`）→ 该行 `incrRetry`（`retry_count=retry_count+1 WHERE id=? AND status='PENDING'`）→ `continue`，**失败不中断后续行** | `VerifyOutboxRelay.java:135-148` |
| F5 | 标记失败同样 `incrRetry` 并计失败（成功消息下轮重投）；`markSent` 影响行数被**丢弃**，「0 行命中」仍计成功 | `VerifyOutboxRelay.java:156,160,163-176`；§3 实测 `success=1, failed=0` |
| F6 | `Error`（如进程级错误）不被 `catch (Exception)` 捕获 → 原样逃出，`finally` 解锁；崩溃时**至多当前在飞 1 行**「已发送未标记」 | `VerifyOutboxRelay.java:135,183-188`；§3 实测 `[SENT,SENT,PENDING]` |
| F7 | `eventId` 在 outbox 写入时生成并落 `payload`/行内；relay 重发**沿用行内** `eventId`（重发不换 id） | `VerifyOutboxService.persistResultAndEvent`；`VerifyEventProducer`/relay `:133-134` |
| F8 | 重试耗尽行（`retry_count >= maxRetry`，默认 16）：取批 SQL 即排除；循环内兜底再判一次，保留行、不投递、不计数 | Mapper；`VerifyOutboxRelay.java:124-129` |
| F9 | 防重锁 `verify:outbox:relay`，`tryLock(0, S)` 抢不到即整轮跳过（不查库不投递）；多实例并发下同一时刻至多一个 relay 在跑 | `VerifyOutboxRelay.java:87-103` |
| F10 | DDL：`status` 注释「PENDING 待投递，SENT 已投递」；`sent_at` 注释**「投递成功时间」**；`uk_event_id` 唯一键；`idx_status_id(status,id)` | `sql/03-verify-db.sql:39-56` |
| F11 | 消费端幂等 = `eventId` SETNX（24h TTL）**＋业务锚点**（`verification_result` 主键、`leaderboard_contribution.record_id` 主键状态机） | 消费端实现（record-service）；规范 delta「事件幂等消费」 |
| F12 | 全仓**只有 relay** 读 outbox 的 `status`；`sent_at` 生产链路只写不读（分析用途见 `docs/perf/归因-*.md`） | grep 全仓 `verify_event_outbox` 引用 |

### 1.2 三分类：哪些是硬约束 / 哪些只是既有观测 / 哪些仍需产品决策

| 分类 | 条目 | 说明 |
| --- | --- | --- |
| **规范硬约束**（当前 delta 明文，改动即需授权） | ①「判定事件经待发行表投递」：先 PENDING 行，再由 relay **投递并标 SENT**，消费端读到的 `eventId` 与行内**逐字一致**；②「投递失败保留行」：失败 `retry_count+1` 且**保持 PENDING** 留下轮重试，超阈值告警保留**不静默丢弃**；③「同一 `eventId` 重复投递 → SETNX 去重，业务仅执行一次」；④「判定/终判事件 SHALL NOT 在判定线程同步直发，SHALL 由 relay 唯一投递」 | `spec/changes/archive/wire-verify-outbox/.../spec-delta.md`；`spec/changes/fix-verify-outbox-poison-head-of-line/.../spec-delta.md`「正常投递语义保持：成功行仍按**原有条件**标为 SENT，失败行仍按原有条件增加 retry_count」 |
| **既有观测**（无规范文字承诺，但为既有代码/测试实测行为） | ① SENT 于**该行发送返回后立即**对独立连接可见（逐行自动提交）；② `sent_at ≈ 该行发送完成时刻`；③ 崩溃时「已发送未标记」最多 **1 行**（在飞行）；④ `markSent` 0 行返回被丢弃仍计成功 | 本报告 §1.1 F2/F3/F5/F6；TASK-147 IT 的 auto-commit 可见性用例 |
| **需产品决策**（本任务不得单方面假设） | ① 重复投递窗口由「≤1 行」放宽为「≤批大小」是否可接受（**消费端 SETNX/锚点幂等不能自动视作授权**）；② `sent_at` 由「逐行投递成功时刻」改为「批末时刻」是否可接受（影响 `docs/perf/归因-*.md` 的 lag 分析口径）；③ SENT 可见性推迟到批末、以及批末聚合更新**无法逐行归因**是否可接受（运维/人工处置视角） | 任务书裁决规则；本报告 §4 |

## 2. 候选与最强反例设计

### 2.1 候选协议（**仅存在于测试**，不落生产代码）

`sendPhaseBatchEnd`：与现行 relay **相同的取批 SQL、按 id 顺序、耗尽兜底、失败逐行 `incrRetry`**；
唯一差别 = **成功行不在发送后立即标记**，发送阶段结束后用一条**测试专用**批量条件 UPDATE
（`UPDATE ... SET status='SENT', sent_at=NOW() WHERE status='PENDING' AND id IN (...)`，PreparedStatement 直写，**未**加入生产 Mapper）
把「本批发送成功的 id 集合」统一标记。该批量 SQL 刻意保留 `status='PENDING'` 条件，以暴露条件语义差异。

### 2.2 反例清单（逐条设计判别）

| # | 反例 | 判别方式（真库） |
| --- | --- | --- |
| C1 | 发送第 k 行后、批末标记前进程退出 → 下轮重扫范围扩大 | `SimulatedProcessExit extends Error`（relay 只 catch `Exception`，模拟真实硬退出）在第 3 条发送返回后抛出；用**真实取批 SQL** 数下轮重投行 |
| C2 | 已投递未标记的行对**第二实例/第二连接**不可区分 → 重复投递 + 聚合标记丢归因 | 二分：候选发送阶段后另一会话取批 + 抢先标记第 1 行，再看批末聚合 UPDATE 行数 |
| C3 | 成功/失败/耗尽混合批：失败行误标 SENT 或中断后续 | 3 行中第 2 行模拟发送失败 + 1 行 `retry_count=16`；两路径逐行核对 status/retry_count/sent_at/下轮资格 |
| C4 | 批末单条 UPDATE 真 SQL 失败 → 整批已投递行仍 PENDING | 临时改名表制造**真实** SQL 失败（同 TASK-147 手法），核对异常传播与行状态 |
| C5 | 条件更新「0 行 / 部分命中」在批粒度无法归因 | 1 行已 SENT + 1 行 PENDING 同批 → 聚合返回 1；另验既不存在的行、全已 SENT 批 |
| C6 | `sent_at` 由该行发送时刻漂移为批末时刻（且整批被压平） | 每行发送模拟持有 3s；对比 `sent_at` 与「该行发送完成时刻」偏差及两行 `sent_at` 间隔 |
| C7 | 耗尽行被候选误取/误标 | 预置 `retry_count=16` 行，两路径均不得发送/标记/累加 |

> 发送用**可编程模拟**（mock `RocketMQTemplate.syncSend` 内按 eventId 注入失败/持有/读取 DB 墙钟/抛 `Error`）；
> **数据库一律真 MySQL + 本仓真实 Mapper SQL**，不用 Mockito 预制数据库结果。本 IT **不**是完整 MQ/Redis 端到端验证。

## 3. 隔离 scratch 真库对照结果（真实 DDL + 真实 Mapper）

- 环境：Docker `task131-scratch-mysql`（MySQL 8.0.46，宿主 `13318`→3306）；schema `task153_batch_scratch`
  由仓库 `sql/03-verify-db.sql` 机械改名灌入（`sed 's/verify_db/task153_batch_scratch/g'`）；IT 内硬校验
  `SELECT DATABASE() == task153_batch_scratch`。**未触碰**演示 `verify_db`；既有 scratch（task147=6 / task148=6 / task149=130 行）前后一致未动。
- 装配：test-only Hikari（maxPool 6）+ `MybatisConfiguration.setMapUnderscoreToCamelCase(true)` + `JdbcTransactionFactory`
  + 真实 `VerifyEventOutboxMapper` + **真实 `VerifyOutboxRelay`**（`ReflectionTestUtils` 注入 batchSize=100 / maxRetry=16；Redisson/RLock 为 mock，仅充当 `tryLock=true`）。

| 用例 | 判别点 | 基线（现行逐行） | 候选（批末标记） | 判定 |
| --- | --- | --- | --- | --- |
| `visibilityBetweenSends...` | 第 k 次发送时刻独立连接可见状态 | `[[PENDING×3],[SENT,PENDING,PENDING],[SENT,SENT,PENDING]]` | `[[PENDING×3],[PENDING×3],[PENDING×3]]` | **差异**：SENT 可见性整批推迟到批末 |
| `crashAfterLastSend...`（**最强反例**） | 3 行全部已送达后进程退出，下轮真实取批 | `[crash-e3]`（仅 1 行） | `[crash-e1, crash-e2, crash-e3]`（整批） | **差异**：重复投递窗口 1 → 3（= 批大小上限）；行内 `eventId` 逐字不变（复用原行，非新行） |
| `mixedBatch...` | 失败行/耗尽行/成功行两路径处置 | 失败行 PENDING + `retry_count 0→1` + `sent_at NULL`；成功行 SENT；耗尽行不动 | 同上（`candidateSent`=2，不含失败/耗尽行） | **无差异**（此维度候选可保持） |
| `conditionalUpdate...` | 条件更新 0 行 / 部分命中 / 真 SQL 失败 | 逐行返回 0/1，可归因；真失败抛 `PersistenceException`，行仍 PENDING | 批量仅返回聚合数（2 id 中 1 命中 → `1`，不知哪行未命中）；真失败抛 `SQLSyntaxErrorException`，同批已投递行仍 PENDING | **差异**：归因粒度丢失（真失败场景两路径行状态一致） |
| `sentAt...` | `sent_at` 与「该行发送完成时刻」偏差、批内间隔 | 偏差 `0s,0s`；批内间隔 `4s` | 偏差 `3s,3s`；批内间隔 `0s` | **差异**：偏离 DDL 注释「投递成功时间」，整批压平 |
| `secondInstance...` | 崩溃态下第二实例取批 + 先抢标记 + 批末聚合 | —— | 第二实例选中 `[multi-e1, multi-e2]`；批末聚合计 `1`（无法指出 e1 已被别处标记） | **差异**：跨实例重复投递窗口 + 聚合丢归因 |
| `exhaustedRow...` | 耗尽行两路径均不发送/标记 | 不动 | 不动；空成功集合批末 UPDATE 影响 0 行 | **无差异** |

**红/绿判别力（变异红）**：把候选协议的「发送阶段不标记」变异回「发送后逐行标记」后，
7 用例中**恰好 5 例红**（visibility / crash / mixedBatch / sentAt / secondInstance——即全部有判别力的用例），
2 例不区分的用例（conditionalUpdate 的真失败行态、exhaustedRow）保持绿；还原后复绿 7/0/0/0。

**保留的最小真库反例状态**（`task153-09` 日志）：`crash-e1/e2/e3` 三行 `PENDING, retry_count=0, sent_at=NULL`，
下轮真实取批 SQL 返回全部 3 行——即「3 条消息已到达 broker、数据库仍称 3 条都未投递」。

## 4. 裁决：NO-GO（逐条对照停止条件）

| 停止条件（任务书） | 是否出现 | 证据 |
| --- | --- | --- |
| 未经现有规范授权的**重复投递窗口** | **是** | 崩溃反例：下轮重投 1 行 → 3 行（上界=批大小 100）；第二实例取批同样整批可见 |
| **SENT 可见性**语义变化 | **是** | 独立连接快照：第 2/3 次发送时基线已逐行 SENT，候选整批保持 PENDING 至批末 |
| **失败重试**语义变化 | 否（该维度可保持：失败行不标 SENT、`retry_count+1`、耗尽保留），但**标记失败路径**在候选下语义改变（整批单条 UPDATE 真失败 → 整批已投递行留 PENDING，重投范围=整批；且异常类型/归因粒度与逐行不同） | §3 表 conditionalUpdate 行 |
| **时间语义**变化 | **是** | `sent_at` 偏差 0s→3s、批内间隔 4s→0s，与 DDL 注释「投递成功时间」不符 |

**为什么消费端 SETNX / 业务锚点幂等不能自动视作授权**：规范只授权「相同 `eventId` 重复投递时业务仅执行一次」，
**未**授权投递侧主动扩大重复窗口；且两个消费侧锚点的语义是「**已被正确投递出去** 的 `eventId` 二次到达时被去重」，
而不是「数据库可以对外声称一批消息尚未投递（PENDING）而消息实际已在途」。扩大窗口属**投递侧**行为变化，
消费端去重只消除*副作用重复*，不消除：① PENDING 行被第二实例取走引发的重复网络投递与消费端 SETNX 命中日志；
② 下轮取批把已送达行再次选中的额外负载；③ 崩溃恢复后「已送达 vs 未送达」在库内不可区分（运维无法据库判断真实进度）。
以上均超出既有规范文字，需产品决策，不能由本任务或消费端幂等代为实现授权。

**因此**：停止生产批量化方向。候选**不撤回**（作为已判别对象留证），但**不得**据本报告改生产 relay/Mapper/默认值。

## 5. 性能口径纪律（本任务不报告的结论）

- 候选的**静态事实**仅为：一条批量条件 UPDATE 覆盖至多 `batch-size` 个成功行，把「至多 N 次 `markSent`」合并为「1 次 UPDATE」
  （N≤100，SQL 往返次数上限减少）。本任务**不**报告该合并的延迟/吞吐收益，因为**语义上不可放行**。
- **不得**把 TASK-152 的「管理员窗口内 `markSent` 同窗/总墙钟 = 0.739312」当作批量化可获得收益上限或依据——
  该数是**成本占比**，不是可回收收益；本任务亦**未**复跑任何负载（无 c100×2000、无常驻服务）。
- 本报告无任何真实吞吐 / P99 数字。

## 6. 直接测得 / 未知 / 未覆盖

- **直接测得（真 MySQL 8.0.46 + 真实 DDL + 真实 Mapper SQL + 真实 relay 代码路径）**：§3 表中全部数字；
  崩溃反例的下轮重投集合（基线 1 行 vs 候选 3 行）与行内 `eventId` 逐字不变；`sent_at` 偏差/间隔；
  第二实例取批集合与聚合归因数；条件更新 0 行/部分命中/真 SQL 失败行为；耗尽行不动。
- **未知（不外推）**：生产 Spring/Hikari 装配与真实网络下同批的精确窗口时长；真实 broker 重发对消费端 SETNX
  命中率的量化影响；多实例真实竞争下的窗口叠加；`sent_at` 变化对 `归因-*.md` lag 分析的量化影响。
- **未覆盖**：真实 RocketMQ/Redis 端到端（发送为模拟；本 IT 不是 E2E）；`--mode=online`/CI 未跑；
  未做负载/容量对照；未评「批末标记 + 其他补偿机制（如先写 SENDING 中间态）」的组合方案（超出本任务边界，且任何替代方案需另立提案与规格授权）。

## 7. 验证门槛与退出码（统一入口 `scripts/verify/mvn-verify.sh`）

| # | 运行 | 四计数（run/fail/err/skip） | 退出码 | 日志 |
| --- | --- | --- | --- | --- |
| V1 | 真库 IT（有 scratch 三变量） | 7 / 0 / 0 / 0（BUILD SUCCESS） | 0 | `task153-03-it-run.log` |
| V2 | 变异红（判别力） | 7 / **5** / 0 / 0（红名单=5 个有判别力用例） | **1**（日志内 `[verify-entry] Maven 以退出码 1 结束`） | `task153-04-it-mutation-red.log` |
| V3 | 还原复绿 | 7 / 0 / 0 / 0 | 0 | `task153-05-it-regreen.log` |
| V4 | 真库 IT（**缺变量**） | 7 / 0 / 0 / **7 skipped**（BUILD SUCCESS，**不记为通过**） | 0 | `task153-06-it-noenv-skip.log` |
| V5 | offline 常规套件（`--pl verify-service test`） | **110** / 0 / 0 / 0（与既有基线同数；`*IT` 默认不收集） | 0 | `task153-07-offline-regular.log` |
| V6 | 只跑最强反例（保状态） | 1 / 0 / 0 / 0 | 0 | `task153-08-counterexample-run.log` |

- 真库 IT 走**同一入口**（`--it` 分支硬编码 leaderboard-service，故用 Maven 3.9 `MAVEN_ARGS` 注入类选择器，仍不手敲 mvn）：
  `TASK153_IT_URL=http://… MAVEN_ARGS="-Dtest=VerifyEventOutboxBatchMarkSafetyMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`
- V4 的 skip 与 0-run 一律**不记通过**；V1 的证据只覆盖「隔离 scratch 真库 + 真实 Mapper/relay 路径」，**不得**升级称为完整 MQ/Redis E2E。

## 8. 证据路径

- IT 源码：`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkSafetyMysqlIT.java`
- 原始运行日志（gitignored）：`docs/perf/data/raw/task153-01-scratch-prestate.log`（既有 scratch 未被触碰的核对）、
  `task153-02-ddl-actual.txt`（实际 DDL `SHOW CREATE TABLE`）、`task153-03..08-*.log`（上表 V1–V6）、
  `task153-09-counterexample-state.log`（保留反例状态 + 既有 scratch 行数复核）
- 机器摘要：`docs/perf/data/exp-outbox-batch-mark-safety.json`
- 规范依据：`spec/changes/prove-verify-outbox-batch-mark-safety/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`；
  `spec/changes/archive/wire-verify-outbox/.../spec-delta.md`；`spec/changes/fix-verify-outbox-poison-head-of-line/.../spec-delta.md`

## 9. 停止条件与后续

- **停止**：生产批量化方向（含「批末统一 markSent」及其默认值/开关形态）**不实施**；本任务不产生任何生产代码改动。
- 若未来仍要批量化：先解决 §4 四类差异的规格授权（重复窗口/可见性/`sent_at`/归因），且**必须**另立单因素提案，
  先跑语义判别（本报告口径）再谈负载验收；消费端幂等现状**不构成**授权。
- 本次**未** push、**未**建 PR；本地提交只含本任务文件（业务证据与台账分两笔）。
