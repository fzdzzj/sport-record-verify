# 提案：Spring 管理路径下 markSent 外层调用与 StatementHandler.update 的逐次配对成本判别（TASK-149）

## Why

TASK-148（HEAD `9fed299fd04b2218f7f30eaaca2a15b484b2597a`）只在受限 Spring 切片里证明「同一种 test-only、按 `MappedStatement.id` 限定的探针能把一次 `markSent` 与一条 `StatementHandler.update` 样本一一配对」，**但从未在同一次调用上同时取得外层 Mapper 调用墙钟与内层读数**。因此它无法回答下一个更小的问题：TASK-146 的外层混合墙钟里，有多少落在 `StatementHandler.update` 之外，即**残余**。

要回答的不是「markSent 为什么慢」，而是：**在 Spring 注入真实 Mapper、Hikari 与自动提交路径下，能否对同一次 `markSent` 调用同时记录外层与内层、逐次求「外层减内层」残余，且不改变语义、探针扰动在可接受范围**。这仍**不**定位生产内部成本，也**不**等于吞吐收益。

**最强反例**：① 探针在 Spring 装配下看不到同一次 `markSent` 的子调用，出现**缺配**；② 一次外层调用配到**多配**样本（串样本）；③ 探针本身**改变外层墙钟**（扰动不可接受，外层开启与关闭分布不可比）；④ 外层与内层来自**不同调用**却被相减；⑤ 状态 / 异常类型 / 独立连接可见性 / 连接归还被改变；⑥ 小样本**不可重复**（轮间波动盖过信号）。任一成立即**停止归因**，保留原始反例，不换第二种探针。

## What Changes

1. **第一道门槛——干净检出复核**：在仅含已提交 `9fed299` 源码的隔离检出上，重跑唯一入口 offline verify-service test 与 TASK-148 条件式真库 IT，确认这两条测试**不依赖**工作树中未跟踪的 `VerifyEventOutboxMarkSentSpringProbeMysqlIT.java`；记录四计数、Skipped、退出码与真实 Mapper / `SqlSessionTemplate` / 事务工厂 / Hikari 类型。**不能复现即判为证据缺口并停止新的成本测量**，不把编译红、环境红或 skip 叫行为红。
2. **只沿用 TASK-147 的同一种探针**：`@Intercepts(@Signature(type = StatementHandler.class, method = "update", args = {Statement.class}))`、按 `…VerifyEventOutboxMapper.markSent` 精确限定；不新增第二种探针、不注册生产插件链、不替换生产 DataSource。
3. **专用 TASK-149 scratch MySQL**：机械使用仓库 `sql/03-verify-db.sql`（改库名）建 `task149_marksent_scratch`；不碰演示 `verify_db` 或其他数据卷。
4. **同调用逐次配对**：同一线程、同一次 `markSent` 调用内，逐次记录外层 Mapper 调用墙钟、内层**整个** `StatementHandler.update` 调用墙钟、更新行数、成功/失败；**只对成功配对的同一调用**计算「外层减内层」**残余**，**不得**把两组独立 P50 相减。该残余混合连接获取、prepare/绑定、MyBatis/Spring 调用与提交等，**不得**命名为其中某一项。
5. **有界小样本**：预热 + **至少三轮**测量；记录探针**关闭的无插件对照**与开启结果、样本数 / 缺配 / 多配、Hikari 水位、可取得的 GC/CPU 范围及轮间波动；复核状态、异常类型、独立连接可见性、连接归还未变；探针对结果或成本产生不可接受的扰动即停止归因。
6. **不做**：不跑 c100×2000、不起常驻大负载；**GO 只表示「小样本同调用分账可重复且语义不变」，不授权 SQL 批量化**。
7. 同次作业完成唯一入口 offline test 与条件真库 IT、逐轮原始日志、有界报告/低基数 JSON、三件套真值、TASK-149 spec/handoff、PLAN 与机会总览、只改清单与必要的逻辑本地提交；不 push、不建 PR。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的成本归因验证增量，先写入本变更 `specs/sport-record-verify/spec-delta.md`，不直接修改总稿。
- 可能新增/修改：verify-service 测试源码（新 IT，沿用原探针）；`docs/perf/` 新报告与低基数 JSON；`work/mailbox/tasks/TASK-149/` 两件套；`work/mailbox/PLAN.md`、`work/mailbox/后端优化机会总览-2026-09-26.md`。
- **不变**：生产 Java/YAML/SQL；Mapper SQL、索引、relay 默认值、MQ、连接池、JVM；无 API 变更、无数据库迁移。不记录 eventId/payload/凭证/SQL 参数。
- 既有 archive 移名、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/` 与非本任务未跟踪文件原样保留；不 stash、不 `git add -A`。

## 决策与停止条件

- **GO** 仅表示：在专用 scratch 真库 + Spring 注入真实 Mapper 的受限切片中，同一次 `markSent` 的外层与内层可**逐次配对**、残余可重复（轮间波动可接受）、且语义（状态/异常类型/独立连接可见性/连接归还）与无插件基线一致。**不授权**任何优化（含 SQL 批量化），**不**声称纯服务端 SQL、fsync、池等待、生产 P99 或吞吐收益。
- **NO-GO / 环境不足**：若干净检出不能复现、缺配/多配出现、探针扰动不可接受、语义出现反例或 scratch 不可用，则**停止归因**，保留原始反例与未覆盖项，**不改试第二种探针**。
- 不论结果，**不**改生产插件链、Mapper SQL、索引、relay 默认值、MQ、连接池与 JVM；不 push、不建 PR。