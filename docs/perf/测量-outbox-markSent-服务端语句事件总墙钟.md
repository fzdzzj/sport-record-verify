# 测量：同负载窗口内 verify_db markSent UPDATE 的 MySQL 服务端语句事件总墙钟（TASK-150，未执行 / 不可归因）

- **任务**：回答同一真实 relay 负载、同一时间窗内，MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的**服务端语句事件总墙钟**，相对于 relay 外层 `markMs` 同窗总墙钟是什么量级。**只测量、不优化**。
- **开工 HEAD**：`b658b01558b5a34c670b008bec027901b51804b6`（与任务书一致；作业期间未换基线）。
- **日期**：2026-09-27。
- **机器摘要**：`docs/perf/data/exp-outbox-relay-mark-sent-server-event.json`。
- **原始证据（gitignore，未入库）**：`docs/perf/data/raw/task150-*`。
- **结论**：**测量未执行；归因不可判定（UNCOVERED）**。原因是演示栈**不能健康起栈**——宿主端口 6379 被预存在的 Windows 服务 `Redis`（版本 3.0.504）占用，演示 `redis:7.2-alpine` 容器无法绑定该端口；按任务约束（仅无进程/端口冲突且为既有容器时才可启动）不启动冲突容器，故**未启动本任务四个 Java 服务、未执行任何 `c100×2000` 负载、未产生任何服务端/外层比值**。

## 0. 结论摘要

1. **仪器侧前提成立（只读核对）**：演示 MySQL（容器 `sport-verify-mysql`，宿主 **3307**，原数据卷 `sport-verify_mysql-data`）`performance_schema=ON`；`statement/sql/update` 与相关语句仪器均 `ENABLED+TIMED`；`statements_digest` consumer `ENABLED`；digest 表无溢出（`digests=21 / null_digests=0`，容量 10000）。
2. **目标 digest 已识别（仅辅助）**：`markSent` = `UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`，其语句 digest 为 `6b07036bdfeae6b8f12d602eb37af86bfe73e3f9dac3600006dfd8cde12b05b7`（在 scratch `task147/148/149_marksent_scratch` 三个 schema 中一致，digest 与 schema 无关；`incrRetry` 为另一条 digest）。**该识别来自 scratch，不充当演示库快照**。
3. **负载前无未解释积压**：演示 `verify_db.verify_event_outbox` 共 **36400** 行，**全部 `SENT`**、`retry_count` 0..0 → 可投递 PENDING=0、耗尽待人工 PENDING=0。
4. **但服务栈侧前提不成立**：宿主 6379 被 Windows 服务 `Redis`（3.0.504，随开机自动启动）占用，演示 Redis 容器无法绑定 6379 → 四服务所需中间件无法按既有容器组齐 → **不能健康起栈**。
5. **未运行负载**：无目标 digest 的 `COUNT_STAR`/`SUM_TIMER_WAIT` 增量、无 relay 批次原始摘要、无提交 QPS/P50/P95、无 `serverEventTotal / outerMarkTotal` 聚合比值。**不编造比值、不以 scratch 或 TASK-149 数字冒充本任务结果、不为凑结论重跑。**
6. **零业务改动**：未改任何 Java / SQL / YAML / 脚本 / relay 默认值 / MQ / 连接池 / JVM / MySQL instrumentation；未 push、未建 PR。

## 1. 冻结基线与起点核对（只读）

