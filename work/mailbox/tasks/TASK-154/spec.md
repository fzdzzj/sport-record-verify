# TASK-154：判别 outbox `markSent` 线程等待可归因性（只读 Performance Schema 观测，不优化）

## 目标与基线

开工 HEAD `88692cf0d67aeb7aed41ae647a8524b21af374f5`。在**不改任何仪器、不重置计数器、不做任何配置变更**的前提下，
只读登记的 Performance Schema 汇总表能否把真实 `markSent` 条件 UPDATE 引起的等待**安全归因**到执行它的客户端连接线程，
或进一步拆成「fsync / 锁 / 纯 SQL」子项。

**只裁决，不实施优化**：不改生产 Java/SQL/YAML/运行默认值，不跑 c100×2000，不换算任何占比；
**不得**把 TASK-152 的 0.739312 反推成待优化子项或可回收收益；TASK-153 的批末标记 NO-GO **不得借此翻案**。

## 边界与判据

- **先只读预检**：登记 scratch/演示两实例的 `setup_consumers`、wait/statement instruments、线程 waits 汇总与目标 digest；
  **不得**开关仪器、重置计数器、改 MySQL 配置或重启/重建容器。已知 `events_waits_current/history/history_long=NO`；
  **不得**把全局等待计数直接归给 `markSent`。
- **受控实验**：专用 `task154_wait_scratch`（仓库真实 DDL 机械改名）；test-only 条件真库 IT 用独立 `DriverManager`
  连接（autocommit=true 与生产同语义）取 `CONNECTION_ID()` 映射 `THREAD_ID`；目标连接**只执行一条**真实 `markSent`
  参数化 SQL（文本从生产 Mapper 注解运行时渲染）；观察连接读前/后快照；逐窗核对线程身份、影响行数、状态与
  计数增量（语句总计数增量=1 保证观察查询不污染目标线程；digest 增量=1）。
- **重复小样本 + 负对照**：`w1..w5`；同线程 `n1` 不存在 id（0 行）、`n2` 已 SENT 行（0 行）、`n3` `SELECT 1`、
  `n4` 同表 SELECT、`n5` 真实 `incrRetry`；序列前后取后台 `thread/innodb/%` 线程与全局 waits、`SHOW GLOBAL STATUS`。
- **一句裁决**：身份或负对照无法闭合、需开启新仪器、等待存在嵌套/后台归属不明 → **NO-GO**，只报告直接读数与无法拆分
  的原因；即使通过也只能给「隔离环境中某些客户端连接线程等待可观测」的受限 GO，不得称完整事务成本/生产瓶颈/优化收益。
- **不改业务**：不 push / PR；只 stage 本任务文件；既有脏项（归档移名、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/`）原状，不 stash、不 `git add -A`。

## 证据与交付

报告 `docs/perf/判别-outbox-markSent-线程等待可归因性.md`、低基数 JSON
`docs/perf/data/exp-outbox-relay-mark-sent-wait-attribution.json`；判别 IT
`verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMarkSentWaitAttributionMysqlIT.java`；
原始输出放 ignored `docs/perf/data/raw/task154-*`。完成本两件套、OpenSpec 三件套据实勾选、TASK-154 spec/handoff、
PLAN / 机会总览、实际文件清单、JSON 校验、`git diff --check` 与无参数 `mailbox-contract.sh`（记录提交前 / 后退出码）。
仅 stage 本任务文件，业务证据与台账分两笔本地提交。
