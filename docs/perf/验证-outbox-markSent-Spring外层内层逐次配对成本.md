# TASK-149：Spring 管理路径下 markSent 外层调用与 StatementHandler.update 的逐次配对成本判别

## 裁决与边界

**GO（受限口径）：在 TASK-148 已证实的受限 Spring Mapper 切片里，同线程、同一次 `markSent` 调用内的「外层墙钟」与「内层墙钟」可逐次配对并对同一调用求残余，且所测语义不变。** 该 GO 仅表示「小样本同调用分账可重复且语义不变」，**不授权 SQL 批量化**，**不表示任何生产延迟/吞吐收益**，也不定位生产瓶颈。开工 HEAD 为 `9fed299fd04b2218f7f30eaaca2a15b484b2597a`。

三个受测层与它们的边界：

- **外层**：`VerifyEventOutboxMapper.markSent` 在调用线程上的整个墙钟。混合了 Spring/MyBatis 代理与会话调用、连接获取、参数绑定、JDBC 执行往返、以及自动提交相关的 commit/close 等。
- **内层**：整个 `StatementHandler.update` 调用的墙钟（含 `execute()`、`getUpdateCount()`、`KeyGenerator.processAfter()`）。可含 JDBC/网络/服务端 UPDATE/隐式提交等待；连接获取、prepare/绑定、显式 commit 不在这个窗口内。
- **残余 = 外层 − 内层**，且**只对成功配对的同一次调用**逐次计算。该残余是**混合量**：包含连接获取、prepare/绑定、MyBatis/Spring 调用开销、以及自动提交相关的 commit/close 等，**不得**命名为其中任何单项（连接获取 / prepare / 提交 / 纯服务端 SQL / fsync / 池等待），也**不得**用两组独立 P50 相减代替逐次配对。

未覆盖：完整生产上下文（Nacos / MQ / Redis / Feign / 调度 / Web）、生产负载下的池等待、服务端 SQL/网络/fsync 拆分、生产 P99 / 吞吐收益、多实例 relay 锁竞争。本次**未跑 c100×2000、未起常驻服务大负载**，**未改**生产插件链、Mapper SQL、索引、relay 默认值、MQ、连接池或 JVM。

## 第一道门槛：干净检出复核（先于新测量）

在隔离检出（`git worktree --detach` 到 `9fed299`，仅已提交源码；确认未跟踪的 `VerifyEventOutboxMarkSentSpringProbeMysqlIT.java` 不在其中）上重跑两条测试：

| 执行 | 当次结果（四计数 / Skipped / 退出码） | 原始日志（ignored） |
| --- | --- | --- |
| `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` | **110/0/0/0、BUILD SUCCESS、rc=0** | `docs/perf/data/raw/task149-gate-clean-offline.log` |
| `MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentSpringMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`（环境变量指向 scratch） | **5/0/0/0、BUILD SUCCESS、rc=0** | `docs/perf/data/raw/task149-gate-clean-it.log` |

干净检出即复现，且**不依赖**那个未跟踪的 Java 文件 → 判为门槛通过，授权开展新的成本测量。条件真库 IT 打印的真实装配类型：`com.zaxxer.hikari.HikariDataSource`、`org.mybatis.spring.mapper.MapperFactoryBean`、`org.mybatis.spring.SqlSessionTemplate`、`org.apache.ibatis.session.defaults.DefaultSqlSessionFactory`、`org.mybatis.spring.transaction.SpringManagedTransactionFactory`、`org.springframework.jdbc.support.JdbcTransactionManager`；无插件基线 pluginCount=0、探针切片=1；真实 SQL 失败译为 `org.springframework.jdbc.BadSqlGrammarException`。

## 接线与环境

`VerifyEventOutboxMarkSentSpringPairedCostMysqlIT` 使用 `AnnotationConfigApplicationContext`，只选择 Boot 的 `DataSourceAutoConfiguration`、`DataSourceTransactionManagerAutoConfiguration` 与仓库 MyBatis-Plus starter 的 `MybatisPlusAutoConfiguration`，并以 `@MapperScan` 注册**真实** Mapper；**不** `new SqlSessionFactory`、不 `openSession`。真库日志打印的实际类型：`HikariDataSource`、`SqlSessionTemplate`、`DefaultSqlSessionFactory`、`SpringManagedTransactionFactory`，Mapper Bean 为 Spring `MapperFactoryBean` 生成的 JDK 代理；无插件基线 pluginCount=0、探针切片=1。调用者无 `@Transactional`。

- 探针**只沿用 TASK-147 的同一个** `MarkSentProbe`（`@Intercepts(@Signature(type=StatementHandler.class, method="update", args={Statement.class}))`，按 `MappedStatement.id` 精确限定 `com.sportverify.verify.mapper.VerifyEventOutboxMapper.markSent`）；未新增第二种探针。与完整生产上下文的差异同 TASK-148（不扫描全业务 Bean、不加载 Nacos、不起 Redis/MQ/Feign/调度/Web/relay 外层）。
- 专用 scratch：库名 `task149_marksent_scratch`，由仓库真实 DDL `sql/03-verify-db.sql` 机械替换库名灌入 `task131-scratch-mysql`；测试先断言 JDBC URL 与 catalog 只指向该 scratch。**未触碰演示 `verify_db` 或其他数据卷**，数据只在 scratch 的 `verify_event_outbox` 内 `TRUNCATE` 重置。

## 同调用逐次配对测量

