# 拆解：verify outbox relay 批内成本与有限突发积压（默认关闭诊断，A=5000ms / B=500ms 各一轮）

- **任务**：TASK-145，只定位 relay **批内成本**与**有限突发积压**，不优化、不改默认值。
- **开工 HEAD**：`28d277709d8020f49c6cff018652d1fe7c80975f`（与任务书一致）。
- **日期**：2026-09-26。**机器摘要**：`docs/perf/data/exp-outbox-relay-batch-cost.json`。
- **原始数据**（gitignore，未入库）：`docs/perf/data/raw/task145-*`（两臂 `*-resources.txt`、`*-diagnostics.txt`、
  `*-samples.csv`、`*-stats.txt`、`*-backlog.txt`、`*-ob-{created,sent}-per-sec.txt`、`*-load.log`、
  `task145-offline-test.log`、分析脚本 `task145-{diag-analyze,pending-peak}.ps1`、
  异常记录 `task145-anomaly-verify-process-exit.md`）。
- **对照基线**：TASK-143（`997c789a`）、TASK-144（`8268aac8`），只作线索与一致性核对，不当作重复轮次。

## 0. 结论摘要

1. **批内成本已分离（本任务首次）**：relay 锁内占时**最大段是逐行 `markSent`**，不是 `syncSend`。
   满批（rows=100）中位数占锁持有：
   | 臂 | lockWait | selectPendingBatch | syncSend 合计 | markSent 合计 | incrRetry 合计 | 残差 | 锁持有总墙钟 |
   | --- | --- | --- | --- | --- | --- | --- | --- |
   | A（5000ms） | 0ms | 5ms (0.4%) | 246ms (18.8%) | **1046ms (79.7%)** | 0 | 11ms (0.8%) | 1312ms |
   | B（500ms） | 0ms | 4ms (0.3%) | 324ms (22.3%) | **1037ms (71.4%)** | 0 | 76ms (5.2%) | 1452ms |
   `syncSend` 与 `markSent` 都是**混合墙钟**（分别含序列化/网络/确认与获取连接/客户端等待），**不得**称纯 MQ 或纯 SQL。
2. **周期闭合成立**：A 周期 P50 **6315ms** ≈ 5000ms 固定等待 + 1312ms 锁内（实测 5000+1312=6312）；
   B 稳态满批段相邻周期中位 **≈1941ms** ≈ 500ms + 1452ms（实测 500+1452=1952）。
   周期内 `select+syncSend+markSent+incrRetry+残差` 与锁持有按定义闭合（残差=余项，故闭合是恒等式，真正有信息的是占比）。
3. **有限突发积压**：两臂均从可投递 PENDING 峰值排空到 **0**（耗尽待人工全程 0）。
   A 峰值 **1837** 条 @23:36:23，排空 **137.8s**、净 **14.58 行/s**；B 峰值 **1668** 条 @23:41:02，排空 **54.1s**、净 **37.15 行/s**。
4. **不改默认值、不宣称持续吞吐改善**：`relay-interval-ms` 运行默认**保持 5000**；本作业未写任何 YAML/Java/参数，
   B 更快只作为本轮观测记录。**两轮不能判可复现收益**。
5. **下一步唯一待证因素**：**逐行 `markSent` 的批内成本构成**。依据：它是两臂锁内占比最高段（79.7% / 71.4%），
   且在 B 臂已是**整周期占比最高段**（1037/1941 ≈ 53%，高于 500ms 固定等待的 26%）——
   继续缩短间隔的边际收益已受 relay 自身单轮成本限制。
6. **异常如实记录**：臂 B 的 verify 进程在排空后、after 资源采样前**静默退出**（无 stderr、无 hs_err、无 REPLAY，
   日志在最后一条正常诊断行处截断）。故**臂 B 的 after 侧 JVM/GC/内存/线程增量 = 未知**；两轮预算已用尽，未补跑。

## 1. 环境与「唯一因素」证明

