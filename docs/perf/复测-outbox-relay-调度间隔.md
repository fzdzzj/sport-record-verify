# 复测：verify outbox relay 调度间隔（fixedDelay A=5000ms / B=500ms，A-B-B-A 四轮）

- **任务**：TASK-144，只检验 `verify.outbox.relay-interval-ms` 单一因素。
- **开工 HEAD**：`8268aac8e7b2d1e542c04ddf526dbf834f87e0ad`（与任务书一致）。
- **对照基线来源**：TASK-143（HEAD `997c789aa7420e2b33d3d6c0a23cf6066846cd04`）。
- **日期**：2026-09-26。**机器摘要**：`docs/perf/data/exp-outbox-relay-interval.json`。
- **原始数据**（gitignore，未入库）：`docs/perf/data/raw/task144-{A1,B1,B2,A2}-{c100-summary.json,c100-raw.csv,verify.log,record.log,samples.csv,stats.txt}`、环境指纹 `task144-env-fingerprint.txt`、解析脚本 `task144-parse.ps1`。

## 0. 结论摘要

**结论：未定（不推荐），不改运行默认值。** 四轮负载全部成功、可靠投递语义无回归，但**可比性门槛不成立**：第一轮 A1 的提交 QPS 相对四轮中位数偏差 **−15.65%**，**超出预注册的 ±15%**；同一轮还混入 **2 条消费失败/重投**（`record-service` `listPoints` 读超时 → `RecordApiFallback`）。按预注册规则「四轮 QPS 与其中位数偏差超过 ±15% 或创建形态不可比时，结论记为未定，不凑收益」，本作业**不写入** `verify.outbox.relay-interval-ms=500`，也**不叠加**批次、并发发送、SQL、MQ、连接池或 JVM 任何参数。

**如实的原始读数**（不用于改默认值）：两轮 B 的 `callback→SENT` P50（19782 / 18306ms）都比两轮 A 中较好者（A1 56769ms）低 65%～68%，P95（32009.1 / 31206.6ms）也低于 A 较好者（108068.2ms）。**方向与幅度在数值上满足改善门槛**，但因轮间可比性不成立（且 A1 混入失败/重投），该读数**不作为**更改默认配置的依据，也不宣称四服务局部以外的收益。

## 1. 环境与「唯一因素」证明

- 服务栈：`scripts/perf/run-perf.sh start-services` 启动 gateway:8080 / user:8081 / record:8082 / verify:8083，四者 `/actuator/health` 均 200。**leaderboard / mapmatch 不在栈内**，PostGIS 容器 `Exited(255)` → 榜单段与真实 R5 未覆盖。
- 中间件四容器 healthy：MySQL 8.0.46（`sport-verify-mysql`，宿主 3307）、RocketMQ 5.2.0（namesrv+broker）、Redis 7.2、Nacos 2.3.2。磁盘 D: 空闲 **220.7GB**。
- **同一 jar**：四轮全程复用同一个 verify 包 `sha256=36768EA21359DB3369C7589A90906FD8799EFA6916C2D0F99718B3B083397C65`（与 TASK-143 同一文件），`jarSwapDuringRounds=NONE`；record 包 `sha256=197978FCBE52EBFAD36ABA9F76972BDB7EF169F5840CC29AFEDD55A292F1F7BE`。
- **实际生效配置**（非推测）：`verify-service/src/main/resources/application.yml` **无** `verify.outbox.*` 键；Nacos `dataId=verify-service.yml` 返回 `config data not exist` → `@Value` 默认值为准：`batch-size=100`、`relay-interval-ms=5000`、`relay-initial-delay-ms=10000`、`max-retry=16`。
  - `VerifyOutboxRelay` = `@Scheduled(fixedDelayString="${verify.outbox.relay-interval-ms:5000}", initialDelayString="${verify.outbox.relay-initial-delay-ms:10000}")`，Redisson 锁 `verify:outbox:relay` `tryLock(0)`，逐行 `syncSend` 后 `markSent` / 失败 `incrRetry`。**fixedDelay 语义** = 本轮结束后再等待，非 fixedRate。
  - 消费端四轮启动日志一致：`consume-thread 32/40`、`consume-message-batch-max-size=8`、`pull-interval-ms=0`。
- **A/B 唯一差异** = `relay-interval-ms`：A 轮走 `@Value` 默认 5000；B 轮启动时追加 `--verify.outbox.relay-interval-ms=500`。**未改** batch-size、max-retry、消费线程、SQL、MQ、连接池、JVM。
- **每轮冷启对称**：四轮均为「重启 verify → 等 health 200 → 确认可投递 PENDING=0 → 跑负载 → 等本轮排空」，避免上一轮 welfare 残留。

## 2. TASK-143 独立复算（不当作当前基线重复跑次）

用 TASK-143 原始样本 `docs/perf/data/raw/task143-stage-samples.csv`（**未修改**）独立复算：

