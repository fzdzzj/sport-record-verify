# TASK-145 回传：拆解 verify outbox relay 批内成本与有限突发积压（默认关闭诊断，A=5000ms / B=500ms 各一轮）

## 回传概要

- **开工 HEAD**：`28d277709d8020f49c6cff018652d1fe7c80975f`（与任务书一致）。既有脏项
  archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 及未跟踪
  `spec/changes/measure-verify-outbox-relay-cost/` 未触碰；未 stash、未 add -A、未清库。
- **提交**：两笔本地提交——诊断代码 + 只读采样脚本 + 报告 + 机器摘要 + 三件套为业务提交；
  TASK-145 两件套 + PLAN + 总览为收口提交（台账提交哈希由任务回传承载）。未 push、未建 PR。
- **结论**：**只测量、不优化**。两轮均成功、可靠投递语义无回归，但**两臂不可比**
  （提交 QPS A 153.45 / B 190.15，+23.9%；wall 与提交 P50 亦不同）。**即便 B 更快，也不改
  运行默认 `relay-interval-ms=5000`，不宣称持续吞吐改善**；两轮不能判可复现收益。
- **本任务首次分离出的核心结论**：relay 锁内占时最大段是**逐行 `markSent`**，不是 `syncSend`。
  满批（rows=100）P50：A `markSent 1046ms` 占锁持有 **79.7%**、`syncSend 246ms` 18.8%；
  B `markSent 1037ms` 占 **71.4%**、`syncSend 324ms` 22.3%。锁内 markSent 在 B 臂已是
  **整周期占比最高段**（1037/~1941 ≈ 53.4%），高于 500ms 固定等待的 25.8%。
- **下一步唯一待证因素**：**逐行 `markSent` 的批内成本构成**。
  口径声明：`syncSend`/`markSent` 均为**混合墙钟**（含序列化/网络/确认与获取连接/客户端等待），
  **不得**称纯 MQ 或纯 SQL。

## 覆盖与环境（安全门槛满足 → 跑满两轮）

- `scripts/perf/run-perf.sh start-services` 启动 gateway:8080 / user:8081 / record:8082 /
  verify:8083，8081/8082/8083 `/actuator/health` 200；**leaderboard / mapmatch 不在栈内**，
  PostGIS 容器未运行 → 榜单段与真实 R5 未覆盖（四服务局部口径）。
- MySQL 8.0.46（宿主 3307）/ RocketMQ 5.2.0 / Redis 7.2 / Nacos 2.3.2 四容器 healthy。
- **同一 jar 复用两臂**：verify `sha256=95CC9207021162B55D6E140B5C4FC5130EBA1EB4F05A6EADADD645224EAA2670`，
  `jarSwapDuringRounds=NONE`（离线 `clean test` 后该 jar 已被 clean 删除，哈希为运行期实测留存）。
- **实际生效配置**：`application.yml` **无** `verify.outbox.*` 键 → `@Value` 默认
  `batch-size=100`、`relay-interval-ms=5000`、`relay-initial-delay-ms=10000`、`max-retry=16`；
  `@Scheduled(fixedDelayString=...)` 为 **fixedDelay 语义**（本轮结束后再等待）。
  **唯一差异 = relay-interval-ms**（A 走默认 5000；B 启动仅追加
  `--verify.outbox.relay-interval-ms=500`）；**两臂同加**诊断开关
  `--verify.outbox.relay-diagnostics-enabled=true`。两臂均 `MYSQL_PORT=3307`。
- 每臂均做**相同**的至多 c10×100 预条件化并排空（`task145-A-pre` / `task145-B-pre`），
  预条件化不计入 cohort；测量前 `可投递 PENDING=0 / 耗尽待人工 PENDING=0`。

## 默认关闭、有界、低基数的周期级诊断

- 开关 `verify.outbox.relay-diagnostics-enabled` **默认 false**；关闭时**不产生批次日志、
  不增加额外 DB/MQ 调用**（单测 `relay_diagnosticsDisabled_emitsNoDiagnosticSummary`、
  `disabledSwitch_isNoOp_andReportsNothing`）。
