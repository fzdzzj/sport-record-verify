# TASK-145：拆解 verify outbox relay 批内成本与有限突发积压（默认关闭诊断，A=5000ms / B=500ms 各一轮）

## 目标

按 `spec/changes/measure-verify-outbox-relay-cost/` 三件套执行一次完整作业：在开工 HEAD
`28d277709d8020f49c6cff018652d1fe7c80975f` 上，把 TASK-144 定位到的
`callback→SENT`（outbox relay 投递）作为线索，**只定位 relay 的批内成本与有限突发积压**，
不做优化、不改运行默认值。

具体：在 `VerifyOutboxRelay` 增加**默认关闭、有界、低基数**的周期级诊断，把同一周期内的
`tryLock` 等待、`selectPendingBatch`、循环内 `syncSend` 合计、`markSent` 合计、`incrRetry`
合计、锁持有总墙钟与残差分离并聚合，附行数/成功/失败/耗尽计数；空轮与锁竞争按窗口汇总。
诊断使用单调时钟、固定低基数字段、每批至多一条摘要，不输出 payload/eventId/令牌/逐行标识，
关闭时不增加批次日志与额外 DB/MQ 调用，保持原异常/中断/解锁与 eventId 语义。补成功、失败、
空批、锁竞争、开关关闭及原有语义的单测。

先过仓库唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`，
必要时 `package` 并记录 jar 指纹。环境/服务/中间件/磁盘/积压安全与关联门槛满足后，最多做
**同 jar、同诊断开关**的 A=5000ms、B=500ms 各一轮 c100×2000 有限突发；每臂允许相同的至多
c10×100 预条件化并排空，预条件化不计入测量 cohort；失败/中止轮占预算，不补跑、不清库。

**决策口径**：本任务只测量。**即便 B 更快，也不得改运行默认 `relay-interval-ms=5000`，也不得
宣称持续吞吐已改善**；仅据可靠证据选择**下一项单一待证因素**。两轮不能判可复现收益；若需默认值
决策，须另立含重复、可比性、资源和更长稳态检验的提案。

规范来源：`spec/changes/measure-verify-outbox-relay-cost/`（proposal.md / tasks.json /
specs/sport-record-verify/spec-delta.md）。

## 开工基线

- HEAD 应为 `28d277709d8020f49c6cff018652d1fe7c80975f`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、
  `.trae/`、`spec/changes/add-verify-degrade-status-index/`；未 stash、未 add -A、不清库。

## 停止条件（要点）

1. 环境、服务健康、参数来源、时间关联、资源采集或磁盘安全不明 → 停止负载并交付未覆盖报告。
2. 诊断明显影响路径正确性、日志洪泛或引入额外远程调用 → 先修诊断，不得强行压测。
3. 出现失败、重投、关联缺口、异常增长积压或资源压力 → 停止进一步轮次，保留原始证据，
   不用后验解释丢弃样本；失败/中止轮计入两轮预算。
4. 不改批次、并发发送、重试、锁、SQL/索引、MQ、连接池或 JVM 参数；不改默认
   `relay-interval-ms=5000`；不 push、不建 PR。

## 判别式

- 覆盖：四服务栈（gateway/user/record/verify）健康可跑；leaderboard/mapmatch 缺席 →
  只报有可靠证据的局部链路，明确榜单段与 R5 未覆盖。
- 生效配置证明：`application.yml` 无 `verify.outbox.*` → `@Value` 默认值生效
  （`batch-size=100`、`relay-interval-ms=5000`、`relay-initial-delay-ms=10000`、`max-retry=16`）；
  B 臂以 `--verify.outbox.relay-interval-ms=500` 注入；两臂同加 `--verify.outbox.relay-diagnostics-enabled=true`。
- 批内闭合：按**同一周期**核对 `select+syncSend+markSent+incrRetry+残差` 与锁持有总时长；
  残差为余项，闭合是恒等式，**有信息的是占比**。分满批/短批/空轮及批间固定等待。
- 关联：`record.request_id = runId + "-" + seq` 反推 `runId`，verify 消费日志给出
  `eventId ↔ recordId`，outbox `payload.recordId` 反查 record → 同 run `callback→SENT` 分位。
- 积压分账：**可投递 PENDING**（`retry_count < maxRetry`）与**耗尽待人工 PENDING**
  （`retry_count >= maxRetry`）分开；不把 PENDING 总数当可自然排空的积压。
- 红证据分类：行为红 / 编译·契约红 / 环境红**据实区分、不混称**；只走唯一入口，记录真实退出码。

## 收口

三件套勾选（未满足条件的格子保持未勾）+ TASK-145 两件套 + 报告与机器摘要 + PLAN 验收记录 +
统一总览更新 + 改动清单一致性 + `git diff --check` + 无参数 mailbox 契约；
只提交本任务文件（业务与台账两笔本地提交），不 push、不建 PR。