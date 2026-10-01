# 提案：验证 markSent 服务端等待计数能否安全归因（TASK-154）

## Why

TASK-152 在单轮真实 relay 负载中测得 2010 条 `markSent` UPDATE 的 MySQL 服务端语句事件总墙钟 26,750.519526 ms，约占已记录的外层 `markMs` 73.9%；TASK-153 证明“批末统一标 SENT”在不改变当前可见性与重投边界的前提下 NO-GO。这些证据**未分离** MySQL 内部的日志、数据文件、表等待、锁或后台任务，也不支持直接调整数据库持久性参数。

对现有演示 MySQL 的只读检查显示：`events_waits_current/history/history_long` 均为 NO、`events_statements_history_long` 为 NO，但 `events_waits_summary_by_thread_by_event_name` 中存在非零计数。线程级聚合可能包含同一连接的其他语句，而后台刷盘可能计入其他线程；全局 fsync/等待计数还与 record_db 等共用。**这些计数能否支持目标 UPDATE 的受限归因，尚未证明。**先在独立 scratch 库做小样本可配对性判别，不能做到就止步，不再派发四服务负载。

## What Changes

1. 冻结 HEAD `88692cf0d67aeb7aed41ae647a8524b21af374f5`、脏项和 TASK-152/153 结论；只读登记演示与 scratch MySQL 的 P_S consumers/instruments/表结构、目标 digest 与计数范围。**不得**开关或清空 Performance Schema 仪器、修改 MySQL 配置、重启容器或重置任何计数器。
2. 在独立 `task154_wait_scratch` schema 用仓库真实 DDL 建最小 outbox 种子。测试专用目标 JDBC 连接先取 `CONNECTION_ID()`；观察连接独立地将它映射至 P_S `THREAD_ID`，在目标连接**只执行**实际 `markSent` 参数化 UPDATE 的前后读取对应线程的 waits 汇总和 scratch schema 目标 digest 增量。重复小样本与负对照（同线程非目标语句），检查每次影响行数、状态、连接/线程身份、计数器范围及稳定性；不会把全局汇总差额冒充单语句测量。
3. 强反例：同线程其他 SQL 污染；P_S 自身快照若在目标线程执行会污染；fsync/redo 在后台线程未体现在目标线程；waits 聚合存在嵌套或并发，不能简单相加；`events_waits_history_long` 关闭时无法凭聚合还原事件树。若目标/负对照无法区分或完整覆盖不可证，则判 **NO-GO（不可作服务端子项归因）**，只报直接观测与缺口。若能可靠对应某些*客户端连接线程*的计数，也只能给受限 GO：该类别在隔离实验可观测，**不代表完整事务、fsync 或生产负载份额**。
4. 只添加 test-only 条件真库 IT 和有界报告/机器摘要、三件套及 TASK-154 台账；条件式 IT 缺变量即跳过、不能算通过。运行离线 verify-service 常规测试和显式真库 IT，记录测试数、Skipped、退出码；JSON、`git diff --check`、无参数 mailbox 契约与只改清单。仅本地提交本任务文件，不 push/PR。本任务不改生产 Java/SQL/YAML、不改服务/数据库运行默认值、不跑 c100×2000，不给出优化收益。

## Impact

- 规范：仅增加 `sport-record-verify` 性能归因的证据门槛，不改原可靠投递语义或主规范总稿。
- 文件：隔离 test-only IT、`docs/perf/` 报告与机器摘要、OpenSpec 三件套、TASK-154 spec/handoff、PLAN/机会总览；原始日志在 ignored `docs/perf/data/raw/task154-*`。
- 环境：只在自有 scratch schema 内增删测试种子（保留明确标注的证据），不触碰演示 `verify_db`、其他 scratch schema、数据卷、宿主 Redis；不需要管理员服务控制或常驻四服务。
- 既有 archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/` 原状保留；不 stash、不 `git add -A`。

## 判定与停止条件

先证实线程、digest 与实际 UPDATE 增量的身份闭合，再讨论观测到的线程级 waits；任何计数关联失败、负对照不可区分、背景线程占比未知或启用新仪器才可得的数据，均不能换算成“markSent 内部 fsync/锁/纯 SQL 百分比”。无可安全对应的读数时如实 NO-GO，不改仪器、不凑结论、不启动大负载。受限 GO 也仅授权另立同负载、同仪器设置的验证提案，不实施参数或业务优化。
