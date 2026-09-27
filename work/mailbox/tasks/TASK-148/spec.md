# TASK-148：验证 markSent 探针的受限 Spring 接线（不优化）

## 目标与基线

开工 HEAD `d878476d51b120906c06720d59bb9c0404c47333`。把 TASK-147 `StatementHandler.update` 目标限定探针放进 Spring 管理的真实 Mapper 路径，对照无插件上下文在专用 scratch 真 MySQL 上判别配对、异常与连接语义。TASK-147 的 `execute()+getUpdateCount()` 窄口径订正为整个 update 调用（含 `KeyGenerator.processAfter()`），原始数字不动。

## 边界与判据

- 仅 test-only、一种原探针；MyBatis-Plus starter 自动装配 `SqlSessionFactory`/`SqlSessionTemplate`，Spring 管理 Mapper、事务工厂及 Hikari，非手工 `openSession`。测试只允许 `task148_marksent_scratch`，用 `sql/03-verify-db.sql` 机械改名 DDL；不碰演示 verify_db。
- 有/无插件基线对照目标一次一条、开关关闭/非目标零、连续不串、真实失败同异常类型、返回值/状态/独立连接可见性与连接归还一致。任何反例/环境缺失/Skipped 均不得称真库通过；不试第二方案。
- 只得出受限 Spring 切片的接线可行性；完整生产上下文、池等待、提交、服务端 SQL/fsync/网络拆分和生产吞吐收益未知。不跑 c100×2000，不改生产 SQL/索引/参数/MQ/池/JVM，不 push/PR。

## 证据与交付

唯一 Maven 入口 `scripts/verify/mvn-verify.sh` 的 offline verify-service 全量及 `MAVEN_ARGS=-Dtest=VerifyEventOutboxMarkSentSpringMysqlIT` 条件真库 IT；记录命令、四计数、Skipped、退出码与绑定 revision。报告 `docs/perf/验证-outbox-markSent-Spring接线可配对.md`、低基数 JSON `docs/perf/data/exp-outbox-relay-mark-sent-spring-pairability.json`；原始日志放 ignored raw/。完成本两件套、OpenSpec 三件套、PLAN/总览和无参数 mailbox 契约，既有脏项原样保留。
