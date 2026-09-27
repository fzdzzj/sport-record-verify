# 验证 outbox markSent 下层计时的可配对测量（TASK-147：订正口径 + 隔离真库接线判别，不优化）

- 开工基线 HEAD：`767de7b04f90c562d775f515f382e40d016556ce`（与任务书一致）
- 结论：**只订正 TASK-146 证据口径 + 在隔离 scratch MySQL 上证明一条「测试专用、目标调用限定」的接线可配对；
  未接生产、未优化 `markSent`、未改任何默认值、未跑大负载**
- 机器摘要：`docs/perf/data/exp-outbox-relay-mark-sent-pairability.json`
- 原始证据（gitignored）：`docs/perf/data/raw/task147-offline.log`、`docs/perf/data/raw/task147-it.log`

## 1. TASK-146 证据口径订正（保留全部原始数字，只改错误位置）

| # | 原错误表述 | 订正后 | 涉及位置 |
| --- | --- | --- | --- |
| 1 | `lockHoldMs` 含摘要输出 / 「（摘要输出 + 解锁）」 | 终点在 `unlock()` **调用返回（或抛错被捕获）之后、摘要日志输出之前**取得：**含解锁调用、不含其后的摘要输出** | 报告 §1.2/§3.3、机器摘要 `lockSemantics`、TASK-146 handoff §修正口径/§锁口径分名、TASK-146 spec、PLAN、总览、`VerifyOutboxRelay`/`RelayDiagnostics`/`VerifyOutboxRelayTest` 注释与断言消息 |
| 2 | 无条件称 `lockHoldMs` 为「完整占锁」 | `unlock()` 抛错被捕获**不代表锁已确实释放**，故该经过时间**不得**无条件称作「完整占锁」或当作已释放的确证 | 同上（口径命名处统一改为「解锁后计时段」） |
| 3 | ——（复核为**已正确**，不改） | 1571 为**首次采样之后观测到的峰值** @`00:38:08.935`；首次采样晚于负载结束，`00:37:41`~`00:38:08` 真实峰值**未知（≥1571）** | 报告 §3.5、机器摘要 `backlog.realPeakNote`、PLAN、总览均已一致，**未改动** |

代码层真实边界（源码为准，非文案）：`holdStart`（解锁前取）→ 批次循环结束 `processingNanos`（finally 之前）
→ `finally { try { lock.unlock(); } catch { warn } if (completed && diagEnabled) { lockHoldNanos = nanoTime - holdStart; ... log.info(摘要) } }`。
即 `lockHoldMs` 终点在 `unlock()` 调用之后、摘要输出之前；`completed` 仅批次循环正常结束才为 true（处理段抛错时不输出 `lockHoldMs`）。

## 2. 装配审查（本仓实际版本与事务/自动提交路径）

- 版本：Java 21、MyBatis-Plus `3.5.7`（`pom.xml` 属性 `mybatis-plus.version`）、`mybatis-plus-spring-boot3-starter`、
  Spring Boot 3.2.4 系、HikariDataSource。
- verify-service **无**自定义 MyBatis 拦截器 / DataSource bean；无 `verify.outbox.*` 键（`application.yml`）。
- relay `relay()` **无 `@Transactional`**：逐行 `markSent`/`incrRetry` 是各自独立的**自动提交**调用。
- `markSent` = MyBatis-Plus `BaseMapper` 的 `@Update` 注解 SQL，目标 `MappedStatement.id` =
  `com.sportverify.verify.mapper.VerifyEventOutboxMapper.markSent`。
- 因此单次 `markSent` 外层墙钟 = 「连接获取 + 客户端 JDBC 准备/执行与网络往返 + 服务端 UPDATE + 隐式提交 + 映射」的**混合墙钟**。

## 3. 候选接线（唯一一条，测试专用）

