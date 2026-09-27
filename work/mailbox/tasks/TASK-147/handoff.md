# TASK-147 回传：订正 relay 证据口径并在隔离环境验证 markSent 可配对测量（不优化、不跑负载、不改默认值）

## 回传概要

- **开工 HEAD**：`767de7b04f90c562d775f515f382e40d016556ce`（与任务书一致，核对后开工）。既有脏项
  archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 未触碰；未 stash、未 `git add -A`、未清库。
- **提交**：两笔本地提交——口径订正 3 主/测 + 新 IT 探针 + 报告 + 机器摘要 + TASK-146 报告/JSON 订正
  + 本变更三件套为**业务提交 `3f52cdc9b6e73b4c4dde8cc182778e5acd96cf27`**（11 文件）；
  TASK-147 两件套 + TASK-146 两件套 + PLAN + 总览为**台账提交**（哈希由任务回传承载）。未 push、未建 PR。
- **结论**：① 精确订正 TASK-146 三处证据口径（保留全部原始数字）；② 在隔离 scratch MySQL 上用
  **真实 DDL + 真实 Mapper 装配 + 少量测试行**证明**唯一一条** test-only、按 `MappedStatement.id`
  限定的 `markSent` 下层计时接线**可一对一配对**（6 用例全绿）。**未注册生产插件、未替换生产 DataSource、
  未改任何默认值、未优化 markSent、未跑 c100×2000 或常驻大负载**。小样本证明「该层可测」**不等于**
  已定位生产瓶颈或证明吞吐收益。
- **下一步唯一因素（仍未测）**：`markSent` **生产**内部构成（连接获取 / 客户端 JDBC 执行 / 提交 /
  服务端 SQL / 残差）与同一配对在**生产 Spring/Hikari 装配**下的表现——本任务只证明「层可配对」。

## TASK-146 证据口径订正（保留全部原始数字）

1. **`lockHoldMs` 终点口径**：原报告/摘要/注释有「含摘要输出」表述，**错误**。代码真值：
   `holdStart → processingNanos（批次循环结束、进入 finally 之前）→ finally { unlock(); if (completed && diagEnabled)
   lockHoldNanos = nanoTime - holdStart; log.info(摘要) }`——终点在 **`unlock()` 调用返回（或抛错被捕获）之后、
   摘要日志之前**取得：**含解锁调用、不含其后的摘要输出**。
2. **`unlock()` 抛错**：抛错被捕获时**不能**据此宣称锁已确实释放，故不得无条件称「完整占锁」，
   改称「解锁后计时段」。
3. **1571**：为首次采样（`2026-09-27 00:38:08.935`）以后「**观测到的峰值**」；首次采样晚于负载结束，
   介于 `00:37:41`~`00:38:08` 之间的**真实峰值未知（≥1571）**。原值 1571 保留，各处表述经复核**本已正确**、未改。

订正落点（**仅错误位置**，不扩范围）：`docs/perf/拆解-outbox-relay-markSent-调用成本.md`、
`docs/perf/data/exp-outbox-relay-mark-sent-cost.json`、`work/mailbox/tasks/TASK-146/{spec.md,handoff.md}`、
`work/mailbox/PLAN.md`、`work/mailbox/后端优化机会总览-2026-09-26.md`、
`VerifyOutboxRelay.java` / `RelayDiagnostics.java` / `VerifyOutboxRelayTest.java` 的 javadoc/注释与断言消息
（方法名 `relay_lockHold_reportsCompleteHoldIncludingUnlock` 保留）。

## 装配审查（本仓实际 MyBatis-Plus/Spring/Hikari 与事务边界）

- Java 21；MyBatis-Plus `3.5.7`（`mybatis-plus-spring-boot3-starter`）；Spring Boot `3.2.4` 系。
- 生产连接池 `HikariDataSource`；verify-service **无**自定义 MyBatis 插件 Bean、**无**自定义 DataSource Bean。
- `VerifyOutboxRelay.relay()` **无 `@Transactional`**：逐行 `markSent`/`incrRetry` 为**各自独立自动提交调用**。
- `markSent` 是 MyBatis-Plus `BaseMapper` 的 `@Update` 注解 SQL
  （`UPDATE verify_event_outbox SET status='SENT', sent_at=NOW() WHERE id=#{id} AND status='PENDING'`），
  目标 statement id = `com.sportverify.verify.mapper.VerifyEventOutboxMapper.markSent`。