- 把 SENT 时间排序，以相邻间距 `>1s` 作**诊断性**分批：长空档 **20 个**，中位 **5025ms**（区间 5017～5550ms）。
- **18 个**完整百条批（每批 100 行）的首末 SENT 跨度中位 **1327.5ms**。
- relay 净投递 ≈ **13.83 行/s**（2000 行 / 144.6s，上限 100 行/5s = 20 行/s）。

以上与 TASK-143 报告数字一致，验证了「固定延迟主导批间空档」的观测；但**不能**由此断定整轮 CPU、纯 MQ 往返或 SQL 各自耗时。本次只把「调度间隔」作为**单一候选因素**。

## 3. 四轮受控负载（同一 jar，c100×2000）

`bash scripts/perf/run-perf.sh load 100 2000 task144-<Label>`，A-B-B-A 次序，**至多四轮一次性跑完**，失败轮也占预算。每轮前 `可投递 PENDING=0`。

| 轮 | 间隔(ms) | 成功/限流/错误 | wall(s) | 提交QPS | 提交P50/P95/P99(ms) | callback→SENT P50/P95/P99/MAX(ms) | relay净速率(行/s) | longGap>1s | 10s创建形态 | 消费失败/重复 | relay失败/耗尽 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 5000 | 2000/0/0 | 15.278 | **130.91** | 663.50/1328.50/1848.58 | 56769/108068.2/113600.1/113789 | 15.20 (2010/132.2s) | 20 个，中位 5023ms | 116/1455/439 | **2 失败 + 2 重复** | 0 / 0 |
| B1 | 500 | 2000/0/0 | 12.784 | **156.44** | 611.91/877.80/1086.12 | 19782/32009.1/33194.9/33462 | 35.59 (2010/56.5s) | 2 个（中位 1053ms） | 99/1084/827 | 0 | 0 / 0 |
| B2 | 500 | 2000/0/0 | 12.462 | **160.49** | 598.07/770.04/1123.50 | 18306/31206.6/32452.3/32610 | 37.25 (2010/54.0s) | 1 个（1570ms） | 103/1331/576 | 0 | 0 / 0 |
| A2 | 5000 | 2000/0/0 | 12.990 | **153.97** | 614.40/963.16/1193.96 | 59317/112228.5/112942.9/118188 | 14.73 (2010/136.5s) | 21 个，中位 5022ms | 102/1372/536 | 0 | 0 / 0 |

- 每轮 cohort = main 2000 + 内置预热 10 = 2010 条，同 run `recordId/eventId` 与 outbox **100% 可配对**（`outboxLinked=2010`，每轮）。id 区间：A1 rec 42433..44442 / ob 22111..24120；B1 44443..46452 / 24121..26130；B2 46453..48462 / 26131..28140；A2 48463..50472 / 28141..30150。
- 每轮负载后**等本轮排空**至 `可投递 PENDING=0`、`耗尽 PENDING=0`（A1 ~122s、B1 ~42s、B2 ~41s、A2 ~107s）。
- 「10s创建形态」= 该轮 outbox `created_at`（DB 秒精度）以该轮首个 created 为原点、按 10s 分桶的计数，三段之和 = 2010。
- 「longGap」与「批组」由 SENT 相邻间距 `>1s` 诊断性切分；**B 轮整段几乎无 >1s 空档**，故此切分对 B 轮不构成「批次」含义（B1 3 组 9/1/2000、B2 2 组 9/2001），仅证明其空档消失。

## 4. 可比性判定 → 未定

**提交 QPS 相对四轮中位数**：四轮排序 130.91 / 153.97 / 156.44 / 160.49，中位 = (153.97+156.44)/2 = **155.205**。

| 轮 | QPS | 相对中位偏差 |
| --- | --- | --- |
| A1 | 130.91 | **−15.65%（超 ±15%）** |
| B1 | 156.44 | +0.80% |
| B2 | 160.49 | +3.41% |
| A2 | 153.97 | −0.80% |

**创建形态**：A 两轮 116/1455/439、102/1372/536 较接近；B1 为 99/1084/827（第三桶 827 明显高于 A 轮 ~439~536 与 B2 576），轮间形态**不完全可比**。

**失败/重投混入**：A1 的 verify.log 有 2 条 `RecordApiFallback - record-service 不可用（不可软降级）：op=listPoints:42452 / 42435, cause=feign.RetryableException: Read timed out`，触发 `校验事件消费失败，等待重试` → RECONSUME_LATER，26s 后重投成功；对应 stats 中 `recordId=42452/42435` 各多 1 条消费日志（`extraConsumeLines=1`）。B1/B2/A2 该错误计数为 **0**。

→ **按预注册规则：可比性不成立，结论记为「未定」，不凑收益。** A1 的 −15.65% 与消费重投很可能是「首轮冷启 + 瞬时读超时」所致，但预注册规则不允许用事后解释放宽门槛，故照实记录、不据此改默认值。