- 服务栈：`scripts/perf/run-perf.sh start-services` 启动 gateway:8080 / user:8081 / record:8082 / verify:8083；
  8081/8082/8083 `/actuator/health` 均 200。**leaderboard / mapmatch 不在栈内**，PostGIS 容器未运行 → 榜单段与真实 R5 未覆盖（四服务局部口径）。
- 中间件四容器：MySQL 8.0.46（`sport-verify-mysql`，宿主 **3307**）/ RocketMQ 5.2.0（namesrv+broker）/ Redis 7.2 / Nacos 2.3.2 均 Up healthy。
- **同一 jar**：两臂复用同一 verify 包，`sha256=95CC9207021162B55D6E140B5C4FC5130EBA1EB4F05A6EADADD645224EAA2670`，`jarSwapDuringRounds=NONE`。
  （离线 `clean test` 后该 jar 已被 clean 删除，哈希为运行期实测留存。）
- **实际生效配置**（非推测）：`verify-service/src/main/resources/application.yml` **无** `verify.outbox.*` 键 → `@Value` 默认值生效：
  `batch-size=100`、`relay-interval-ms=5000`、`relay-initial-delay-ms=10000`、`max-retry=16`；
  `VerifyOutboxRelay = @Scheduled(fixedDelayString="${verify.outbox.relay-interval-ms:5000}", initialDelayString="...:10000")`（fixedDelay 语义）。
- **唯一差异**：A 臂走默认 5000；B 臂启动仅追加 `--verify.outbox.relay-interval-ms=500`。
  **两臂都追加同一诊断开关** `--verify.outbox.relay-diagnostics-enabled=true`。
- **未改**：批次、并发发送方式、重试、锁、SQL/索引、MQ、连接池、JVM 参数；两臂均以 `MYSQL_PORT=3307` 启动。
- 每臂均做**相同**的至多 c10×100 预条件化并排空（A：`task145-A-pre`；B：`task145-B-pre`），预条件化请求**不计入**测量 cohort；
  测量前 `可投递 PENDING=0 / 耗尽待人工 PENDING=0`。

## 2. 默认关闭、有界、低基数的周期级诊断

- 开关 `verify.outbox.relay-diagnostics-enabled` **默认 false**；关闭时**不产生批次日志、不增加额外 DB/MQ 调用**（单测 `relay_diagnosticsDisabled_emitsNoDiagnosticSummary`、`disabledSwitch_isNoOp_andReportsNothing`）。
- 开启时每个**非空批次至多一条**摘要（`rows/success/failed/exhausted/lockWaitMs/selectMs/sendMs/markMs/incrRetryMs/lockHoldMs/residualMs/emptyRounds/lockSkips`）；
  **空轮与锁竞争**按 `relay-diagnostics-window-ms`（默认 10000ms）**窗口汇总至多一条**，不逐行、无 payload/eventId/token。
- **真实服务侧有界性佐证**（臂 A 启动后的空闲段，`docs/perf/data/raw/task145-A-diagnostics.txt` 第 1~11 行）：
  23:33:35 起每 **10s** 恰好一条空轮汇总，`emptyRounds` 在 5s 周期下为 2/窗口，`lockSkips` 恒 0 —— 有界频率在真服务上成立。
- 计时用单调时钟 `System.nanoTime()`；**原有异常、解锁与 `eventId` 语义未改**（逐行日志与失败路径保持原样）。
- 单测覆盖：`RelayDiagnosticsTest`（6）+ `VerifyOutboxRelayTest`（13，含成功/失败/空批/锁竞争/开关关闭），共 104 用例通过。

## 3. 两轮受控负载（同 jar、同诊断开关、c100×2000）

`bash scripts/perf/run-perf.sh load 100 2000 task145-<臂>`；每臂 1 轮，失败轮占预算，未补跑。

