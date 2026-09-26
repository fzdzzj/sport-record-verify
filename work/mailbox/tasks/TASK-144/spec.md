# TASK-144：只检验 verify outbox relay 批间调度空档（fixedDelay A=5000ms / B=500ms，A-B-B-A）

## 目标

按 `spec/changes/update-verify-outbox-relay-delay/` 三件套执行一次完整作业：在开工 HEAD
`8268aac8e7b2d1e542c04ddf526dbf834f87e0ad` 上，把 TASK-143 定位到的支配段
`callback→SENT`（outbox relay 投递）作为单一候选因素，只改 `verify.outbox.relay-interval-ms`
（A=5000ms、B=500ms）做同 jar、同负载的受控重复对照。先独立复算 TASK-143 原始样本的
SENT 长空档与百条批跨度，核对真实生效配置（`@Value` 默认 100/5000/16）、outbox
「可投递 PENDING / 耗尽待人工 PENDING」两类分账，以及同 run `recordId/eventId` 关联口径。
安全与关联门槛满足后，按 **A-B-B-A** 至多四轮 c100×2000；每轮前确认可投递 PENDING=0，
每轮后等本轮排空；失败轮也占预算。

**决策口径**：四轮提交 QPS 相对四轮中位数偏差超过 ±15%，或 outbox 创建形态不可比，
或任何失败/重试混入 → 结论记**未定**，不凑收益。只有两轮 B 的 `callback→SENT` P50 都比
两轮 A 中较好者至少低 20%、P95 不劣、可靠投递语义与资源门槛无回归，才把唯一默认配置
`verify.outbox.relay-interval-ms=500` 写入 verify-service 实际 classpath YAML 并补判别测试；
**否则不改运行默认值**，不叠加批次、并发发送、SQL、MQ、连接池或 JVM 任何参数。

同一作业另完成 TASK-143 已提交报告/机器摘要的**三处文字口径订正（不改原始数字）**：
SENT 不是榜单消费完成；独立 P50 比值不是逐请求占比；净投递速率差的内部构成尚未分离。

规范来源：`spec/changes/update-verify-outbox-relay-delay/`（proposal.md / tasks.json /
specs/sport-record-verify/spec-delta.md）。

## 开工基线

- HEAD 应为 `8268aac8e7b2d1e542c04ddf526dbf834f87e0ad`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、
  `.trae/`、`spec/changes/add-verify-degrade-status-index/` 及未跟踪
  `spec/changes/update-verify-outbox-relay-delay/`；未 stash、未 add -A。

## 停止条件（要点）

1. 环境、数据关联、有效配置或起栈状态不确定，或出现健康/磁盘/消息积压异常 → 立即停止，
   不为满足四轮强行继续；失败发生在第一轮也计入四轮预算。
2. B 两轮收益方向不一致、到达速率不具可比性、QPS 偏差超 ±15%、故障/重试混入或资源恶化 →
   不改默认值，记录未定/不推荐及原因；不换 B 档试探。
3. 除 `relay-interval-ms` 外不改 batch-size、重试上限、消费线程、SQL、MQ、连接池、JVM。
4. 不清库、不覆盖历史 raw、不向演示库灌故障行；不 push、不建 PR。

## 判别式

- 覆盖：四服务栈（gateway/user/record/verify）健康可跑；leaderboard/mapmatch 缺席 → 只报
  有可靠证据的局部链路，明确榜单段与 R5 未覆盖。
- 关联：`record.request_id = runId + "-" + seq` 反推 `runId`（只读方法），verify 消费日志给出
  `eventId ↔ recordId`，outbox `payload.recordId` 反查 record → 同 run 配对 `callback→SENT`。
- 生效配置证明：application.yml 无 `verify.outbox.*` + Nacos `verify-service.yml`
  `config data not exist` → `@Value` 默认值；B 轮以 `--verify.outbox.relay-interval-ms=500` 注入。
- relay 生效佐证：A 轮 SENT 长空档（>1s）中位 ≈5000ms、净速率 ≈15 行/s；B 轮几乎无长空档、
  净速率 ≈36~37 行/s。
- 门槛：本任务决策为「不改默认值」→ 未改 Java/YAML/SQL，无编译/测试对象，不跑 Maven 并说明
  理由；另验机器摘要 JSON、`git diff --check`、`mailbox-contract.sh` 退出码。

## 收口

三件套勾选（未满足条件的格子保持未勾）+ TASK-144 两件套 + PLAN 验收记录 + 统一总览更新 +
改动清单一致性 + mailbox 契约；只提交本任务文件（业务与台账两笔本地提交），不 push、不建 PR。