# TASK-146：校正 relay 诊断口径并拆解逐行 markSent 调用成本（一轮有限突发测量，不优化）

## 目标

按 `spec/changes/measure-verify-outbox-mark-sent-cost/` 三件套执行一次完整作业：在开工 HEAD
`f1c9b6e46da641ab0079dba983aa2bf200d382f4` 上，**只校正 relay 诊断口径并测量逐行 `markSent`
的内部成本外边界**，不实施 markSent 优化、不改任何运行默认值。

具体：

1. **先写可复现的失败注入判别测试**，暴露旧计时的两处反例：
   (a) `markSent` 抛错时旧实现从 `sendStart` 再累计 `sendNanos`，把已计过的发送与失败的标记
   过程错归到发送；(b) 旧 `lockHoldMs` 在诊断日志与 `unlock()` 之前截取，不是完整占锁。
   红证据须是**目标行为断言失败**，不拿编译或环境失败冒充。
2. **修正计时口径**：发送段与标记段各自只累计一次；区分「锁内处理段」（`lockProcessingMs`）
   与解锁后计时段 `lockHoldMs`（终点在 `unlock()` 调用返回或抛错被捕获之后、摘要输出之前取得，
   含解锁调用、不含其后的摘要输出；解锁抛错被捕获时不代表锁已确实释放，不得称「完整占锁」）；
   默认关闭时不产生额外计时开销与 I/O；
   不新增逐事件日志、高基数标签或敏感字段；保持原 eventId、SENT、重试、异常传播与锁释放语义。
3. **审查** verify-service 的 MyBatis→JDBC→Hikari 实际接线与事务边界；仅以**安全、默认关闭、
   有界**的方式取得与 relay **同一次 markSent 调用可配对**的下层读数；Mapper/JDBC 客户端墙钟
   **不得**称为纯服务端 SQL、纯 fsync 或纯池等待；若无法安全配对或插桩改变连接/事务/异常语义，
   **停止插桩**，将内部构成记为**未知**，不为凑结论扩范围。
4. 先过仓库唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`，
   必要时 `package` 并记录 jar 指纹。只有测试、接线、健康、参数、进程存活及**负载前可投递/耗尽
   PENDING 分账**均满足条件，才做**最多一轮** c100×2000、**默认 `relay-interval-ms=5000`**、
   诊断开启的有限突发；失败或中止也占这一次预算，不补跑、不清库。
5. 采集同 run 关联、批内配对分段、积压/排空与**可得** JVM/GC/Hikari/MySQL/MQ 资源；缺失**明确
   记未知**。本轮为**不同 jar** 的测量轮，**不得**与 TASK-145 数字冒充优化前后收益对照。

**决策口径**：本任务只测量、只修诊断。**不得**改默认 `relay-interval-ms=5000`、批次、SQL/索引、
连接池、MQ 或 JVM 参数；**不得**声称 TASK-146 已优化吞吐；仅据可靠证据给出**唯一**下一步待证因素。

规范来源：`spec/changes/measure-verify-outbox-mark-sent-cost/`（proposal.md / tasks.json /
specs/sport-record-verify/spec-delta.md）。

## 开工基线

- HEAD 应为 `f1c9b6e46da641ab0079dba983aa2bf200d382f4`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、
  `.trae/`、`spec/changes/add-verify-degrade-status-index/`；未 stash、未 add -A、不清库。

## 停止条件（要点）

1. HEAD 已变化 → 停止并报告，不自行换基线。
2. 环境、服务健康、参数来源、接线可判定、进程存活或负载前 PENDING 分账不明 → 停止负载，
   交付未覆盖报告。
3. 插桩会改变连接/事务/异常语义，或下层读数无法与同一次 markSent 调用可靠配对 → **停止插桩**，
   把内部构成记为未知，不用全局差值/分位数相减臆断。
4. 出现失败、重投、关联缺口、异常增长积压或资源压力 → 停止进一步轮次，保留原始证据；
   唯一一轮失败/中止即计入预算，不补跑、不清库。
5. 不改批次、默认间隔、发送并发、重试、锁、SQL/索引、MQ、连接池或 JVM 参数；不 push、不建 PR。

## 判别式

- 覆盖：四服务栈（gateway/user/record/verify）健康可跑；leaderboard/mapmatch 缺席 →
  只报有可靠证据的局部链路，榜单段与 R5 明确未覆盖。
- 生效配置证明：`application.yml` 无 `verify.outbox.*` 且 Nacos `verify-service.yml` 不存在（404）
  → `@Value` 默认生效（`batch-size=100`、`relay-interval-ms=5000`、`relay-initial-delay-ms=10000`、
  `max-retry=16`）；本轮**唯一改动** = `--verify.outbox.relay-diagnostics-enabled=true`（默认 false）。
- 单实例佐证：诊断批次行 `rows` 合计 = `success` 合计 = cohort，`lockSkips` 全程 0。
- 计时判别：`markSent` 抛错时不得把已成功的发送/失败的标记错归到发送；`lockHoldMs` 须含解锁。
- 口径分名：`lockProcessingMs`（锁内处理段，不含摘要与解锁）vs `lockHoldMs`（解锁后计时段：
  终点在 `unlock()` 调用返回/抛错被捕获之后、摘要输出之前，含解锁调用、不含其后的摘要输出；
  不代表锁已释放，不得称「完整占锁」）。
- 积压分账：**可投递 PENDING**（`retry_count < maxRetry`）与**耗尽待人工 PENDING**
  （`retry_count >= maxRetry`）分开；不把 PENDING 总数当可自然排空的积压。
- 红证据分类：行为红 / 编译·契约红 / 环境红**据实区分、不混称**；只走唯一入口，记录真实退出码。

## 收口

三件套按实际覆盖勾选（未满足条件的格子保持未勾）+ TASK-146 两件套 + 报告与机器摘要 +
PLAN 验收记录 + 统一总览更新 + 改动清单一致性 + JSON 校验 + `git diff --check` + 无参数
mailbox 契约；只提交本任务文件（业务与台账两笔本地提交），不 push、不建 PR。