| 臂 | 间隔(ms) | 成功/限流/错误 | wall(s) | 提交QPS | 提交P50/P95/P99/MAX(ms) |
| --- | --- | --- | --- | --- | --- |
| A | 5000（默认） | 2000/0/0 | 13.03 | 153.5 | 620.6/881.7/1190.0/2034.3 |
| B | 500（启动覆盖） | 2000/0/0 | 10.52 | 190.2 | 508.6/620.1/1028.0/1503.3 |

同 run 关联（只读，未改 LoadTest）：每臂 cohort = main 2000 + 内置预热 10 = **2010** 条，
`outboxLinked=2010`、`published=consume=callback=sent=2010`，**关联覆盖率 100%**，无重复消费行、无缺失。

| 臂 | recordId 区间 | outbox id 区间 | relayFailLines | relayExhaustedLines |
| --- | --- | --- | --- | --- |
| A | 50583..52592 | 30261..32270 | 0 | 0 |
| B | 52703..54712 | 32381..34390 | 0 | 0 |

## 4. 周期闭合与批内分解（核心）

### 4.1 每周期分位（诊断摘要，同进程单调时钟）

| 臂 | 批次类型 | n | lockWait | select | syncSend | markSent | incrRetry | 残差 | 锁持有 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 满批(=100) | 19 | 0 | 5 | 246 | **1046** | 0 | 11 | 1312 |
| A | 短批(<100) | 3 | 1 | 6 | 89 | 378 | 0 | 4 | 476 |
| A | 全部批次 | 22 | 0 | 5 | 245 | 1044.5 | 0 | 11 | 1305.5 |
| B | 满批(=100) | 18 | 0 | 4 | 324 | **1037** | 0 | 76 | 1452 |
| B | 短批(<100) | 13 | 0 | 3 | 62 | 315 | 0 | 18 | 396 |
| B | 全部批次 | 31 | 0 | 3 | 312 | 984 | 0 | 74 | 1389 |

（单位 ms，P50；A 满批 `select+send+mark+incrRetry+残差 = 1308` 对锁持有 1312，差 −4ms 来自各段独立取中位数，**不是**逐周期求和。）

### 4.2 周期级预算（固定等待 + 锁内）

- **A**：周期 P50 **6315ms**（MIN 5490 / P95 7221 / MAX 11122，MAX 出现在创建突发叠加期）
  = 5000ms 固定等到（**79.2%**）+ 锁内 1312ms（20.8%）；锁内 **markSent 1046ms = 整周期 16.6%**，`syncSend` 246ms = 3.9%。
- **B**：稳态满批段相邻周期中位 **≈1941ms**（全窗口 P50 1894 / MIN 578 / MAX 6145）
  = 500ms 固定等到（**25.8%**）+ 锁内 1452ms（74.2%）；锁内 **markSent 1037ms = 整周期 53.4%**，`syncSend` 324ms = 16.7%。
- **口径声明**：周期级聚合与同 run 事件延迟是两种口径，**不相加、不互补**；上表占比均为「同周期聚合量的比值」，
  **不是逐请求占比**，也不是纯 MQ / 纯 SQL / 纯 CPU 耗时。

### 4.3 真实批次形态 vs 日志分组

- 诊断给出的 A 真实批次大小：`9,64,100×19,37`；B：`9,2,28,3,12,19,3,10,18,10,14,14,100×17,68`
  （B 在创建突发期因 500ms 间隔及时跟上，批次小而密；突发期仅投出 142 行，其余转入满批排空）。
- 用「SENT 相邻间距 >1s」的日志分组的旧启发式在 B 臂**失效**（合并成 9 / 2001 两组），
  与 TASK-144 报告一致：**日志分组不等于真实调度周期**；本次以诊断摘要为准。

## 5. 有限突发积压与时间桶

| 臂 | 可投递 PENDING 峰值 | 耗尽待人工峰值 | 排空完成 | relay 首末 SENT 跨度 | 净投递速率 |
| --- | --- | --- | --- | --- | --- |
| A | **1837** @23:36:23 | 0 | 23:38:19 | 137.8s | 14.58 行/s |
| B | **1668** @23:41:02 | 0 | 23:41:35 | 54.1s | 37.15 行/s |