**候选**：`@Intercepts(@Signature(type = StatementHandler.class, method = "update", args = {Statement.class}))`
的 MyBatis 插件，按 `MappedStatement.id` **限定到 `markSent`**，**只注册在 IT 自建的 `SqlSessionFactory`** 上
（`configuration.addInterceptor(new MarkSentProbe())`）——**不**写生产配置、**不**替换生产 DataSource、**不**动全局插件链。

| 面向 | 说明 |
| --- | --- |
| 同调用配对办法 | 目标 statement 每次 `update` 恰好入队**一条**样本；样本槽为**线程本地**（测试同线程调用），故一次 `markSent` ↔ 一条样本一一配对；`clear()` 后调用、`drain()` 校验条数 |
| 可测（客户端）边界 | 整个 `StatementHandler.update` 调用墙钟（包含 `execute()`、`getUpdateCount()` 及 MyBatis `KeyGenerator.processAfter()`；可含客户端 JDBC、网络往返、服务端 UPDATE 与 autocommit 隐式提交相关等待）；同时样本带 `rows`（更新行数）与 `failed` 标记 |
| 不能测（记未知） | 连接获取（在 `update` 之前完成）、参数绑定/准备（`prepare` 阶段）、显式 `commit`（`SqlSession.commit`）、以及服务端 SQL 与网络往返的**拆分** |
| 非目标隔离 | `id != TARGET_ID` 时直接 `proceed`、不入队；`selectPendingBatch`/`incrRetry` 不产生样本 |
| 开关 | 线程本地 `ENABLED`；关闭时零采样（连 `nanoTime` 都不取） |
| 异常清理 | `try/catch/finally`：失败也只在 `finally` 入队一条 `failed=true, rows=-1` 样本，异常原样抛出；作用域随线程本地槽清理，不污染下一次 |

**最强反例（逐条用真库测试判别）**：

1. 插件在 MyBatis-Plus/Spring 实际装配里**看不到**同一次 `markSent` 的子调用 → **未成立**（6 用例中目标调用均配到样本）。
2. 改变连接复用 / 事务 / auto-commit / 异常类型 / 更新行数 → **未成立**（下面 §4 逐项对照无探针基线一致）。
3. 关不掉或关闭仍产读数 → **未成立**（关闭态 0 样本）。
4. 失败后作用域污染后续样本 / 非目标串样本 → **未成立**（失败后下一次仅 1 条干净样本；夹入非目标调用后样本数不变）。

> 口径纪律：本层读数是**客户端 JDBC 调用墙钟**，**不得**称为「纯服务端 SQL」「纯 fsync」「纯池等待」或「提交耗时」。

## 4. 隔离 scratch MySQL 小样本判别（真实 DDL + 真实 Mapper 装配）

- 环境：Docker 容器 `task131-scratch-mysql`（MySQL 8.0.46，宿主 `13318`→3306，root/root）。
- schema：`task147_marksent_scratch`，由仓库 DDL **机械改名**灌入（验的就是提交里那份 DDL）：
  `sed 's/verify_db/task147_marksent_scratch/g' sql/03-verify-db.sql | docker exec -i task131-scratch-mysql mysql -uroot -proot`
- 装配：IT 自建 `HikariDataSource`（test-only，maxPool 4）+ `MybatisConfiguration` + `JdbcTransactionFactory`
  + `MybatisSqlSessionFactoryBuilder` + 真实 `VerifyEventOutboxMapper` + 测试专用探针；`openSession(true)` = auto-commit。
- 测试类：`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentProbeMysqlIT.java`（`*IT`，默认不收集）。

