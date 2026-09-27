# TASK-152：具备管理员权限时完成 markSent 同窗语句事件测量

## 目标与基线

开工 HEAD `9cf6d174f4a1ae2c2ded644ba757d758873261b8`。在 TASK-150 的**同一问题**与**同一配平判据**下，完成一次真实 relay 的**同负载窗口**测量：MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的**服务端语句事件总墙钟**，相对 relay 外层 `markMs` 同窗总墙钟的量级。该值**不是**逐请求配对读数，**不是**纯 SQL / 纯 fsync / 完整事务成本，**不得**由独立 P50 相减或把差额命名为池等待 / 网络 / CPU / MyBatis。

**不实施优化**，**不改写 TASK-150/151 的「不可归因（测量未执行）」结论**。

## 边界与判据

- **硬门槛（中断服务之前）**：核对 HEAD、既有脏项与其他会话活动；确认当前会话**已提升**且具备 Windows 服务控制权限，记录 6379 / 宿主 `Redis` / 客户端 / 依赖；经唯一入口 `scripts/verify/mvn-verify.sh --mode=offline --pl verify-service package` 补齐缺失 jar，记录退出码 / 测试数 / 产物与其他服务 jar。权限或构建不成立即停止（不写新的空未覆盖提交）。
- **受控切换**：仅在无已知依赖且能保证恢复时，经**服务管理器临时暂停**宿主 `Redis`，启动既有 `sport-verify-redis` 容器；**不得** `Stop-Process`、改启动类型、改端口 / compose、重建容器或数据卷、以宿主 Redis 3.0.504 代替演示 Redis。任何中途失败优先进入环境恢复。
- **前置**：演示 MySQL 原卷、`verify_db` 可投递 / 耗尽 PENDING、P_S 开关 / 唯一目标 digest / 溢出；必要中间件与**单实例**四服务健康、同一 jar、默认 `relay-interval=5000ms`、仅开启**既有有界** relay 诊断。前提不成立不跑负载。
- **一次负载**：仅前提全成立才做**最多一次** `run-perf.sh load 100 2000`（含预热 10）。留前后同窗 `COUNT_STAR` / `SUM_TIMER_WAIT`、全部非空 relay 批次 `markMs` / 成功行 / 重试 / 锁跳过、cohort 排空、资源读数、退出码与 QPS / P50 / P95。原始输出独立 `docs/perf/data/raw/task152-*`，不覆盖 TASK-145～151。
- **配平**：仅当目标 digest 唯一、窗口完整、无其他实例 / 同 SQL 干扰，且 `COUNT_STAR` 增量 = 成功 `markSent` 次数 = 批次成功行数时，才换算 `SUM_TIMER_WAIT` 单位并逐批求和 `markMs`，报告**同窗聚合比值**与舍入边界；不配平则只报原始数并判不可归因，**不重跑凑结论**。
- **强制恢复**：无论成败，**先**停本轮 Java 与演示 Redis，**再**恢复宿主 `Redis` 原状态与启动类型并核验 6379；其他容器只恢复本轮确知改变且未被接管的状态。恢复失败置回传首位。
- **不改业务**：不优化 `markSent`、不新增生产插件、不改默认值 / 业务代码；不 push / PR。

## 证据与交付

报告 `docs/perf/复测-outbox-markSent-服务端语句事件总墙钟-管理员窗口.md`、低基数 JSON `docs/perf/data/exp-outbox-relay-mark-sent-server-event-admin-window.json`；原始输出放 ignored `docs/perf/data/raw/task152-*`。完成本两件套、OpenSpec 三件套据实勾选、TASK-152 spec/handoff、PLAN / 机会总览、实际文件清单、JSON 校验、`git diff --check` 与无参数 `mailbox-contract.sh`（记录提交前 / 后退出码）。仅 stage 本任务文件，业务证据与台账分两笔本地提交。既有脏项（归档移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`）原样保留。