- **outbox 创建 5s 桶**（DB `created_at`，秒精度）：A `42/55/219/857/837`（和 2010）；B `43/71/515/907/474`（和 2010）。
- **outbox 投递(SENT) 5s 桶**：A 共 27 桶，主体为 **100/5s**（受「100 行 / 6.3s 周期」上限约束）；
  B 共 12 桶：`42/72/71/83/174/260/248/273/242/277/243/25`（峰值 ≈250~277/5s ≈ 50~55 行/s）。
- 两臂**创建形态是「涓流 + 突发」**（消费者侧在突发后追平），**创建桶 ≠ 提交桶**；A 创建跨度 23s、B 21s，均长于提交 wall（13.03s / 10.52s）。
- **PENDING 分账**：全程 `retry_count>0` 为 **0**，故「可投递」与「耗尽待人工」两类均可自然排空；
  **不把 PENDING 总数当可自然排空的积压**。每臂排空后两类均 = 0。

## 6. 资源读数（可获得项 + 采集缺口）

- **臂 A（同时间轴 before→after，`task145-A-{before,after}-resources.txt`）**：
  GC 年轻代次数 3→21（+18 次）、暂停合计 0.013→0.082s（**+69ms / 166.6s 窗口**，占比极小）；
  累计分配 +1.29GB、晋升 +54.9MB；堆 used Eden 81.8→12.6MB / Old 58.3→111.9MB；
  `hikaricp_connections` active 0 / idle 10 / max 10 全程未变、`timeout_total` 0；线程 248→216；
  `process_cpu_usage` 为**瞬时 gauge**，before 0.0508 / after 0.0123，**不可相加**。
- **臂 B**：before 已采集（uptime 52.3s，GC 3 次 / 0.018s，累计分配 2.06e8，线程 233，Hikari 0/10）；
  **after = `PROM_SCRAPE_FAILED`（进程已退出，累计计数器随之销毁）→ 臂 B 的 JVM/GC/内存/线程增量记「未知」**。
- **MySQL 全局状态**（`SHOW GLOBAL STATUS`，**全局累计**，非单轮隔离）：
  A 窗口 `Queries +50690`、`Com_update +4020`、`Com_select +20241`、`Innodb_rows_read +1032271`；
  B 窗口 `Queries +50573`、`Com_update +4020`、`Com_select +20200`、`Innodb_rows_read +915091`。
  - **交叉校验**：两臂 `Com_update` 全部精确 **+4020 = 2×2010**（2010 次 `markSent` + 2010 次记录状态更新），与「每行恰好一次成功标记、无重发」一致。
  - **`Innodb_row_lock_waits` 7→7、`Innodb_row_lock_time` 62→62 两臂均无增量** → 本窗口未观测到 InnoDB 行锁竞争（全局口径）。
- **锁竞争**：两臂诊断 `lockSkips` 与 `lockWaitMs` 全程 0 → 单实例运行下 Redisson `verify:outbox:relay` **零竞争**；
  **多实例竞争未覆盖**。
- **MQ broker 读数不可得**：容器内 `mqadmin: command not found`（脚本 best-effort 分支），记 **`MQ_BROKER_STATUS_UNAVAILABLE`**，不以「无异常」代替。
- **口径声明**：采样器与计时本身的开销未知；本作业是**诊断开启轮**，**不与 TASK-144 计时关闭的数值做因果比较**。

## 7. 可比性、精度与反例

- **轮间可比性不成立（如实登记）**：提交 QPS A 153.5 / B 190.2，B 比 A 高 **+23.9%**；
  两臂 wall（13.03s / 10.52s）与提交 P50（620.6 / 508.6ms）也不同。
  TASK-145 未预注册 QPS 门槛（±15% 是 TASK-144 的规则），但按「仅凭两轮不能判可复现收益」的口径，
  **不得**从两臂差异得出「缩短间隔可改善提交侧」的结论。