| 用例 | 判别点 | 结果 |
| --- | --- | --- |
| `targetCall_pairsExactlyOneLowerSample_andStateMatchesBaseline` | 一次目标调用 ↔ 一条样本；样本 `rows`==`markSent` 返回；PENDING→SENT、`sent_at` 置位；有/无探针更新行数与最终状态一致；关闭态 0 读数 | 通过 |
| `nonTargetCalls_recordNoSample` | `selectPendingBatch`/`incrRetry` 不产生样本 | 通过 |
| `consecutiveCalls_pairInOrder_andInterleavedNonTargetDoesNotInsertSample` | 连续 3 次目标调用配 3 条样本、夹非目标调用不增样本；三行均 SENT | 通过 |
| `autoCommitVisibility_isUnchangedByProbe` | 会话未 commit 时，另一连接即可见 SENT（auto-commit 语义）；有/无探针一致 | 通过 |
| `failedTargetCall_propagatesSameException_recordsOneSample_andCleansScope` | 真实 SQL 失败（表临时改名）异常**类型**有/无探针一致；失败留 1 条 `failed=true` 样本；恢复后下一次仅 1 条干净样本 | 通过 |
| `connectionRelease_matchesBaseline` | Hikari `activeConnections==0`、`totalConnections` 有/无探针一致 | 通过 |

**退出码**：offline verify-service test（唯一入口）`rc=0`（110/0/0/0）；真库 IT（唯一入口 + `MAVEN_ARGS` 注入类选择器）`rc=0`（6/0/0/0，BUILD SUCCESS）。
IT 走统一入口的方式（脚本 `--it` 分支硬编码 leaderboard-service，故用 Maven 3.9 的 `MAVEN_ARGS` 注入目标类选择器，仍不手敲 mvn）：
`MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentProbeMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`

## 5. 直接测得 / 未知

- **直接测得（真库）**：目标 `markSent` 一次调用与一条下层样本**可一一配对**；非目标不串样本；探针关闭零读数；
  连续调用按序配对；真实失败异常类型不变且作用域自清理；auto-commit 可见性、更新行数、最终状态、连接释放与无探针基线一致。
- **未知（本任务不外推）**：`markSent` 的**生产内部构成**（连接获取 / 提交 / 服务端 SQL / 网络往返拆分）；
  生产 Spring/Hikari 装配下的同一结论（本 IT 用 test-only Hikari + 独立 factory，**非**生产 Spring 装配）；
  生产容量与吞吐收益。

> **小样本证明「此层可配对测量」≠ 已定位生产瓶颈、≠ 证明吞吐收益、≠ 生产插桩已安全。**

## 6. 未覆盖 / 停止条件

- 未跑 c100×2000 或任何常驻服务大负载；未启动常驻服务；未改动 `markSent`、Mapper SQL、索引、事务、
  默认 5000ms、批次、重试、连接池、MQ 或 JVM 参数；未 push、未建 PR。
- 未覆盖：生产 Spring 装配下的同一配对证明（本仓无 verify-service 真库 IT 的生产装配先例，未引入 Spring 测试上下文）；
  连接获取/提交/服务端 SQL 拆分；多实例锁竞争；`--mode=online`/CI。
- 停止/下一步条件：本候选**未出现**语义或隔离反例，故**未**撤回；但**不**据此改生产配置。若要把该层接入生产诊断，
  需另立单因素提案，先解决「生产 Spring 装配下注册测试专用插件」与「低基数、默认关闭、有界」的接线问题，再谈负载验收。

## 7. 证据路径

- 真库 IT 原始日志：`docs/perf/data/raw/task147-it.log`（末行 `ENTRY_EXIT_CODE=0`）
- offline 入口原始日志：`docs/perf/data/raw/task147-offline.log`（末行 `ENTRY_EXIT_CODE=0`）
- 机器摘要：`docs/perf/data/exp-outbox-relay-mark-sent-pairability.json`
- 首次（错误）运行留痕：`docs/perf/data/raw/task147-it.log` 首段（`renameOutboxTo` 方向写反 → 表名未复原，3 例 `Table ... doesn't exist`，
  rc=1；**已定位并修复**为 `renameOutbox(from,to)` + teardown 兜底，复跑 6/0/0/0）