**门槛读数（仅登记，不据此决策）**：`callback→SENT` P50 较好 A = A1 56769ms；B1 19782ms（−65.15%）、B2 18306ms（−67.75%），均 ≥20% 改善；P95 B1 32009.1 / B2 31206.6 均 ≤ A1 108068.2（不劣）。**改善方向一致、幅度充足，但可比性不成立 → 不写默认配置。**

## 5. relay 间隔生效佐证（证明唯一因素确实生效）

- **A 轮**：longGap 中位 ≈ **5022~5023ms**（≈5000ms），批组呈「45/41、81/63、100×18/19、84/6」形态，完整百条批 SENT 跨度中位 1234/1266ms，净速率 15.20 / 14.73 行/s（≈ 上限 20 行/s 受 5s 空档稀释）。
- **B 轮**：SENT 相邻间距中位 14ms、>1s 空档仅 1~2 个，净速率 **35.59 / 37.25 行/s**（批 100 行 / 0.5s 延迟的理论 ≈ 很高，实际受创建与发送耗时限制）。
- 两臂净速率与空档形态差异巨大且方向一致，证明 `relay-interval-ms` 确为实际生效的单一变量。

## 6. outbox 分账与可靠投递语义

- 两类 PENDING 全程分离：每轮**负载前可投递 PENDING=0、耗尽待人工 PENDING=0**；每轮**排空后可投递=0、耗尽=0**。全表 `retry_count>0` 在本作业期间为 0。
- 语义无回归：relay 四轮 `relayFailLines=0`、`relayExhaustedLines=0`；eventId 复用（ob id 连续、无重发）、成功标 SENT（每轮 SENT 精确 +2010）、失败递增语义未触发。消费失败仅 A1 2 条（重投成功，非 relay 失败）。
- **PENDING 总数不当作可自然排空的积压**；只用「可投递 / 耗尽」两类分账。

## 7. 精度与反例声明

- `callback→SENT`、`consume→callback` 同在 verify.log（同进程）可作毫秒级细分；`pub→consume`、`pub→SENT` 跨进程依赖宿主共享时钟、未 NTP 校齐，只作粗判读；DB `created_at/sent_at` 为**秒精度**。
- **不把不同段的独立 P50 相减/相加/求比值当作逐请求占比或单请求时长**。
- 日志配置为固定 pattern（无 `%X{recordId}` 结构化字段），关联依赖正则匹配；`recordId/eventId` 由消费日志反取。
- 反例：若本轮限流、到达速率、锁冲突或 MQ/DB 背景负载不同，空档/净速率差异不能机械归因于单一配置；本轮 B 轮无失败，但 A1 有失败/重投，**轮间并非同质**。

## 8. 未覆盖 / 缺口

- **CPU / GC / MQ broker / DB 资源指标未逐轮采样** = 未知（未接监控采集，不用「无异常」代替实测）。
- 榜单消费 / `SENT→榜单` 段（leaderboard 未启动）；R5 质量 / 离路率 / mapmatch CPU（PostGIS+mapmatch 未启动，A 轮全降级）。
- `--it`、`--mode=online`、CI 未跑；500/1000 并发档（禁止）。
- `maxRetry<=0`、非法 retry_count、大规模耗尽行下的扫描成本未覆盖。
- 本报告为**四服务局部**口径，不能外推完整榜单/R5 端到端。

## 9. TASK-143 文字口径订正（不改原始数字）

同步订正 TASK-143 已提交报告与机器摘要的**三处文字口径**（数字保持不变）：

1. `docs/perf/归因-事件分段-HEAD.md`、`docs/perf/data/attr-verify-event-stage-lag.json`：`SENT` **不是榜单消费完成**，相关结论收窄为「判定完成 → outbox 标记 SENT 前」。
2. 明确「两个**独立**阶段 P50 的比值或大小关系**不是逐请求占比**」。
3. 净投递速率差（13.4 行/s vs 创建约 59 行/s）的**内部构成（批内逐行发送 / DB 写入 / 锁与自身处理）尚未分离**，不再单一归因「逐行 `syncSend`」。

## 10. 退出码与门槛

- 负载：A1 wrapper **exit 1**（msys/Git Bash 经 PowerShell pipe 的 `echo: Bad file descriptor` 假象，load 本身产出完整 summary：`ok=2000/errors=0`）；B1/B2/A2 **exit 0**。四轮均以 `summary.json ok=2000 errors=0` + 排空至 `PENDING=0` 为凭。
- 机器摘要 JSON：`ConvertFrom-Json` **rc=0**。
- `git diff --check`：**rc=0**（仅 autocrlf LF→CRLF 提示）。
- `bash scripts/verify/mailbox-contract.sh`（无参数）：**rc=0**。
- 本作业**未改**任何 Java / YAML / SQL（决策为「不改默认值」）→ 无编译/测试对象，未跑 Maven；唯一入口 `scripts/verify/mvn-verify.sh` 不适用。