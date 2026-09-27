# 复测：同负载窗口内 verify_db markSent UPDATE 的 MySQL 服务端语句事件总墙钟（TASK-151，未执行 / 环境权限阻塞）

- **任务**：在 TASK-150 的同一问题与停止判据下，恢复真实 relay 的同负载窗口测量——同一真实负载、同一时间窗内，MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的服务端语句事件总墙钟，相对 relay 外层 `markMs` 同窗总墙钟的量级。**只测量、不优化**。
- **开工 HEAD**：`02a16af312aed8792a44eb64d7ba57ad0c00067d`（与任务书一致；作业期间未换基线，未被他会话推进）。
- **日期**：2026-09-27。
- **机器摘要**：`docs/perf/data/exp-outbox-relay-mark-sent-server-event-resume.json`。
- **原始证据（gitignore，未入库）**：`docs/perf/data/raw/task151-*`。
- **结论**：**测量未执行；归因不可判定（UNCOVERED）**。唯一被本任务授权的端口释放手段——临时暂停宿主 Windows 服务 `Redis`——在本机**无管理员权限**下不可执行（`Access is denied`）。演示 `redis:7.2-alpine` 容器仍无法绑定 6379，故**未启动任何中间件与四个 Java 服务、未执行任何负载、未产生任何服务端/外层比值**。本任务不改写 TASK-150 的未覆盖结论。

## 0. 结论摘要

1. **冻结成立**：HEAD `02a16af` 与任务书一致；既有脏项（两个 archive 移名的删除侧、`spec/changes/archive/*` 新路径侧、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）与 TASK-150 结束时一致，本轮**未触碰、未 stash、未 `git add -A`**；本任务未跟踪三件套 `spec/changes/resume-verify-outbox-mark-sent-server-event/` 作为交付输入原样保留。
2. **环境阻塞比对 TASK-150 未消解**：宿主 6379 仍被**预存在 Windows 服务 `Redis`（版本 3.0.504，StartMode=Auto，Running，PID 6684）**占用；既有演示容器 `sport-verify-redis`（`redis:7.2-alpine`，`Running=false`，`PortBindings=6379`）无法绑定该端口。
3. **唯一被授权的释放动作不可执行**：按提示词与三件套，仅授权「在可逆且不中断其他明确可见使用者时，**临时暂停名为 `Redis` 的 Windows 服务**」。实测 `Stop-Service -Name Redis -Force` 返回 `Cannot open Redis service on computer '.'`；`sc.exe stop Redis` 返回 `[SC] OpenService FAILED 5: Access is denied`；当前主体 `LAPTOP-CNQRQ4HR\fzdzzj` 的 `IsAdmin=False`，`net session` 亦 `System error 5`。**无权限**属任务既定停止条件，故**立即停止测量、按未覆盖收口**。
4. **未采用任何被禁止的替代手段**：未 `Stop-Process`、未改启动类型、未改端口/compose、未重建容器/数据卷，未以宿主 Redis 3.0.504 代替演示 Redis 7.2。
5. **未运行负载**：无 `run-perf.sh load 100 2000`（**负载退出码 = N/A（未运行）**）；无目标 digest 前/后 `COUNT_STAR`/`SUM_TIMER_WAIT`、无 relay 批次原始摘要、无提交 QPS/P50/P95、无资源读数、无演示库数据增长。**不编造比值、不以 scratch 或 TASK-149 数字冒充本任务结果、不为凑结论重跑。**
6. **零业务改动、未跑 Maven**：未改任何 Java / SQL / YAML / 脚本 / relay 默认值 / MQ / 连接池 / JVM / MySQL instrumentation → **未运行 Maven**，不把任何测试写为已跑/通过。

## 1. 冻结基线与起点核对（只读）

