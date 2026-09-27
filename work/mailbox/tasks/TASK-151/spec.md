# TASK-151：恢复 TASK-150 未覆盖的真实 relay 同负载窗口服务端语句事件测量

## 目标与基线

开工 HEAD `02a16af312aed8792a44eb64d7ba57ad0c00067d`。在 TASK-150 的**同一问题**与**同停止判据**下，尝试恢复一次同负载窗口测量：MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的**服务端语句事件总墙钟**，相对 relay 外层 `markMs` 同窗总墙钟的量级。该值**不是**逐请求配对读数，**不是**纯 SQL / 纯 fsync / 完整事务成本，**不得**由独立 P50 相减或把差额命名为池等待 / 网络 / CPU / MyBatis。

本任务**不实施优化**，**不改 TASK-150 的未覆盖结论**，**不得**把 TASK-150 改写为测量成功。

## 边界与判据（沿用 TASK-150）

- **冻结**：HEAD / 既有脏项 / 进程 / 容器 / 数据状态；不改 MySQL instrumentation、不清库、不删数据卷、不重置 Performance Schema 计数器。
- **端口释放（本任务新增的唯一授权）**：仅授权在**确认不中断其他明确可见使用者且可逆**时，以**服务管理器临时暂停名为 `Redis` 的 Windows 服务**，以启动既有 `sport-verify-redis` 容器；**禁止** `Stop-Process`、改启动类型、改端口 / compose、重建容器 / 数据卷，**禁止**以宿主 Redis 3.0.504 代替演示 Redis 7.2。**无权限 / 依赖不明 / 恢复不可靠即立即停止，按未覆盖收口。**
- **起栈**：仅在无进程 / 端口冲突且为既有容器时，按序启动既有演示 MySQL（核对原卷、`verify_db` 可投递 / 耗尽 PENDING 与 Performance Schema）→ 必要的 Nacos / Redis / RocketMQ → 单实例四个 Java 服务；不得 `down -v`、重建数据卷或启动无关服务。
- **一次负载**：前提全部成立才做**最多一次** `run-perf.sh load 100 2000`（预热 10）；固定同一 jar、默认 `relay-interval=5000`、原 batch / maxRetry、池 / MQ / JVM / SQL / 索引，仅开启既有有界 relay 诊断。负载前 / 排空后保存目标 digest 的 `COUNT_STAR` / `SUM_TIMER_WAIT` 与资源快照；原始文件名独立 `task151-*`，**不覆盖** TASK-145～150。
- **配平**：仅当目标 digest `COUNT_STAR` 增量 = 本轮成功 `markSent` 次数 = relay 批次成功行数，且时间窗覆盖预热及主体、无并发同 SQL 干扰时，才换算 `SUM_TIMER_WAIT` 单位并逐批求和 `markMs`，报告**同窗聚合比值**及舍入边界。
- **停止条件**：任何配平失败、仪器缺失、服务异常、不能健康起栈、无权限、积压未排空，都保留原始证据并判**不可归因**；**不得**为凑结论重跑负载、改 instrumentation 或换第二种测量办法。
- **不改业务**：不优化 `markSent`、不新增生产插件、不改默认值 / 业务代码；不 push / PR。

## 证据与交付

报告 `docs/perf/复测-outbox-markSent-服务端语句事件总墙钟.md`、低基数 JSON `docs/perf/data/exp-outbox-relay-mark-sent-server-event-resume.json`；原始输出放 ignored `docs/perf/data/raw/task151-*`。完成本两件套、OpenSpec 三件套据实勾选、TASK-151 spec/handoff、PLAN/机会总览、实际文件清单、JSON 校验、`git diff --check` 与无参数 `mailbox-contract.sh`。**纯文档 / 数据交付不声称跑过 Maven**；若实际改脚本或代码必须补对应验证。仅 stage 本任务文件，必要时业务证据与台账分两笔本地提交。既有脏项（归档移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`）原样保留。