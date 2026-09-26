# TASK-143：当前 HEAD 校验判定与榜单事件分段积压归因（只度量不优化）

## 目标

按 `spec/changes/measure-verify-event-stage-lag/` 三件套执行一次完整作业：在当前 HEAD
`997c789aa7420e2b33d3d6c0a23cf6066846cd04` 上，对「提交 → SUBMITTED 发布 → MQ 消费 →
判定落库 → outbox → SENT → 榜单」事件链做**受控分段归因**。先核对每段起止点、关联键、
时间精度与重试/失败路径；只读盘点 outbox 的**可投递 PENDING / 重试耗尽待人工 PENDING / SENT**
三类（用实际生效 `maxRetry`），不得把 PENDING 总数当可自然排空的积压。安全门槛满足后最多跑
**一轮** c100×2000，仅对同 run 可成对关联的样本算阶段延迟，逐段给 n/精度/P50/P95/P99/失败/重复/缺失。
**不实施任何性能优化**，不改服务业务代码、SQL、默认配置或 `LoadTest` 请求体。

规范来源：`spec/changes/measure-verify-event-stage-lag/`（proposal.md / tasks.json /
specs/sport-record-verify/spec-delta.md）。

## 开工基线

- HEAD 应为 `997c789aa7420e2b33d3d6c0a23cf6066846cd04`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、
  `.trae/`、`spec/changes/add-verify-degrade-status-index/` 及未跟踪
  `work/mailbox/后端优化机会总览-2026-09-26.md`；未 stash、未 add -A。

## 停止条件（要点）

1. 完整链路无法就绪不等于四服务局部无效，但必须缩窄结论；连请求与 DB/日志样本不能可靠关联时
   不执行大负载。
2. 任何持续服务异常、磁盘不足、MySQL/MQ 不健康，或已有队列无法判断新旧来源 → 停止，记录退出码与未覆盖。
3. 负载最多一轮（内置 10 预热计入该轮）；失败也计入预算，不重跑凑结果；不清库、不覆盖历史 raw、
   不向演示库灌故障行。
4. 不调 relay 周期/批次、消费线程、连接池、SQL、索引、JVM、缓存或事务边界；不 push、不建 PR。

## 判别式

- 覆盖：四服务栈（gateway/user/record/verify）健康可跑；leaderboard/mapmatch 缺席 → 按 spec
  只报告有可靠证据的局部链路，明确榜单与 R5 未覆盖，不造完整质量指标。
- 关联：`record.request_id = runId + "-" + seq` 反推 `runId`（只读方法），verify 消费日志给出
  `eventId ↔ recordId`，outbox `payload.recordId` 反查 record → 同 run 配对样本。
- outbox 分账：`status='PENDING' AND retry_count<maxRetry`（可投递）与 `>=maxRetry`（待人工）
  及 `SENT` 各自 count/最老年龄；`maxRetry` 取实际生效值 16。
- 门槛：`bash scripts/verify/mvn-verify.sh`（仅在改动 Java 时）；本任务为只读度量，未改 Java，
  故不跑 Maven 并说明理由；另验机器摘要 JSON、`git diff --check`、`mailbox-contract.sh` 退出码。

## 收口

三件套勾选 + TASK-143 两件套 + PLAN 验收记录 + 改动清单一致性 + mailbox 契约；
只提交本任务文件（业务与台账两笔本地提交），不 push、不建 PR。