- 开启时每个**非空批次至多一条**摘要（`rows/success/failed/exhausted/lockWaitMs/selectMs/
  sendMs/markMs/incrRetryMs/lockHoldMs/residualMs/emptyRounds/lockSkips`）；**空轮与锁竞争**
  按 `relay-diagnostics-window-ms`（默认 10000ms）**窗口汇总至多一条**；无 payload/eventId/token。
- **真服务侧有界性佐证**：臂 A 空闲段自 23:33:35 起每 **10s** 恰好一条空轮汇总
  （`emptyRounds=2`/窗口、`lockSkips` 恒 0）。
- 计时用 `System.nanoTime()`；**原有逐行日志、异常、解锁与 eventId 语义未改**。
- 单测：`RelayDiagnosticsTest`（6）+ `VerifyOutboxRelayTest`（13，含成功/失败/空批/锁竞争/开关关闭）。

## 两轮受控负载（同 jar、同诊断开关，c100×2000）

| 臂 | 间隔(ms) | 成功/限流/错误 | wall(s) | 提交QPS | 提交P50/P95/P99/MAX(ms) |
| --- | --- | --- | --- | --- | --- |
| A | 5000（默认） | 2000/0/0 | 13.033 | 153.45 | 620.60/881.75/1189.99/2034.28 |
| B | 500（启动覆盖） | 2000/0/0 | 10.518 | 190.15 | 508.55/620.14/1028.01/1503.30 |

- 同 run 关联：每臂 cohort = main 2000 + 内置预热 10 = **2010**，`outboxLinked=2010`、
  `published=consume=callback=sent=2010`，**覆盖率 100%**，无重复消费、无缺失。
- id 区间：A rec 50583..52592 / ob 30261..32270；B rec 52703..54712 / ob 32381..34390。
  两臂 `relayFailLines=0`、`relayExhaustedLines=0`。

## 周期闭合与批内分解（核心）

| 臂 | 批次类型 | n | lockWait | select | syncSend | markSent | incrRetry | 残差 | 锁持有 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 满批(=100) | 19 | 0 | 5 | 246 | **1046** | 0 | 11 | 1312 |
| A | 短批(<100) | 3 | 1 | 6 | 89 | 378 | 0 | 4 | 476 |
| A | 全部批次 | 22 | 0 | 5 | 245 | 1044.5 | 0 | 11 | 1305.5 |
| B | 满批(=100) | 18 | 0 | 4 | 324 | **1037** | 0 | 76 | 1452 |
| B | 短批(<100) | 13 | 0 | 3 | 62 | 315 | 0 | 18 | 396 |
| B | 全部批次 | 31 | 0 | 3 | 312 | 984 | 0 | 74 | 1389 |

（单位 ms，P50）

- **周期级预算**：A 周期 P50 **6315ms**（MIN 5490/P95 7221/MAX 11122）
  = 5000ms 固定等待（**79.2%**）+ 锁内 1312ms（20.8%）；锁内 `markSent` = 整周期 **16.6%**。
  B 稳态满批段相邻周期中位 **≈1941ms**（全窗口 P50 1894/MIN 578/MAX 6145）
  = 500ms（**25.8%**）+ 锁内 1452ms（74.2%）；锁内 `markSent` = 整周期 **53.4%**。
- **闭合性质**：残差定义为余项，故 `select+send+mark+incrRetry+残差 = 锁持有` 是**恒等式**；
  真正有信息的是**占比**。各段独立取中位数之和与锁持有中位数可差数毫秒（A −4ms），非逐周期求和。
- **口径**：周期级聚合与同 run 事件延迟是两种口径，**不相加、不互补**；占比是「同周期聚合量之比」，
  **不是逐请求占比**。
- **真实批次形态 vs 日志分组**：诊断给出 A 真实批次 `9,64,100×19,37`；B
  `9,2,28,3,12,19,3,10,18,10,14,14,100×17,68`。「SENT 相邻间距 >1s」的旧日志分组启发式在
  B 臂**失效**（合并为 9 / 2001）→ 与 TASK-144 一致：**日志分组 ≠ 真实调度周期**。

## 有限突发积压与时间桶

