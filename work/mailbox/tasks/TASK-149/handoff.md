# TASK-149 回传：Spring 管理路径下 markSent 外层/内层逐次配对成本判别

## 结论

开工 HEAD `9fed299fd04b2218f7f30eaaca2a15b484b2597a`；业务提交 `158dcb31466c462b5813be4663d51768ffa741dd`。**裁决 GO（受限口径）= 在 TASK-148 已证实的受限 Spring Mapper 切片里，同线程、同一次 `markSent` 调用内的「外层墙钟」与「内层整个 `StatementHandler.update` 墙钟」可逐次配对、对同一成功调用求残余，且所测语义不变。** GO **不授权 SQL 批量化**、**不表示任何生产延迟/吞吐收益**、不定位生产瓶颈。全部数值/失败分类/环境差异见报告与 JSON。

## 验收

- **第一道门槛（干净检出复核，先于新测量）**：`git worktree --detach 9fed299`，确认未跟踪的 `VerifyEventOutboxMarkSentSpringProbeMysqlIT.java` 不在其中。offline verify-service 全量 **110/0/0/0、BUILD SUCCESS、rc=0**（`docs/perf/data/raw/task149-gate-clean-offline.log`）；TASK-148 条件式 scratch 真库 IT **5/0/0/0、BUILD SUCCESS、rc=0**（`task149-gate-clean-it.log`）。两条测试**不依赖**该未跟踪文件 → 门槛通过，授权新测量。真实类型：`HikariDataSource`/`MapperFactoryBean`/`SqlSessionTemplate`/`DefaultSqlSessionFactory`/`SpringManagedTransactionFactory`/`JdbcTransactionManager`，pluginCount 0↔1。
- **接线**：只沿用 TASK-147 **同一个** test-only、按 `MappedStatement.id`（`com.sportverify.verify.mapper.VerifyEventOutboxMapper.markSent`）精确限定的探针；Spring 注入真实 Mapper（`MapperFactoryBean` JDK 代理）、`SqlSessionTemplate`、`SpringManagedTransactionFactory`、Hikari；无 `@Transactional`。专用 scratch `task149_marksent_scratch` 由仓库真实 DDL `sql/03-verify-db.sql` 机械改名建成；**未碰演示 `verify_db` 或其他数据卷**。
- **逐次配对**：预热 30 + 3 轮×100；**300/300 全配对，缺配 0 / 多配 0 / 负残余 0**。残余只对成功配对的同一调用逐次计算。最终权威日志残余 P50/P95/P99（µs）：轮1 1018/1447/1716、轮2 810/1334/1536、轮3 701/1030/1247；内层 P50 ≈9.5–10.0ms。无插件基线外层 P50 9822µs、P95 13227µs。
- **轮间波动 / 扰动**：三次独立重跑残余 P50 分别落在 1038/910/749、934/859/516、1018/810/701 µs（区间 ~516–1038µs，均无缺配/多配）；无插件基线外层 P95 跨运行不稳定（一次 216763µs vs 12334/13227µs）→ **仅作原始观测，不用作减法对照**，不拿两组独立 P50 相减。残余是**混合量**（连接获取、prepare/绑定、MyBatis/Spring 调用、自动提交 commit/close 等），**不得命名拆项**，也不得称纯服务端 SQL / fsync / 池等待。探针只增加一次 update 计时插桩，未改变结果/语义 → 扰动可接受，归因继续。
- **语义不变**：目标调用 `rows=1` 且恰一条样本、内层落在外层窗口内；`status=SENT`、`sent_at` 置位且**独立连接立即可见**；真实 SQL 失败有无探针同异常类型（`org.springframework.jdbc.BadSqlGrammarException`），失败留下恰一条 `failed=true, rows=-1` 样本且不污染下一次；调用后 Hikari activeConnections 回 0。
- **门槛与退出码**：唯一入口 offline verify-service 全量 **110/0/0/0、BUILD SUCCESS、rc=0**（`docs/perf/data/raw/task149-offline.log`）；`MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentSpringPairedCostMysqlIT -Dsurefire.failIfNoSpecifiedTests=false"` 经同一入口（`--it` 硬编码 leaderboard-service）→ **4/0/0/0、BUILD SUCCESS、rc=0**（`docs/perf/data/raw/task149-it.log`）。`*IT` 不被默认 Surefire 收集，全量绿不冒充真库 IT 绿。
- **非行为红如实记录**：测试自身两处缺陷当场修正并重跑——SUMMARY 行把纳秒值标成 `_us`（结论与逐次原始行不受影响）、缺 `assertInstanceOf` 静态导入与 seed 计数偏差（首跑前自查修复）。均**不是**被测量语义的反例，未计行为红；原始日志 `task149-it-before-summary-unitfix.log`、`task149-it-rerun1-unitfix.log` 保留。

## 未覆盖

连接获取 / prepare/绑定 / 显式 commit 的逐次分账；服务端 SQL / 网络 / fsync 的分别占比；生产负载下的池等待、生产 P99 与吞吐收益；完整生产上下文（`VerifyApplication` 全 Bean、Nacos、MQ、Redis、Feign、调度、Web/Actuator、relay 外层锁/批次）；多实例锁竞争。未跑 c100×2000、未起常驻服务大负载；未改生产插件链 / Mapper SQL / 索引 / relay 默认值 / MQ / 连接池 / JVM；`--mode=online` / CI 未跑，**未达外部门槛**；未 push、未建 PR。

## 工作树如实说明（必须保留）

- 开工时那个**归属不明**的未跟踪文件 `VerifyEventOutboxMarkSentSpringProbeMysqlIT.java`：本任务**从未删除、修改或暂存**它，也未把它算作 TASK-148 已提交证据；作业期间它从工作树消失（`Get-ChildItem -Recurse -Filter *SpringProbe*` 全仓无结果），本任务的任何命令（git worktree / copy / docker / mvn clean / python runner）都不会删除该 `src` 文件，**判为并发外部动作**，非本任务所致。不得声称「已保留该未跟踪文件」。
- 工作树中另有**非本任务**的未跟踪目录 `spec/changes/measure-verify-outbox-mark-sent-server-event/`，同样非本任务产物、未暂存。
- 注意区分：被 TASK-148 提交并追踪的 `VerifyEventOutboxMarkSentProbeMysqlIT.java`（提供 `MarkSentProbe`）**仍在**且完好；消失的是名字多一个 `Spring` 的那个未跟踪文件。

## 实际改动清单（只改）

- **业务提交 `158dcb3`（6 文件）**：`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentSpringPairedCostMysqlIT.java`（新）、`docs/perf/验证-outbox-markSent-Spring外层内层逐次配对成本.md`（新）、`docs/perf/data/exp-outbox-relay-mark-sent-spring-paired-cost.json`（新）、`spec/changes/measure-verify-mark-sent-spring-paired-cost/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-149/spec.md`（新）、`work/mailbox/tasks/TASK-149/handoff.md`（新）、`work/mailbox/PLAN.md`（改）、`work/mailbox/后端优化机会总览-2026-09-26.md`（改）。
- 既有脏项（`spec/changes/adopt-native-mq-retry` 与 `wire-verify-outbox` 的归档移名、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）原样保留；未用 `git stash`、未 `git add -A`、未 push、未建 PR。