- **HEAD**：`b658b01558b5a34c670b008bec027901b51804b6`，与任务书一致，未更换基线。
- **工作树既有脏项（原样保留，未 stash、未 `git add -A`）**：`spec/changes/adopt-native-mq-retry/*` 与 `spec/changes/wire-verify-outbox/*` 的删除（归档移名的旧路径侧）、未跟踪 `spec/changes/archive/adopt-native-mq-retry/`、`spec/changes/archive/wire-verify-outbox/`、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`。
- **起点进程/容器**：作业开始时**无 `java.exe` 进程**；演示中间件容器 `sport-verify-{mysql,redis,nacos,rocketmq-namesrv,rocketmq-broker}` 均 `Exited (255) 6 hours ago`；另有**其他会话**的 `task131-scratch-mysql`（Up，宿主 13318）——本任务只对其做只读 digest 查询，未启动/停止它，未触碰其数据卷。

## 2. 演示 MySQL 只读预检（成立）

启动既有容器 `sport-verify-mysql`（`docker start`，保留原卷，未重建、未 `down -v`、未清库）后只读取证。原始输出见 `docs/perf/data/raw/task150-01-ps-settings.txt`、`task150-03-verify-db-and-ps.txt`。

| 项 | 值 |
| --- | --- |
| 版本 / `@@performance_schema` / digest 容量 | 8.0.46 / **1（ON）** / 10000 |
| `statement/sql/update`（及 select/insert/commit 等） | `ENABLED=YES, TIMED=YES` |
| `statements_digest` consumer | `ENABLED=YES`（`events_statements_cpu/history_long` 为 NO，不影响 digest 汇总表） |
| digest 汇总行 / `DIGEST IS NULL` | 21 / 0（**无溢出**） |
| `verify_db` 下 `verify_event_outbox` 行数 | 36400（全 `SENT`，`retry_count` 0..0） |
| `SCHEMA_NAME='verify_db'` 的 digests | **空**（实例刚启动，P_S 为内存态；目标 digest 尚未被本实例记录，故 pre 快照基线=不存在/0） |

- 仪器开关**未做任何修改**；未重置 Performance Schema 计数器、未清库、未删数据卷。

## 3. 停止原因：Redis 端口冲突致不能健康起栈

按任务允许的顺序，先启动演示 MySQL；在启动其余必要中间件前发现**端口冲突**：

| 事实 | 证据 |
| --- | --- |
| 宿主 6379 监听属于 **Windows 服务 `Redis`**（非本栈容器），版本 **3.0.504**，`os:Windows` | `Get-Service`：`Redis` Status=Running StartType=Automatic；`Get-NetTCPConnection` 6379 → PID 6684（`redis-server.exe`）；raw `INFO server` 返回 `redis_version:3.0.504` |
| 演示 `sport-verify-redis` 期望绑定宿主 **6379** | `docker inspect sport-verify-redis`：`Image=redis:7.2-alpine`、`PortBindings={"6379/tcp":[{"HostPort":"6379"}]}`、当前 `Running=false` |
| 结论 | 既有容器 `sport-verify-redis` **无法绑定已被占用的 6379**；版本（3.0.504）亦非演示预期的 `redis:7.2-alpine`。按任务约束「仅在无进程/端口冲突且确认是既有容器时才允许启动」**不启动冲突容器**，不以宿主 3.0 冒充演示 Redis |

- 其余端口不冲突：MySQL 3307、Nacos 8848/9848、RocketMQ 9876/10911/10909 均空闲。**唯一阻塞是 Redis 6379。**
- 用户裁定：**停止并记未覆盖**。
- 结果：**未启动 Nacos/RocketMQ/Redis，未启动四个 Java 服务**，因此**未执行负载**。这属于任务的既定停止条件（「不能健康起栈…就停止测量并记未覆盖」）。

## 4. 未执行 / 未覆盖清单

- **未执行负载**：无 `run-perf.sh load 100 2000`；**负载退出码 = N/A（未运行）**；无目标 digest 前/后 `COUNT_STAR`+`SUM_TIMER_WAIT`；无 relay 非空批次原始摘要、无成功/失败/重试/锁跳过、无提交 QPS/P50/P95、无资源读数、无演示库数据增长。
- **无配平、无比值**：因未产生任何 `markSent` 语句事件，**不存在可配平的计数**，**不报告** `服务端语句事件总墙钟 / 外层标记总墙钟` 的同窗聚合比值。
- **未覆盖**：真实 relay 下的服务端语句事件份额；`SUM_TIMER_WAIT` 单位换算与逐批 `markMs` 求和；P_S 时间窗覆盖预热+主体的验证；其他实例/并发同 SQL 干扰排查（因单实例未起，无从发生，但也未证）；完整生产上下文（Nacos/MQ/Redis/Feign/调度/Web）；多实例锁竞争；`--mode=online`/CI；未达外部门槛。
- **不得由本任务推出的结论**：不得称纯 SQL/fsync/池等待/网络/CPU/MyBatis，不得从独立 P50 相减，不得宣称延迟/吞吐改善，不得改 relay 默认 5000ms 或优化 markSent。

## 5. 判据、退出码与契约口径

- **纯文档/数据交付**：本任务**未改任何脚本或代码** → **未运行 Maven**，**不把任何测试写为已跑/通过**；无 `mvn-verify.sh` 退出码可报。
- `git diff --check`、JSON 解析、无参数 `mailbox-contract.sh` 的退出码与提交状态见 `docs/perf/data/raw/task150-*` 与 TASK-150 handoff。

## 6. 证据文件（原始，gitignore 未入库）

- `docs/perf/data/raw/task150-00-env-start.txt`：开工 HEAD、工作树脏项、容器状态。
- `docs/perf/data/raw/task150-01-ps-settings.txt`：P_S 开关、仪器、consumer、digest 溢出。
- `docs/perf/data/raw/task150-02-digest-identify.txt`：目标 `markSent` digest 识别（scratch 辅助）。
- `docs/perf/data/raw/task150-03-verify-db-and-ps.txt`：`verify_db` 分账、`SCHEMA_NAME='verify_db'` digests（空）、全局状态。
- `docs/perf/data/raw/task150-07-redis-port-conflict.txt`：6379 归属、服务状态、容器端口绑定。