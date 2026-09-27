# TASK-148：markSent 探针在 Spring 管理 Mapper 切片中的配对判别

## 裁决与边界

**受限 Spring 切片可配对；未验证完整生产上下文，也未定位生产瓶颈。** 本次只复用 TASK-147 的测试专用 `MarkSentProbe`（精确过滤 `VerifyEventOutboxMapper.markSent` 的 `MappedStatement.id`），没有第二种探针、生产插件、Mapper SQL 或运行参数改动。开工 HEAD 为 `d878476d51b120906c06720d59bb9c0404c47333`。

TASK-147 原报告把样本写成严格 `PreparedStatement.execute()+getUpdateCount()`，现按本地 MyBatis 3.5.16 `PreparedStatementHandler.update` 字节码订正为**整个 `StatementHandler.update` 调用墙钟**：其中有 `execute()`、`getUpdateCount()`、`KeyGenerator.processAfter()`。可含 JDBC/网络/服务端 UPDATE/隐式提交相关等待；连接获取、prepare/绑定、显式 commit 不在这个窗口；服务端 SQL、网络、fsync、池等待的分别占比未知。TASK-147 原始数字未改。

## 接线与环境

使用仓库 `sql/03-verify-db.sql` 将 schema 名机械替换为专用 `task148_marksent_scratch`，灌入已有 `task131-scratch-mysql` 的真 MySQL。测试先断言 JDBC URL 与 catalog 只指向该 scratch；测试数据仅在该 schema 的 `verify_event_outbox` 中重置，未清演示 `verify_db` 或数据卷。

`VerifyEventOutboxMarkSentSpringMysqlIT` 使用 `AnnotationConfigApplicationContext`，只选择 Boot 的 `DataSourceAutoConfiguration`、`DataSourceTransactionManagerAutoConfiguration` 与仓库 `mybatis-plus-spring-boot3-starter` 的 `MybatisPlusAutoConfiguration`，并以 `@MapperScan` 注册真实 Mapper；**不** `new SqlSessionFactory`、不 `openSession`。真库日志断言/打印的实际类型：`HikariDataSource`、`MapperFactoryBean`、`SqlSessionTemplate`、`DefaultSqlSessionFactory`、`SpringManagedTransactionFactory`、`JdbcTransactionManager`。无插件基线 pluginCount=0；有探针切片=1，Bean 仅在测试配置出现。调用者无 `@Transactional`，调用前无活动业务事务。

与完整生产上下文差异：不扫描 `VerifyApplication` 的全业务 Bean，不加载 `application.yml`/Nacos 配置导入，不启动 Nacos 服务发现、Redis/Redisson、RocketMQ、Feign、定时调度、Web/Actuator、relay 外层锁/批次；仅验证 Mapper 的受限 Spring 接线，不声称生产启动路径或负载行为。Nacos/Redis/MQ 没有被用作此结论的替身。

## 真库反例及结果

- 一次 `markSent` 返回 1，PENDING→SENT 且 `sent_at` 置位，独立 `DriverManager` 连接立即看见；无插件基线与有探针结果一致。有探针恰一条 `TARGET_ID`、`rows=1`、`failed=false` 的样本，计时非负。
- 关闭探针、无插件基线和非目标 `selectPendingBatch`/`incrRetry` 均零样本；连续三次目标调用只生成三条按序样本，交错非目标不插入。
- scratch 表临时改名造成**真实 SQL 失败**；无插件与有插件均译为 `org.springframework.jdbc.BadSqlGrammarException`；后者仅留一条 `failed=true, rows=-1`，复原表后下一次成功调用只留一条干净样本。两侧 Hikari activeConnections 均回到 0。
- 这些是相同测试切片内的语义对照，不是连接等待、SQL/fsync 或吞吐的测量。没有 c100×2000、没有常驻服务压测，也不从独立 P50 相减。

## 命令、失败分类与原始输出

| 执行 | 当次结果 | 原始日志（ignored） |
| --- | --- | --- |
| `MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentSpringMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`（设置 TASK148_IT_URL/USER/PASSWORD 到 scratch） | **5/0/0/0、BUILD SUCCESS、rc=0**；缺环境变量的运行不计真库通过 | `docs/perf/data/raw/task148-it.log` |
| `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` | verify-service **110/0/0/0、BUILD SUCCESS、rc=0**；`*IT` 未由默认 Surefire 收集，不把 0 次 IT 当真库通过 | `docs/perf/data/raw/task148-offline.log` |

环境红留痕：首次切片尝试使用 `SpringApplicationBuilder`，`application.yml` 的 Nacos config import 启动外部重试，默认数据源覆盖低优先级属性，Mapper 连接遭拒（5 run/1 failure/3 errors、rc=1）；它不是探针语义反例，且未得到演示库成功写入。第二次尝试屏蔽配置加载后被 Nacos 的“缺 spring.config.import”检查拒绝（1 error、rc=1），亦是**环境/切片构造红**。最终只用选定 auto-config 类的 `AnnotationConfigApplicationContext`，以最高优先级 scratch 属性源并在 Mapper 调用前核查 Hikari JDBC URL/catalog，消除了这两个来源；第三次及最终重跑均为真库 5/0/0/0。原始失败日志依次为 `task148-it-first.log`、`task148-it-second.log`，有效真库日志为 `task148-it-third.log`、`task148-it.log`。未把编译红或 skip 写成行为红。

未跑 online/CI，未达外部门槛。后续若要归因生产负载，须另有真实生产式上下文及同负载分账；本次结论仅是安全配对的受限接线证明。