预热 30 次（读数丢弃），随后 3 轮受测，每轮 100 次目标调用；每轮结束另做一次**无插件基线**外层对照。测量内核：在一次 `mapper.markSent(id)` 的外层窗口内取 `System.nanoTime()` 差值，随后排空线程本地样本队列；**仅当** `rows==1 && samples.size()==1 && !failed` 时，才对该同一调用计算 `residual = outer − inner`。

| 轮次 | n | 缺配 | 多配 | 负残余 | 外层 P50/P95/P99 (µs) | 内层 P50/P95/P99 (µs) | **残余 P50/P95/P99 (µs)** | Hikari active 前→后 | GC 次数/时间增量 | CPU 增量 (ns) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 100 | 0 | 0 | 0 | 10520 / 13606 / 15144 | 9646 / 12936 / 13910 | **1018 / 1447 / 1716** | 0 → 0 | 0 / 0ms | 437,500,000 |
| 2 | 100 | 0 | 0 | 0 | 10365 / 13530 / 14755 | 9548 / 12658 / 13950 | **810 / 1334 / 1536** | 0 → 0 | 0 / 0ms | 375,000,000 |
| 3 | 100 | 0 | 0 | 0 | 10291 / 12673 / 13794 | 9557 / 11804 / 13246 | **701 / 1030 / 1247** | 0 → 0 | 0 / 0ms | 343,750,000 |
| 无插件基线（对照） | 100 | — | — | — | 9822 / 13227 / — | — | — | 0 → 0 | — | — |

- **配对完备**：三轮共 300 次目标调用，**300/300 全配对**，缺配 0、多配 0、负残余 0；300 条逐次原始行（`TASK149_RAW round=.. i=.. outer_us=.. inner_us=.. residual_us=.. rows=.. pairs=..`）全部落在 4 个同一 ID 调用点上，无一负残余。
- **可重复**：本次三卷残余 P50 为 1018 / 810 / 701 µs；加上两次独立重跑（各 3 轮 100 次）的残余 P50 分别为 1038/910/749 µs 与 934/859/516 µs，**三轮内与轮间均落在 ~516–1038 µs 区间**，无缺配、无多配。逐轮内层 P50 稳定在 ~9.5–10.0 ms。
- **探针对成本/结果的扰动**：无插件基线在本次与一次重跑中外层 P50 为 9822 / 9459 µs（与探针切片外层 P50 同量级），说明目标限定探针未把外层整体拉高到不可接受；但**另一次**基线外层 P95 出现 216763 µs 的尾部异常。基线尾部跨运行不稳定，因此**仅作原始观测记录**，**不**用它做减法对照，也**不**拿两组独立 P50 相减。判据：探针开关只增加一次 `StatementHandler.update` 的计时插桩，未改变目标调用结果或语义 → 判为**可接受的扰动**，归因继续。

## 语义不变（探针开启下）

`semanticsUnchanged_underPairedProbe` 与其余 3 个用例全绿，覆盖：

- 一次目标调用返回 `rows=1`、恰好产生一条样本，内层读数落在外层窗口内，残余非负；
- 最终 `status=SENT` 且 `sent_at` 置位，**独立 `DriverManager` 连接立即看见**已提交状态；
- scratch 表临时改名制造**真实 SQL 失败**：有无探针均译为同一异常类型（`BadSqlGrammarException`），有探针侧只留一条 `failed=true, rows=-1` 样本，复原表后下一次调用只留一条干净样本；
- 每组调用后两侧 Hikari activeConnections 均回到 0（**连接归还未变**）。

## 命令、退出码与原始输出

| 执行 | 当次结果 | 原始日志（ignored） |
| --- | --- | --- |
| 唯一入口 offline verify-service 全量 | **110/0/0/0、BUILD SUCCESS、rc=0**；`*IT` 未被默认 Surefire 收集，不把 0 次 IT 当真库通过 | `docs/perf/data/raw/task149-offline.log` |
| `MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentSpringPairedCostMysqlIT -Dsurefire.failIfNoSpecifiedTests=false"` 经唯一入口 | **4/0/0/0、BUILD SUCCESS、rc=0**（`--it` 硬编码 leaderboard-service，故真库 IT 走 `MAVEN_ARGS` 经同一入口） | `docs/perf/data/raw/task149-it.log` |

- 三次独立重跑的原始日志：`task149-it-before-summary-unitfix.log`（首次，其间发现 SUMMARY 行单位标签错误）、`task149-it-rerun1-unitfix.log`（修正单位标签后）、`task149-it.log`（当前权威日志，另加了真实装配类型打印）。两处**非行为红**的测试自身缺陷已当场修正并重跑：SUMMARY 行把纳秒值标成 `_us`（结论与逐次原始行不受影响）；缺 `assertInstanceOf` 静态导入 / seed 计数偏差（首跑前的自查修复）。这些都不是被测量语义的反例，均未计入行为红。
- 低基数 JSON：`docs/perf/data/exp-outbox-relay-mark-sent-spring-paired-cost.json`。

## 未覆盖 / 未知

连接获取、prepare/绑定、显式 commit 的逐次分账；服务端 SQL / 网络 / fsync 的分别占比；生产负载下的池等待；生产 P99 与吞吐收益；完整生产上下文；多实例 relay 锁竞争。`--mode=online` / CI 未跑，**未达外部门槛**。

## 停止与不越界

本次只做「同调用逐次配对是否可重复且语义不变」的判别。**GO 不授权 SQL 批量化、不改任何生产默认值**。若未来要做生产负载归因或 `markSent` 内部成本拆分，须另立单因素提案，配可安全配对的同负载验收与独立证据。未改生产插件链、Mapper SQL、索引、relay 默认值、MQ、连接池、JVM；未 push、未建 PR。