- **同源段对照（仅供方向判读）**：`pub→consume` P50 两臂接近（A 10616ms / B 9938ms），
  而 `callback→SENT` 差异巨大（A 58008ms / B 19343.5ms）→ 两臂差异**集中在 relay 段**，不在 MQ 段。该读数不用于改默认值。
- **精度**：`callback→SENT`、`consume→callback` 同在 verify 进程内（毫秒级可信）；`pub→consume`、`pub→SENT` 跨进程、未 NTP 校齐（粗判读）；DB `created_at/sent_at` 为**秒精度**。
  关联依赖固定日志 pattern 的正则（无结构化 `recordId` 字段）。**不把不同段的独立 P50 相减/相加/求比值当逐请求时长**。
- **最强反例**：B 臂在创建突发期把批次拆小、把负载摊平，未出现 A 臂那种「满批 100 行 + 6.3s 空档」的脉冲；
  但突发期只投出 142 行，**绝大部分（1868 行）仍靠满批排空**。因此「B 更快」只在**本轮有限突发**下成立，
  **不能证明**长期到达率（本作业观测到 outbox 创建速率 ≈87~96 行/s，远高于 B 的 37 行/s 净投递）下不会持续积压。
- **诊断开启**：两轮均在诊断开启下测得；开启轮**不得**当生产默认关闭状态的收益证据。

## 8. 异常与采集缺口（如实记录）

- 臂 B 的 verify 进程（PID 52284）在排空确认（23:41:37）后、after 资源采样（23:41:47）前**静默退出**：
  `verify-stderr.log` 为空、日志无异常栈/无关闭钩子输出、全仓无 `hs_err_pid*.log`/`replay_pid*`、Windows Application 日志无 java/verify 条目。
  **无 JVM 崩溃证据，亦无法确证外部终止原因**；两臂启动方式不同（A=bash nohup 全程存活；B=PowerShell Start-Process 约 107s 后消失）。
  详见 `docs/perf/data/raw/task145-anomaly-verify-process-exit.md`。两轮预算已用尽，未补跑。
- 未覆盖：臂 B 的 after JVM/GC/内存/线程读数；MQ broker 读数（两臂）；多实例锁竞争；`maxRetry<=0`、非法 `retry_count`、大规模耗尽行扫描成本；
  榜单消费 / `SENT→榜单` 段（leaderboard 未启动）；R5 质量 / 离路率 / mapmatch CPU（PostGIS+mapmatch 未启动）；`--it` / `--mode=online` / CI；500/1000 并发档（禁止）。

## 9. 退出码与本地门槛

- **离线测试（唯一入口）**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` → **rc=0**，
  `Tests run: 104, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（`RelayDiagnosticsTest` 6、`VerifyOutboxRelayTest` 13）。
- **红证据分类（不混称）**：
  - **行为红**（真实用例红）：把 `RelayDiagnostics.batch()` 的 `if (!enabled) return Optional.empty();` 临时改为恒假，
    离线测试 `Tests run: 104, Failures: 2`, **rc=1**（`relay_diagnosticsDisabled_emitsNoDiagnosticSummary` + `disabledSwitch_isNoOp_andReportsNothing`）；已精确还原。
  - **环境红**（非用例红）：服务在运行占用 `verify-service/target/*.jar`，`maven-clean-plugin` 无法删除导致 clean 失败；停服后重跑即 **rc=0**。
  - **编译/契约红**：本轮未出现（不冒充）。
- 负载：A、B 两轮 wrapper 均写出完整 summary（`ok=2000 / errors=0`）；wrapper 的 exit code 受 Git Bash 经 PowerShell 管道的
  `echo: Bad file descriptor` 假象影响，**以 summary.json + 排空至两类 PENDING=0 为凭**。
- 机器摘要 JSON `ConvertFrom-Json`、`git diff --check`、无参数 `mailbox-contract.sh` 的退出码见 TASK-145 handoff。