## 唯一候选接线（至多一种，测试专用、目标调用限定）

- **种类**：test-only MyBatis 插件
  `@Intercepts(@Signature(type = StatementHandler.class, method = "update", args = {Statement.class}))`，
  `intercept` 内取 `delegate.mappedStatement.id` 与 `…VerifyEventOutboxMapper.markSent` **精确比对**。
- **注册范围**：**仅注册在 IT 自建 `MybatisConfiguration`**（`configuration.addInterceptor(new MarkSentProbe())`），
  **未注册生产配置、未替换生产 DataSource、未改全局插件链、未在生产启任何诊断开关**。
- **同调用配对办法**：一次目标 statement 执行向 **THREAD-LOCAL 队列**入列**恰好一条**样本；IT
  `reset()`（清空）→ 调用 → `drain()`（排空）；单线程串行（一次 `markSent` ↔ 一条样本）。
- **开关**：thread-local `ENABLED`；关闭态**零 `nanoTime` 采样**、不产样本。

## 可测边界 / 不能测段

- **可测=客户端层**：`PreparedStatement.execute()` + `getUpdateCount()` 的**墙钟**；样本另带 `rows`
  （更新行数）与 `failed` 标记。
- **不能测**：连接获取（在 `update` 之前完成）、参数绑定 / statement prepare、显式 commit
  （`SqlSession.commit`）、**服务端 SQL 与网络往返的拆分**。

## 最强反例（逐条，均未成立）

| # | 反例 | 判别结果 |
| --- | --- | --- |
| ① | 插件在 MyBatis-Plus/Spring 装配下看不到同一次 `markSent` 子调用 | **未观测**：6 用例中目标调用全部配对（1:1） |
| ② | 改变连接复用 / 事务 / auto-commit / 异常类型 / 更新行数 | **未观测**：逐项与无探针基线比对一致 |
| ③ | 无法关闭，或关闭态仍有读数 | **未观测**：关闭态产样本数 = 0 |
| ④ | 失败调用污染后续样本，或非目标调用串样本 | **未观测**：失败后恰一条干净样本；交错非目标不新增样本 |

## 隔离 scratch 真库判别（真实 DDL + 真实 Mapper + 少量测试行）

- 容器 `task131-scratch-mysql`（`mysql:8.0.46`，宿主 `13318→3306`）；schema `task147_marksent_scratch`
  由 `sql/03-verify-db.sql` 机械改名（`sed 's/verify_db/task147_marksent_scratch/g'`）生成——**真实 DDL**；
  **未触碰演示库 / verify_db**。
- 测试装配：test-only `HikariDataSource`(maxPool 4) + `MybatisConfiguration` +
  `Environment("scratch", JdbcTransactionFactory, dataSource)` + `MybatisSqlSessionFactoryBuilder` +
  **真实 `VerifyEventOutboxMapper`** + test-only 探针；`openSession(true)` = auto-commit。
  **未用 Mockito 预制读数冒充真库接线**。
- IT 类：`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentProbeMysqlIT.java`
  （`*IT`，surefire 默认不收集）。

| 用例 | 结果 | 判别要点 |
| --- | --- | --- |
| `targetCall_pairsExactlyOneLowerSample_andStateMatchesBaseline` | pass | 一次目标调用 ↔ 一条样本；`sample.rows == markSent` 返回值；PENDING→SENT 且 `sent_at` 置位；有无探针的更新行数与最终状态一致；**关闭态 0 读数** |
| `nonTargetCalls_recordNoSample` | pass | `selectPendingBatch`/`incrRetry` 不产样本 |
| `consecutiveCalls_pairInOrder_andInterleavedNonTargetDoesNotInsertSample` | pass | 3 次目标调用 → 3 条样本、按序；交错非目标不新增样本；3 行均 SENT |
| `autoCommitVisibility_isUnchangedByProbe` | pass | 会话提交前即可由**另一连接**看见 SENT；有无探针一致 |
| `failedTargetCall_propagatesSameException_recordsOneSample_andCleansScope` | pass | 真实 SQL 失败（临时改名表）；异常类型有无探针一致；恰一条 `failed=true` 样本；下一次调用恰一条干净样本 |
| `connectionRelease_matchesBaseline` | pass | Hikari `activeConnections==0`、`totalConnections` 有无探针一致 |

