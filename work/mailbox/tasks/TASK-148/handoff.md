# TASK-148 回传：Spring 管理的 markSent 探针受限切片可配对

## 结论

开工 HEAD `d878476d51b120906c06720d59bb9c0404c47333`；业务提交 `711c0d1`。专用 scratch 真库与仓库 DDL 下，同一种 TASK-147 test-only、按目标 `MappedStatement.id` 限定的探针，经 Spring 注入真实 Mapper 可一调用一读数且不改变所测语义。**只覆盖受限 Spring 切片，不是完整生产上下文、不定位内部瓶颈、不构成吞吐收益。** 全部数值/失败分类/环境差异见报告和 JSON。

## 验收

- 接线实际类型：`MapperFactoryBean`、`SqlSessionTemplate`、`DefaultSqlSessionFactory`（由仓库 MyBatis-Plus starter 自动装配）、`SpringManagedTransactionFactory`、`JdbcTransactionManager`、`HikariDataSource`；无插件基线 0 插件、测试切片 1 个原探针；无显式业务事务。只连 `task148_marksent_scratch`，独立连接读到已提交状态。
- 真库 @`711c0d1`：`MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentSpringMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` → **5/0/0/0，BUILD SUCCESS，rc=0**（`docs/perf/data/raw/task148-it-711c0d1.log`）。无插件基线、开关/非目标、连续、真实 SQL 失败、连接归还均在场且全绿；失败类型 `BadSqlGrammarException` 有无探针一致。
- offline 全量 @`711c0d1`：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` → verify-service **110/0/0/0，BUILD SUCCESS，rc=0**（`docs/perf/data/raw/task148-offline-711c0d1.log`）。`*IT` 不进入默认 Surefire，全量绿不能冒充真库 IT 绿。
- 第一次环境红（application.yml 触发 Nacos 且默认数据源覆盖测试属性，5/1/3/0 rc=1）、第二次环境红（Nacos import 检查，1/0/1/0 rc=1）**不是行为红**；最终隔离 config-data 且断言实际 Hikari JDBC URL/catalog 才取真库有效结果。未做生产式完整上下文/online/CI，**未达外部门槛**。
- TASK-147 文案订正：样本整个 `StatementHandler.update` 调用墙钟，含 `KeyGenerator.processAfter()`；原始数据不动。无参数契约与 JSON/diff 校验结果以本轮最终回报为准。

## 未覆盖

生产服务组件扫描、Nacos、Redis、RocketMQ、Feign、调度、Web/Actuator、relay 锁/批次；连接获取、显式提交、服务端 SQL/网络/fsync 分账、生产负载占比、吞吐收益、多实例。未跑 c100×2000，未清演示库/数据卷，未改生产参数/Mapper/索引/MQ/池/JVM，未 push/PR。原有 archive 移名、.codex/.trae/add-verify-degrade-status-index 未碰；另一个运行中出现的未跟踪 SpringProbeMysqlIT.java 非本任务产物，未暂存。

## 实际改动清单（只改）

- verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentSpringMysqlIT.java
- verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentProbeMysqlIT.java
- docs/perf/验证-outbox-markSent-下层计时可配对.md
- docs/perf/data/exp-outbox-relay-mark-sent-pairability.json
- docs/perf/验证-outbox-markSent-Spring接线可配对.md
- docs/perf/data/exp-outbox-relay-mark-sent-spring-pairability.json
- spec/changes/prove-verify-outbox-mark-sent-spring-wiring/proposal.md
- spec/changes/prove-verify-outbox-mark-sent-spring-wiring/tasks.json
- spec/changes/prove-verify-outbox-mark-sent-spring-wiring/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-147/handoff.md
- work/mailbox/tasks/TASK-148/spec.md
- work/mailbox/tasks/TASK-148/handoff.md
- work/mailbox/PLAN.md
- work/mailbox/后端优化机会总览-2026-09-26.md