| 臂 | 可投递 PENDING 峰值 | 耗尽待人工峰值 | 排空完成 | relay 首末 SENT 跨度 | 净投递速率 |
| --- | --- | --- | --- | --- | --- |
| A | **1837** @23:36:23 | 0 | 23:38:19 | 137.8s | 14.58 行/s |
| B | **1668** @23:41:02 | 0 | 23:41:35 | 54.1s | 37.15 行/s |

- **outbox 创建 5s 桶**（DB 秒精度）：A `42/55/219/857/837`（和 2010）；B `43/71/515/907/474`（和 2010）；
  创建速率 A 87.39 行/s、B 95.71 行/s。
- **SENT 5s 桶**：A 共 27 桶、主体 **100/5s**（受「100 行 / 6.3s 周期」上限约束）；
  B 共 12 桶，峰值 ≈250~277/5s（≈50~55 行/s）。
- **分账**：全程 `retry_count>0` = **0**，故「可投递」与「耗尽待人工」两类均可自然排空；
  每臂排空后两类均 = 0。**不把 PENDING 总数当可自然排空的积压**。
- 小口径语句：创建形态为「涓流 + 突发」，**创建桶 ≠ 提交桶**（A 创建跨度 23s、B 21s，均长于提交 wall）。

## 资源读数（可获得项 + 采集缺口）

- **臂 A（同时间轴 before→after）**：GC 年轻代 3→21（+18 次）、暂停合计 0.013→0.082s
  （**+69ms / 166.6s 窗口**）；累计分配 +1.29GB、晋升 +54.9MB；Eden 81.8→12.6MB、Old 58.3→111.9MB；
  `hikaricp_connections` active 0 / idle 10 / max 10 全程未变、`timeout_total` 0；线程 248→216；
  `process_cpu_usage` 为**瞬时 gauge**（0.0508→0.0123），**不可相加**。
- **臂 B**：before 已采集（uptime 52.3s、GC 3/0.018s、累计分配 2.06e8、线程 233、Hikari 0/10）；
  **after = `PROM_SCRAPE_FAILED`（进程已退出）→ 增量记「未知」**。
- **MySQL 全局状态（全局累计，非单轮隔离）**：A 窗口 `Queries +50690 / Com_update +4020 /
  Com_select +20241 / Innodb_rows_read +1032271`；B `+50573 / +4020 / +20200 / +915091`。
  **交叉校验**：两臂 `Com_update` 精确 **+4020 = 2×2010**（2010 次 `markSent` + 2010 次记录状态更新）
  → 每行恰好一次成功标记、无重发。`Innodb_row_lock_waits` 7→7、`Innodb_row_lock_time` 62→62 无增量。
- **锁竞争**：两臂 `lockSkips`/`lockWaitMs` 全程 0 → 单实例零 Redisson 竞争；**多实例未覆盖**。
- **MQ broker 读数不可得**：容器内 `mqadmin: command not found` → 记 `MQ_BROKER_STATUS_UNAVAILABLE`，
  不以「无异常」代替。
- **口径**：采样与计时自身开销未知；本作业为**诊断开启轮**，**不与 TASK-144 计时关闭的数值做因果比较**。

## 可比性、精度与反例

- **可比性不成立（如实登记）**：提交 QPS A 153.45 / B 190.15（**+23.9%**），wall 13.033s / 10.518s，
  提交 P50 620.60 / 508.55ms。TASK-145 **未预注册 QPS 门槛**（±15% 是 TASK-144 的规则），但按
  「仅凭两轮不能判可复现收益」的口径，**不得**据此得出「缩短间隔改善提交侧」的结论。
- **同源段方向判读**：`pub→consume` 两臂接近（A 10616ms / B 9938ms），`callback→SENT` 差异巨大
  （A 58008ms / B 19343.5ms）→ 差异**集中在 relay 段**，不在 MQ 段；该读数不用于改默认值。
- **精度**：`callback→SENT`、`consume→callback` 同进程毫秒级可信；`pub→consume`、`pub→SENT`
  跨进程**未 NTP 校齐**（粗判读）；DB `created_at/sent_at` 为**秒精度**。关联依赖固定 pattern 正则。
  **不把不同段的独立 P50 相减/相加/求比值当逐请求时长**。
