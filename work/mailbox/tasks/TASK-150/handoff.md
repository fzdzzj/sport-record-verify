# TASK-150 回传：同一负载窗口内 verify_db markSent UPDATE 的 MySQL 服务端语句事件总墙钟

## 结论

开工 HEAD `b658b01558b5a34c670b008bec027901b51804b6`（与任务书一致，作业期间未换基线）。**结论等级：不可归因（UNCOVERED，测量未执行）**。原因：演示栈**不能健康起栈**——宿主端口 6379 被预存在的 Windows 服务 `Redis`（版本 3.0.504，非演示预期的 `redis:7.2-alpine`）占用，既有演示容器 `sport-verify-redis` 无法绑定该端口；按任务约束（仅无进程/端口冲突且为既有容器时才可启动）**不启动冲突容器**，故**未启动 Nacos/RocketMQ/Redis 与四个 Java 服务、未执行任何 `c100×2000` 负载、未产生任何服务端语句事件/外层标记比值**。**不编造比值、不以 scratch 或 TASK-149 数字冒充本任务结果、不为凑结论重跑。**

## 前提预检（只读，仪器侧成立）

- **起点核对**：HEAD `b658b01` 匹配；作业开始时无 `java.exe`；演示中间件容器 `sport-verify-{mysql,redis,nacos,rocketmq-namesrv,rocketmq-broker}` 均 `Exited (255)`；另有**其他会话**的 `task131-scratch-mysql`（Up，宿主 13318）——本任务仅对其做只读 digest 查询，未启停、未触其数据卷。既有脏项（归档移名删除侧、`spec/changes/archive/*`、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`、本任务未跟踪目录）**原样保留**；未 `git stash`、未 `git add -A`、未清库、未删数据卷、未重置 Performance Schema 计数器、未改 MySQL instrumentation。
- **演示 MySQL 只读预检（成立）**：`docker start sport-verify-mysql`（保留原卷 `sport-verify_mysql-data`，未重建、未 `down -v`）。容器 `sport-verify-mysql` 宿主 **3307**，版本 **8.0.46**；`@@performance_schema=1`；digest 容量 10000、汇总行 21、`DIGEST IS NULL` 0（**无溢出**）；`statement/sql/update`（及 select/insert/commit 等）`ENABLED=YES, TIMED=YES`；`statements_digest` consumer `ENABLED=YES`。`verify_db.verify_event_outbox` 共 **36400** 行**全部 `SENT`**、`retry_count` 0..0 → 可投递 PENDING=0、耗尽待人工 PENDING=0（**负载前无未解释积压**）。`SCHEMA_NAME='verify_db'` 的 digests **为空**（实例刚启动，P_S 内存态，pre 基线=不存在/0）。
- **目标 digest 已识别（仅辅助识别）**：`markSent` = `UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`，digest `6b07036bdfeae6b8f12d602eb37af86bfe73e3f9dac3600006dfd8cde12b05b7`（在 scratch `task147/148/149_marksent_scratch` 三个 schema 中一致；digest 与 schema 无关）；`incrRetry` 为**另一条** digest。**该识别来自 scratch，不充当演示库快照**。

## 未覆盖

- **未执行负载**：无 `run-perf.sh load 100 2000`；**负载退出码 = N/A（未运行）**；无目标 digest 前/后 `COUNT_STAR`+`SUM_TIMER_WAIT`；无 relay 非空批次原始摘要、成功/失败/重试/锁跳过；无提交 QPS/P50/P95；无资源读数；无演示库数据增长。
- **无配平、无比值**：因未产生任何 `markSent` 语句事件，不存在可配平计数，**不报告**「服务端语句事件总墙钟 / 外层标记总墙钟」同窗聚合比值，也不换算 `SUM_TIMER_WAIT` 单位、不逐批求和 `markMs`。
- 真实 relay 下的服务端语句事件份额；P_S 时间窗覆盖预热+主体的验证；其他实例/并发同 SQL 干扰排查（单实例未起，无从发生，也未证）；完整生产上下文（Nacos/MQ/Redis/Feign/调度/Web）；多实例锁竞争；`--mode=online`/CI；**未达外部门槛**。
- **不得由本任务推出**：不得称纯 SQL/fsync/池等待/网络/CPU/MyBatis，不得从独立 P50 相减，不得宣称延迟/吞吐改善，不得改 relay 默认 5000ms 或优化 markSent。

## 验收与退出码口径

- **纯文档/数据交付**：本任务**未改任何 Java/SQL/YAML/脚本** → **未运行 Maven**、**不把任何测试写为已跑/通过**；无 `mvn-verify.sh` 退出码可报。
- `git diff --check`、JSON 解析、无参数 `mailbox-contract.sh` 的退出码与提交前后状态见下方「实际改动清单」及 `docs/perf/data/raw/task150-*`。

## 工作树如实说明（必须保留）

- 未跟踪目录 `spec/changes/measure-verify-outbox-mark-sent-server-event/` 为**本任务**新增的三件套目录，随业务证据提交。
- 既有脏项（`spec/changes/adopt-native-mq-retry`、`spec/changes/wire-verify-outbox` 的归档移名删除侧与 `spec/changes/archive/*` 新路径侧、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）**非本任务产物、未暂存、原样保留**。
- 收尾仅停止本轮启动的 Java 服务（本轮**未启动任何 Java 服务**）；演示 MySQL 容器 `sport-verify-mysql` 保持运行，中间件状态如实回传，**未触碰其他会话进程/容器**。

## 实际改动清单（只改）

- **业务证据提交 `09e691a`（5 文件）**：`docs/perf/测量-outbox-markSent-服务端语句事件总墙钟.md`（新）、`docs/perf/data/exp-outbox-relay-mark-sent-server-event.json`（新）、`spec/changes/measure-verify-outbox-mark-sent-server-event/proposal.md`（新）、`spec/changes/measure-verify-outbox-mark-sent-server-event/tasks.json`（新）、`spec/changes/measure-verify-outbox-mark-sent-server-event/specs/sport-record-verify/spec-delta.md`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-150/spec.md`（新）、`work/mailbox/tasks/TASK-150/handoff.md`（新）、`work/mailbox/PLAN.md`（改）、`work/mailbox/后端优化机会总览-2026-09-26.md`（改）。
- 既有脏项原样保留；未用 `git stash`、未 `git add -A`、未 push、未建 PR。