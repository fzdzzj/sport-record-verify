# 提案：验证 outbox markSent 探针在 Spring 管理路径下可配对（TASK-148）

## Why

TASK-145/146 测得满批 `markSent` 的**外层混合墙钟**主导锁内处理段，内部连接获取、客户端 JDBC、提交及服务端执行仍未知。TASK-147 只在隔离真 MySQL 和手工构建的 MyBatis-Plus `SqlSessionFactory` 中证明测试探针可配对；它没有覆盖 verify-service 的 Spring 注入 Mapper、`SqlSessionTemplate`、事务和异常翻译路径。不能由此直接推断生产内部瓶颈或优化收益。

另一个口径需更正：本地 MyBatis 3.5.16 的 `PreparedStatementHandler.update` 除 `execute()` 和 `getUpdateCount()` 还调用 `KeyGenerator.processAfter()`；TASK-147 探针计的是整个 `StatementHandler.update` 调用，并非严格只有前两次 JDBC 调用。

## What Changes

1. 冻结基线和原有脏项，核对真实依赖、Mapper、数据源、事务路径，只订正 TASK-147 的计时边界措辞，原始数字不动。
2. 沿用 TASK-147 **唯一**按 `MappedStatement.id` 限定的 test-only 探针。在隔离 scratch MySQL 上用真实 DDL、Spring 注入的 Mapper、仓库的 MyBatis-Plus starter 自动装配、`SqlSessionTemplate` 和 Hikari 做小样本；只把 URL/凭证改指 scratch。排除 MQ、Redis、Nacos、调度和常驻 Web 服务，逐项披露与完整生产上下文的差异。不得手工 `new SqlSessionFactory` / `openSession` 冒充 Spring 路径。
3. 与**无插件基线**对照一调用一读数、开关关闭及非目标零样本、连续及失败后不串样本、更新行数、状态、异常类型、自动提交可见性和连接归还。关键接线或反例不成立即停止，不换第二方案。
4. 只报告该 Spring 测试切片的可行性，不跑 c100×2000 或常驻大负载、不改生产插件链/数据源、Mapper SQL、索引、默认值、MQ/池/JVM。完成离线测试、真库条件 IT、报告/摘要、台账、契约和必要的本地提交；不 push/PR。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的成本归因验证增量，先写入本变更的 `spec-delta.md`，不直接修改总稿。
- 可能修改：verify-service 测试源码；TASK-147 报告/机器摘要中错误的窄边界措辞；TASK-148 新报告/摘要、三件套、TASK-148 台账、PLAN/机会总览。原则上不改生产 Java/YAML/SQL。
- 无 API、数据库迁移或运行默认值变化；不得记录 eventId/payload/凭证/SQL 参数。
- scratch 库可重置；不得清演示 `verify_db` 或碰既有 archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`。

## 判定与停止条件

只有真 MySQL + Spring 注入真实 Mapper 的受限测试路径、无插件基线与反例均成立，才能写“Spring 管理的受限上下文中可配对”；**不等于完整生产路径已验证**，更不等于内部成本占比或吞吐收益。环境缺失、关键 Spring 接线无法证实、语义/隔离反例成立时记未覆盖/不可行，不换探针、不改生产配置。下一步是否开展负载归因由本次结论另行决策。