- **最强反例**：B 在创建突发期把批次拆小、负载摊平，未出现 A 那种「满批 100 + 6.3s 空档」脉冲；
  但突发期只投出 142 行，**绝大部分（1868 行）仍靠满批排空**。故「B 更快」只在**本轮有限突发**成立，
  **不能证明**长期到达率（观测创建速率 ≈87~96 行/s，远高于 B 的 37 行/s 净投递）下不会持续积压。
- **诊断开启**：两轮均在诊断开启下测得；**不得**当生产默认关闭状态的收益证据。

## 异常与采集缺口（如实记录）

- 臂 B 的 verify 进程（PID 52284）在排空确认（23:41:37）后、after 资源采样（23:41:47）前**静默退出**：
  `verify-stderr.log` 为空、日志无异常栈/无关闭钩子输出、全仓无 `hs_err_pid*.log`/`replay_pid*`、
  Windows Application 日志无 java/verify 条目 → **无 JVM 崩溃证据，亦无法确证外部终止原因**。
  两臂启动方式不同（A=bash nohup 全程存活；B=PowerShell Start-Process 约 107s 后消失）。
  详见 `docs/perf/data/raw/task145-anomaly-verify-process-exit.md`。**两轮预算已用尽，未补跑**。

## 实际改动清单

业务提交（7 文件）：

- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java（改）
- verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java（新）
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java（改）
- verify-service/src/test/java/com/sportverify/verify/mq/RelayDiagnosticsTest.java（新）
- scripts/perf/relay-round-sampler.sh（新，只读资源采样）
- docs/perf/拆解-outbox-relay-批内成本.md（新）
- docs/perf/data/exp-outbox-relay-batch-cost.json（新）

台账提交（4 文件）：

- work/mailbox/tasks/TASK-145/spec.md（新）
- work/mailbox/tasks/TASK-145/handoff.md（新，本文件）
- work/mailbox/PLAN.md（追加验收记录）
- work/mailbox/后端优化机会总览-2026-09-26.md（更新 P2 节与仓库状态）

另：`spec/changes/measure-verify-outbox-relay-cost/` 三件套（开工前既有未跟踪）随本变更按事实勾选提交。

## 本地门槛与退出码

- **离线测试（唯一入口）**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`
  → **rc=0**，`Tests run: 104, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
  （`RelayDiagnosticsTest` 6、`VerifyOutboxRelayTest` 13）。
- **红证据分类（不混称）**：
  - **行为红**：把 `RelayDiagnostics.batch()` 的 `if (!enabled) return Optional.empty();` 临时改恒假 →
    `Tests run: 104, Failures: 2`、**rc=1**（`relay_diagnosticsDisabled_emitsNoDiagnosticSummary` +
    `disabledSwitch_isNoOp_andReportsNothing`）；已精确还原。
  - **环境红**：服务运行占用 `verify-service/target/*.jar` → `maven-clean-plugin` 无法删除导致 clean 失败；
    停服后重跑即 **rc=0**。
  - **编译/契约红**：本轮未出现（不冒充）。
- 负载：两轮 wrapper 均产出完整 summary（`ok=2000 / errors=0`）；wrapper exit code 受
  Git Bash 经 PowerShell 管道的 `echo: Bad file descriptor` 假象影响，**以 summary.json +
  排空至两类 PENDING=0 为凭**。
- 机器摘要 JSON `ConvertFrom-Json`：**rc=0**。
- `git diff --check`：**rc=0**。
- 无参数 `bash scripts/verify/mailbox-contract.sh`：收口提交后 **rc=0**。

## 未覆盖/缺口

- 臂 B 的 after JVM/GC/内存/线程读数（进程静默退出）= **未知**。
- MQ broker 读数（两臂；`mqadmin` 不可用）；多实例锁竞争（单实例）。
- `maxRetry<=0`、非法 `retry_count`、大规模耗尽行扫描成本。
- 榜单消费 / `SENT→榜单` 段（leaderboard 未启动）；R5 质量/离路率/mapmatch CPU
  （PostGIS + mapmatch 未启动）。
- `--it`、`--mode=online`、CI 未跑；500/1000 并发档（禁止）。
- 本结论为**四服务局部**口径，不外推完整榜单/R5 端到端；**不改默认值、不宣称持续吞吐改善**。