- **HEAD**：`02a16af312aed8792a44eb64d7ba57ad0c00067d`，与任务书一致，未更换基线。
- **工作树既有脏项（原样保留）**：
  - 删除侧（归档移名旧路径）：`spec/changes/adopt-native-mq-retry/{proposal.md,specs/sport-record-verify/spec-delta.md,tasks.json}`、`spec/changes/wire-verify-outbox/{proposal.md,specs/sport-record-verify/spec-delta.md,tasks.json}`；
  - 未跟踪：`spec/changes/archive/adopt-native-mq-retry/`、`spec/changes/archive/wire-verify-outbox/`、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`；
  - 本任务交付输入（未跟踪）：`spec/changes/resume-verify-outbox-mark-sent-server-event/`。
- **中间件/容器起点**：演示中间件 `sport-verify-mysql` **Up（healthy，宿主 3307）**（TASK-150 启动后保持运行）、`sport-verify-{redis,nacos,rocketmq-namesrv,rocketmq-broker}` 均 `Exited (255)`；另有**其他会话**的 `task131-scratch-mysql`（Up，宿主 13318）——本任务只做只读状态记录，未启停、未触其数据卷。
- **起点 Java 进程**：存在两个 `java.exe`，**均属另一仓库/会话**（`D:\code\crmAndRag-merge-add-knowledge-admin-api` 的 `mvn -o -B -ntp clean verify` 及其 surefire fork，配套 `testcontainers-ryuk` 容器）——**非本任务四服务**，本任务未启停它们。
- **jar 起点**：`record-service`(2026-09-26 11:33)、`user-service`(2026-09-25 17:28)、`gateway-service`(2026-09-25 17:28) 的 `target/*.jar` 在位；**`verify-service/target/*.jar` 缺失**（`target/` 只剩 `classes/`、`test-classes/`、`surefire-reports/`，被 `mvn-verify.sh` 的 `clean` 清掉）。因测量未执行，**未据此起栈、未为此构建 jar**。

原始证据：`docs/perf/data/raw/task151-00-env-start.txt`。

## 2. 环境阻塞：宿主 Redis 服务的可逆暂停不可执行（无权限）

任务与三件套「宿主 Redis 端口冲突仅可经可逆操作消除」把释放 6379 的**唯一**手段限定为临时暂停宿主服务：

| 事实 | 证据 |
| --- | --- |
| 宿主 6379 监听属于 Windows 服务 `Redis` | `Get-NetTCPConnection -LocalPort 6379`：`::` 与 `0.0.0.0` 两行 `Listen`，`OwningProcess=6684` |
| 服务原状态/启动类型/承载用户 | `Get-CimInstance Win32_Service`：`Name=Redis State=Running StartMode=Auto`，`PathName="D:\develop1\Redis-x64-3.0.504\redis-server.exe" --service-run redis.windows.conf`，`StartName=NT AUTHORITY\NetworkService`，`ProcessId=6684`（父进程 `services.exe` PID 1996） |
| 显见客户端 | `Get-NetTCPConnection -RemotePort 6379 -State Established` → **(none)**；作业全程无到 6379 的已建立连接 |
| 依赖归属 | 逐一比对另一活跃会话仓库 `D:\code\crmAndRag-merge-add-knowledge-admin-api` 全文：`redis/6379` 仅 4 处偶然命中（文档/测试夹具），**无应用配置引用**；该会话走 Testcontainers 随机端口 → **不依赖宿主 6379** |
| 演示容器期望 | `docker inspect sport-verify-redis`：`Image=redis:7.2-alpine`、`Running=false`、`RestartPolicy=no`、`PortBindings={"6379/tcp":[{"HostPort":"6379"}]}` |
| **暂停尝试（授权动作）** | `Stop-Service -Name Redis -Force` → `Service 'Redis (Redis)' cannot be stopped ... Cannot open Redis service on computer '.'`；`sc.exe stop Redis` → `[SC] OpenService FAILED 5: Access is denied`（`sc exit=5`） |
| 权限主体 | `[Security.Principal.WindowsPrincipal].IsInRole(Administrator)` → **False**；`net session` → `System error 5 has occurred / Access is denied` |

**判定**：任务的「无权限…立即停止测量，按未覆盖收口」条件成立。既有演示容器仍无法绑定被占用的 6379，四服务所需中间件无法按既有容器组齐 → **不能健康起栈**。故：

- 不启动冲突容器、不启动 Nacos/RocketMQ/Redis、不启动四个 Java 服务；
- **不执行负载**，不产生任何 digest 计数与比值。

原始证据：`docs/perf/data/raw/task151-01-redis-pause.txt`。

**未采用的被禁替代**（逐条排除）：`Stop-Process`（禁）／改启动类型（禁）／改端口或 compose（禁）／重建容器或数据卷（禁）／以宿主 Redis 3.0.504 代替演示 Redis 7.2（禁）。

## 3. 恢复与核验（本轮未改变环境）

本任务对被授权动作 `Stop-Service` 的尝试**失败且无副作用**；除此之外**未启动任何容器、未启动任何 Java、未改任何服务/端口/卷/配置**。核验：

| 项 | 恢复前（起点） | 恢复后（核验） |
| --- | --- | --- |
| 宿主 `Redis` 服务 | Running / Automatic | **Running / Automatic**（未变） |
| 6379 监听 | PID 6684（`::`+`0.0.0.0`） | **PID 6684**（未变） |
| 演示 `sport-verify-redis` | `Running=false`，`RestartPolicy=no` | **`Running=false`**（未启动） |
| 本任务启动的 Java | 无 | **无** |
| 既有脏项 | 6 删除 + 6 未跟踪目录 | **一致**（未触碰） |

无「恢复失败」项；无清库、无删卷。原始证据：`docs/perf/data/raw/task151-02-restore.txt`。

## 4. 未执行 / 未覆盖清单

- **未执行负载**：无 `run-perf.sh load 100 2000`；**负载退出码 = N/A（未运行）**；无目标 digest 前/后 `COUNT_STAR`+`SUM_TIMER_WAIT`；无 relay 非空批次原始摘要、无成功/失败/重试/锁跳过、无提交 QPS/P50/P95、无资源读数、无演示库数据增长。
- **无配平、无比值**：因未产生任何 `markSent` 语句事件，**不存在可配平的计数**，**不报告**「服务端语句事件总墙钟 / 外层标记总墙钟」同窗聚合比值，不换算 `SUM_TIMER_WAIT` 单位、不逐批求和 `markMs`。
- **未覆盖**：真实 relay 下服务端语句事件份额；P_S 时间窗覆盖预热+主体的验证；其他实例/并发同 SQL 干扰排查（单实例未起，无从发生，也未证）；`verify_db` 负载前可投递/耗尽 PENDING 分账在**本轮**的复核；演示栈健康、同一 jar、默认 relay=5000ms、既有有界诊断开关的**起栈核对**；完整生产上下文（Nacos/MQ/Redis/Feign/调度/Web）；多实例锁竞争；`--mode=online`/CI；未达外部门槛。
- **不得由本任务推出**：不得称纯 SQL/fsync/池等待/网络/CPU/MyBatis，不得从独立 P50 相减，不得宣称延迟/吞吐改善，不得改 relay 默认 5000ms 或优化 markSent。

## 5. 判据、退出码与契约口径

- **纯文档/数据交付**：本任务**未改任何脚本或代码** → **未运行 Maven**，**不把任何测试写为已跑/通过**；无 `mvn-verify.sh` 退出码可报。
- `git diff --check`、JSON 解析、无参数 `mailbox-contract.sh` 的退出码与提交前后状态见 `docs/perf/data/raw/task151-*` 与 TASK-151 handoff。

## 6. 证据文件（原始，gitignore 未入库）

- `docs/perf/data/raw/task151-00-env-start.txt`：开工 HEAD、工作树脏项、宿主 Redis 服务/6379/客户端、容器、Java 与 jar 起点状态。
- `docs/perf/data/raw/task151-01-redis-pause.txt`：暂停授权动作的前/后状态与 `Access is denied` 原始报错。
- `docs/perf/data/raw/task151-02-restore.txt`：恢复与核验（服务/端口/容器/脏项未变）。