- **首跑红与修复（如实记录）**：首跑恢复表名的 SQL 把源表名写错（`RENAME verify_event_outbox TO verify_event_outbox`），
  scratch 表停在临时名 → 3 例报 `Table … verify_event_outbox doesn't exist`、**rc=1**；改为
  `renameOutbox(from,to)` 并在 teardown 加兜底恢复，手工带 schema 前缀复原 scratch 表，**复跑 6/0/0/0、rc=0**。
- scratch 最终态：`task147_marksent_scratch` 四表齐全（`appeal`、`rule_version`、`verification_result`、
  `verify_event_outbox`），`verify_event_outbox` **已复原**。

## 退出码与门槛

- **offline（唯一入口）** `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`
  → **rc=0**：`Tests run: 110, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
  （`*IT` 不被 test 阶段收集 → CI 基线仍 **110**，只增不减）。日志末行 `ENTRY_EXIT_CODE=0`
  （`docs/perf/data/raw/task147-offline.log`，该路径被 `.gitignore` 忽略）。
- **条件式真库 IT（仍走唯一入口）**：因 `mvn-verify.sh --it` 硬编码 `leaderboard-service`，改用
  Maven 3.9 `MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentProbeMysqlIT -Dsurefire.failIfNoSpecifiedTests=false"`
  经同一入口 → **rc=0**：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`；环境变量
  `TASK147_IT_URL/USER/PASSWORD`；日志末行 `ENTRY_EXIT_CODE=0`（`docs/perf/data/raw/task147-it.log`，已忽略）。
- 机器摘要 JSON（`ConvertFrom-Json`）、`git diff --check`、无参数 `bash scripts/verify/mailbox-contract.sh`
  的退出码见「收口核对」项（收口后集中回传）。

## 未覆盖 / 未知（不标为通过）

- `markSent` **生产**内部构成（连接获取 / 提交 / 服务端 SQL / 网络拆分）= **未知**。
- 同一配对在**生产 Spring/Hikari 装配**下的表现 = **未知**（本 IT 用 test-only Hikari + 独立 factory，
  未引入 verify-service Spring-context 真库 IT 先例）。
- 生产容量与吞吐收益 = **未知**；`--mode=online` / CI **未跑**；多实例锁竞争 **未覆盖**。
- 本任务下层读数是**客户端 JDBC 调用墙钟**，**不得**称纯服务端 SQL / 纯 fsync / 纯池等待 / commit 时间。

## 实际改动清单（只改）

业务提交 `3f52cdc9b6e73b4c4dde8cc182778e5acd96cf27`（11 文件）：

- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java（改）
- verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java（改）
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java（改）
- verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentProbeMysqlIT.java（新）
- docs/perf/验证-outbox-markSent-下层计时可配对.md（新）
- docs/perf/data/exp-outbox-relay-mark-sent-pairability.json（新）
- docs/perf/拆解-outbox-relay-markSent-调用成本.md（改）
- docs/perf/data/exp-outbox-relay-mark-sent-cost.json（改）
- spec/changes/prove-verify-outbox-mark-sent-attribution/proposal.md（新）
- spec/changes/prove-verify-outbox-mark-sent-attribution/tasks.json（新）
- spec/changes/prove-verify-outbox-mark-sent-attribution/specs/sport-record-verify/spec-delta.md（新）

台账提交（6 文件）：

- work/mailbox/tasks/TASK-147/spec.md（新）
- work/mailbox/tasks/TASK-147/handoff.md（新，本文件）
- work/mailbox/tasks/TASK-146/spec.md（改）
- work/mailbox/tasks/TASK-146/handoff.md（改）
- work/mailbox/PLAN.md（改）
- work/mailbox/后端优化机会总览-2026-09-26.md（改）

## 未触碰

- 未注册生产 MyBatis 插件、未替换生产 DataSource、未改全局插件链；未改 `markSent`、Mapper SQL、索引、
  事务、默认 5000ms、批次、重试、连接池、MQ 或 JVM 参数；未运行 c100×2000、未起常驻大负载。
- archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 原样保留；未用 `git stash`、未 `git add -A`、未清库；未 push、未建 PR。