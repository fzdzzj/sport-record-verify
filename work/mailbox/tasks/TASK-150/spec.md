# TASK-150：同一负载窗口内 verify_db markSent UPDATE 的 MySQL 服务端语句事件总墙钟

## 目标与基线

开工 HEAD `b658b01558b5a34c670b008bec027901b51804b6`。回答**唯一一个问题**：在同一真实 relay 负载、**同一时间窗**内，MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的**服务端语句事件总墙钟**，相对于 relay 外层 `markMs` 同窗总墙钟是什么量级。该值**不是**逐请求配对读数，**不是**纯 SQL / 纯 fsync / 完整事务成本，也**不得**由独立 P50 相减或把差额直接命名为池等待/网络/CPU/MyBatis。

本任务与 TASK-149 是**不同问题**：TASK-149 是受限 Spring 外层/内层逐次配对；本任务要求真实 relay 同一负载窗口的服务端语句事件总量，**不得**改做另一轮 scratch 配对实验，**不得**把 TASK-149 数字冒充本任务结果。

## 边界与判据

- **冻结**：HEAD/既有脏项/进程/容器/数据状态；不改 MySQL instrumentation、不清库、不删数据卷、不重置 Performance Schema 计数器。
- **起栈**：仅在没有进程/端口冲突且为既有容器时，按序启动既有演示 MySQL（核对原卷、`verify_db` 可投递/耗尽 PENDING 与 Performance Schema）→ 必要的 Nacos/Redis/RocketMQ → 单实例四个 Java 服务；不得 `down -v`、重建数据卷或启动无关服务。
- **只读预检**：演示库确认 `performance_schema=ON`、`statement/sql/update` 已 `ENABLED/TIMED`、`statements_digest` 已启用；识别 `SCHEMA_NAME=verify_db` 下 `markSent` UPDATE **唯一** digest；排查溢出与其他同 SQL 干扰。scratch 中的 digest **只辅助识别**，**不充当演示库快照**。
- **一次负载**：前提全部成立才做**最多一次** `run-perf.sh load 100 2000`；固定同一 jar、默认 `relay-interval=5000`、原 batch/maxRetry、池/MQ/JVM/SQL/索引，仅开启既有有界 relay 诊断。负载前保存目标 digest 的 `COUNT_STAR`/`SUM_TIMER_WAIT` 与资源快照；排空后保存同一 digest 后快照、所有非空批次原始摘要、成功/失败/重试/锁跳过、提交 QPS/P50/P95、资源读数与数据增长。原始文件名独立 `task150-*`，**不覆盖** TASK-145～149。
- **配平**：仅当目标 digest `COUNT_STAR` 增量 = 本轮成功 `markSent` 次数 = relay 批次成功行数，且时间窗覆盖预热及主体、无并发同 SQL 干扰时，才换算 `SUM_TIMER_WAIT` 单位并逐批求和 `markMs`，报告「服务端语句事件总墙钟 / 外层标记总墙钟」**同窗聚合比值**及舍入边界。
- **停止条件**：任何配平失败、仪器缺失、服务异常、不能健康起栈、负载前存在未解释积压或积压未排空，都保留原始证据并判**不可归因**；**不得**为凑结论重跑负载、改 instrumentation 或换第二种测量办法。
- **不改业务**：不优化 `markSent`、不新增生产插件、不改默认值/业务代码；不 push/PR。

## 证据与交付

报告 `docs/perf/测量-outbox-markSent-服务端语句事件总墙钟.md`、低基数 JSON `docs/perf/data/exp-outbox-relay-mark-sent-server-event.json`；原始输出放 ignored `docs/perf/data/raw/task150-*`。完成本两件套、OpenSpec 三件套据实勾选、PLAN/机会总览、实际文件清单、`git diff --check` 与无参数 `mailbox-contract.sh`。**纯文档/数据交付不声称跑过 Maven**；若实际改脚本或代码必须补对应验证。仅 stage 本任务文件，必要时业务证据与台账分两笔本地提交。既有脏项（归档移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`）原样保留。