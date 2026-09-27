# TASK-149：Spring 管理路径下 markSent 外层调用与 StatementHandler.update 的逐次配对成本判别

## 目标与基线

开工 HEAD `9fed299fd04b2218f7f30eaaca2a15b484b2597a`（TASK-148 收口）。在 TASK-148 已证实的受限 Spring 管理 Mapper 切片中，回答：能否对**同一次** `markSent` 调用、在**同一线程**逐次同时取得「外层 Mapper 调用墙钟」与「内层整个 `StatementHandler.update` 调用墙钟」，逐次求「外层减内层」残余，且不改变所测语义。**只沿用 TASK-147 的同一个 test-only、按 `MappedStatement.id` 精确限定的探针**，不引入第二种探针。

## 边界与判据

- 第一道门槛：在仅含已提交 `9fed299` 的隔离检出上重跑唯一入口 offline verify-service 全量与 TASK-148 条件式 scratch 真库 IT，确认测试**不依赖**开局那个未跟踪的 `VerifyEventOutboxMarkSentSpringProbeMysqlIT.java`；记录四计数、Skipped、退出码与真实 Mapper/SqlSessionTemplate/事务工厂/Hikari 类型。不能复现即判**证据缺口**并停止新测量；编译红、环境红、skip 不得称作行为红。
- 专用 scratch `task149_marksent_scratch`，用仓库真实 DDL `sql/03-verify-db.sql` 机械替换库名灌入 `task131-scratch-mysql`；**不碰演示 `verify_db` 或其他数据卷**。有界小样本：预热 + ≥3 轮每轮 100 次目标调用 + 无插件对照。
- **只对成功配对的同一调用**计算 `outer − inner` 残余；**不得**将两组独立 P50 相减。残余是**混合量**（连接获取、prepare/绑定、MyBatis/Spring 调用、自动提交相关 commit/close 等），**不得**命名为其中任一单项，亦不得称纯服务端 SQL / fsync / 池等待 / 生产 P99 / 吞吐收益。
- 记录样本数/缺配/多配、Hikari 水位、可取得的 GC/CPU 范围与轮间波动；复核状态、异常类型、独立连接可见性、连接归还未变。探针产生不可接受的扰动即**停止归因**。
- **GO 只表示「小样本同调用分账可重复且语义不变」，不授权 SQL 批量化**；NO-GO 或环境不足时保留原始反例，**不改试第二种探针**。不跑 c100×2000、不起常驻大负载，不改生产插件链/Mapper SQL/索引/relay 默认值/MQ/连接池/JVM；不 push/PR。

## 证据与交付

唯一 Maven 入口 `scripts/verify/mvn-verify.sh` 的 offline verify-service 全量及 `MAVEN_ARGS=-Dtest=VerifyEventOutboxMarkSentSpringPairedCostMysqlIT` 条件真库 IT；记录命令、退出码、四计数、Skipped、revision 与未覆盖项。报告 `docs/perf/验证-outbox-markSent-Spring外层内层逐次配对成本.md`、低基数 JSON `docs/perf/data/exp-outbox-relay-mark-sent-spring-paired-cost.json`；原始日志放 ignored `docs/perf/data/raw/`。完成本两件套、OpenSpec 三件套、PLAN/总览和无参数 mailbox 契约；既有脏项（归档移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`）原样保留。