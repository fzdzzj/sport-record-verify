# TASK-147 提案：订正 relay 证据口径并在隔离环境验证 markSent 可配对测量

## Why

TASK-146（HEAD `767de7b04f90c562d775f515f382e40d016556ce`）通过外层计时确认：诊断开启、单轮 c100×2000 下满批逐行 `markSent` 的调用墙钟仍占锁内处理段约 80%；但连接获取、客户端 JDBC、提交、服务端 SQL 等内部构成**未测得**。再跑同样的外层负载不会回答下一问题，不能据此批量改 UPDATE、调池或改默认间隔。

复核发现两处口径需纠正：当前 `lockHoldMs` 的终点在 `unlock()` 调用返回或抛错被捕获之后，**但诊断摘要在终点之后输出**，故不包含摘要输出；若 `unlock()` 抛错，未能证明锁真正释放，不得无条件称“完整占锁”。TASK-146 的积压数 1571 是**首次采样后观测到的峰值**；首次采样晚于负载结束，真实峰值未知且至少为 1571。修文案不篡改原始数字。

TASK-146 对 DataSource 包装 / MyBatis 拦截器“不可安全”作了评估，但没有用隔离、版本一致、真 MySQL 的试验证明所有可配对测量路径都不成立。MyBatis 允许在明确的更新与语句执行点设置插件，并不自动保证在本仓 Spring 装配里安全、也不直接等于纯 SQL 或提交耗时。因此下一步是**证明或证伪一条受限的测量接线**，而非直接给生产服务加全局插件。

**最强反例**：测试插件可能在 MyBatis-Plus/Spring 的实际装配中看不到同一次 `markSent` 的子调用；可能改变连接复用、事务/auto-commit、异常类型、更新行数或诊断关闭开销。只要这些反例不能用真实接线测试排除，就停止，不以两个独立分位数或 MySQL 全局计数凑出拆账结论。

## What Changes

1. 精确订正 TASK-146 报告、机器摘要和代码注释中的 `lockHoldMs`、解锁失败和“积压峰值”表述；如改测试，只断言真实计时边界和失败路径，不改 relay 投递语义。
2. 先做只读调用链审计：确认实际版本的 Spring/MyBatis-Plus/Hikari、事务和自动提交路径，设计至多**一种**最小、测试专用的候选测量接线（例如按 `MappedStatement.id` 限定的 MyBatis 插件）；写出能配对的边界、不能测的段及停机/退出清理条件。仅用于隔离 scratch，**禁止直接在生产配置注册全局插件或替换数据源**。
3. 条件具备时，在隔离 scratch MySQL 用真实 `verify_event_outbox` DDL 和真实 Mapper/Spring 装配做小样本判别：目标 `markSent` 执行一次、与下层读数同调用配对；非目标 Mapper 不被计入；开关关闭、异常、连续调用、事务/更新行数和连接释放与未插桩语义一致。配对层若只到 `StatementHandler.update`，应称 JDBC 客户端调用墙钟；连接获取、提交与服务端执行仍记未知。
4. 无法取得隔离库、装配证明失败或有语义偏差时停止候选接线，撤回试验性实现，记录反证和下一验证办法；不可用 Mockito 冒充真 MySQL 绿证据。**本任务禁止 c100×2000 及任何常驻服务大负载**，不重复消耗事件链压测预算、不清演示数据库。
5. 同次作业完成必要离线测试、报告/摘要、三件套状态、TASK-147 台账、PLAN/机会总览、只改清单、契约及必要本地提交。不 push、不建 PR。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的 relay 成本归因能力增量（仅写本 change 的 spec-delta，不直接修改总稿）。
- 可能修改：`verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` 的注释；测试专用的最小 probe/IT；`docs/perf/拆解-outbox-relay-markSent-调用成本.md`、`docs/perf/data/exp-outbox-relay-mark-sent-cost.json`、TASK-146 handoff 中的错误口径及 TASK-147 报告/摘要、台账/总览。
- 不变：生产数据源/MyBatis 插件链、Mapper SQL、数据库 schema/索引、事件 ID/SENT/重试、锁逻辑、默认 5000ms/诊断默认关闭及 MQ/池/JVM 参数。无 API 变更、无数据库迁移、无常驻负载。

## 决策与停止条件

仅在真 MySQL + 本仓真实 Mapper 装配中，证明同调用配对、范围限定、开关/失败清理与状态语义不变，才可报告**“此层测量办法可行”**；不等于生产插桩已安全、也不等于标记内部瓶颈已定位。缺条件或反证成立则报告不可判定/不推荐，不转去试第二、第三方案，不以代码修改或单测全绿替代接线证据。无论哪种结论，TASK-147 不优化 `markSent`、不改默认值、不跑负载。