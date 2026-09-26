# TASK-144 回传：verify outbox relay 调度间隔单因素对照（fixedDelay A=5000ms / B=500ms，A-B-B-A）

## 回传概要

- **开工 HEAD**：`8268aac8e7b2d1e542c04ddf526dbf834f87e0ad`（与任务书一致）。既有脏项
  archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 及未跟踪
  `spec/changes/update-verify-outbox-relay-delay/` 未触碰；未 stash、未 add -A。
- **提交**：两笔本地提交——规范三件套 + 新报告 + 机器摘要 + TASK-143 三处文字订正为业务提交
  （`perf(verify): outbox relay 调度间隔 A-B-B-A 对照报告与机器摘要（不改默认值）`，7 文件）；
  台账两件套 + PLAN + 总览为收口提交（`docs(mailbox): TASK-144 提交绑定与验收记录`，4 文件；
  台账提交哈希由任务回传承载，台账无法承载自身提交哈希）。未 push、未建 PR。
- **结论**：**未定（不推荐），不改运行默认值**。四轮负载全部成功、可靠投递语义无回归，但
  **可比性门槛不成立**：A1 提交 QPS 相对四轮中位数偏差 **−15.65%（超 ±15%）**，且 A1 混入
  **2 条消费失败/重投**（`record-service` `listPoints` 读超时 → `RecordApiFallback`）。
  按预注册规则记未定，**不写入** `verify.outbox.relay-interval-ms=500`，不叠加其他参数。
- **门槛读数（仅登记，不据以决策）**：两轮 B 的 `callback→SENT` P50（19782/18306ms）比两轮 A
  中较好者（A1 56769ms）低 65%～68%，P95（32009.1/31206.6ms）不劣于 A 较好者（108068.2ms）；
  **数值上满足改善门槛，但可比性不成立 → 不改默认值。**

## 覆盖与环境（安全门槛满足 → 跑满四轮）

- `scripts/perf/run-perf.sh start-services` 启动 gateway/user/record/verify，8081/8082/8083
  `/actuator/health` 200；**leaderboard/mapmatch 不在栈内**，PostGIS 容器 `Exited(255)`
  → 榜单段与真实 R5 未覆盖（四服务局部口径）。
- MySQL 8.0.46（宿主 3307）/ RocketMQ 5.2.0 / Redis 7.2 / Nacos 2.3.2 四容器 healthy；
  D: 空闲 **220.7GB**；每轮前可投递 PENDING=0。
- **同一 jar 全程复用**：verify `sha256=36768EA21359DB3369C7589A90906FD8799EFA6916C2D0F99718B3B083397C65`
  （与 TASK-143 同一文件），`jarSwapDuringRounds=NONE`。
- **实际生效配置**：`application.yml` 无 `verify.outbox.*`；Nacos `verify-service.yml`
  `config data not exist` → `@Value` 默认 `batch-size=100`、`relay-interval-ms=5000`、
  `relay-initial-delay-ms=10000`、`max-retry=16`；消费端四轮一致 `32/40`、单批 8、pull 0。
  **唯一改动因素 = relay-interval-ms**（A 走默认 5000；B 启动追加 `--verify.outbox.relay-interval-ms=500`）。
- 环境指纹：`docs/perf/data/raw/task144-env-fingerprint.txt`（gitignore）。

## TASK-143 独立复算（原始文件未修改）

用 `docs/perf/data/raw/task143-stage-samples.csv` 复算：SENT 相邻间距 >1s 的长空档 **20 个**、
中位 **5025ms**（5017～5550ms）；**18 个**完整百条批首末 SENT 跨度中位 **1327.5ms**；净投递
≈ **13.83 行/s**（2000/144.6s，上限 20 行/s）。与原报告数字一致；只作诊断，不据此断定单轮 CPU/纯 MQ/SQL 耗时。

## 四轮受控负载（同 jar，c100×2000，A-B-B-A）

每轮 = main 2000 + 内置预热 10 = 2010 条，同 run 配对 100%（`outboxLinked=2010`）；每轮后等排空。

| 轮 | 间隔 | 成功/限流/错误 | wall(s) | 提交QPS | 提交P50/P95/P99(ms) | callback→SENT P50/P95/P99/MAX(ms) | relay净速率 | longGap>1s | 消费失败/重复 | relay失败/耗尽 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 5000 | 2000/0/0 | 15.278 | 130.91 | 663.50/1328.50/1848.58 | 56769/108068.2/113600.1/113789 | 15.20 | 20 个，中位 5023ms | **2 失败 + 2 重复** | 0/0 |
| B1 | 500 | 2000/0/0 | 12.784 | 156.44 | 611.91/877.80/1086.12 | 19782/32009.1/33194.9/33462 | 35.59 | 2 个 | 0 | 0/0 |
| B2 | 500 | 2000/0/0 | 12.462 | 160.49 | 598.07/770.04/1123.50 | 18306/31206.6/32452.3/32610 | 37.25 | 1 个 | 0 | 0/0 |
| A2 | 5000 | 2000/0/0 | 12.990 | 153.97 | 614.40/963.16/1193.96 | 59317/112228.5/112942.9/118188 | 14.73 | 21 个，中位 5022ms | 0 | 0/0 |

- id 区间：A1 rec 42433..44442/ob 22111..24120；B1 44443..46452/24121..26130；
  B2 46453..48462/26131..28140；A2 48463..50472/28141..30150。四轮均排空至 PENDING=0。
- 10s 创建形态（outbox `created_at` 秒精度，以各轮首个 created 为原点）：A1 116/1455/439、
  B1 99/1084/827、B2 103/1331/576、A2 102/1372/536。

## 可比性判定 → 未定

- 四轮 QPS 中位 = (153.97+156.44)/2 = **155.205**；偏差 A1 **−15.65%（超 ±15%）**、
  B1 +0.80%、B2 +3.41%、A2 −0.80%。
- 创建形态：A 两轮接近，B1 第三桶 827 明显高于 A 轮（439~536）与 B2（576）→ 不完全可比。
- A1 有 2 条消费失败/重投（`listPoints:42452 / 42435` 读超时 → RECONSUME_LATER，26s 后重投成功）；
  B1/B2/A2 该错误计数 0。
- → 按预注册规则**记未定、不凑收益**。A1 的 −15.65% 与重投很可能为首轮冷启 + 瞬时读超时，
  但规则不允许事后放宽门槛。

## relay 间隔生效佐证

- A 轮：SENT 长空档（>1s）中位 ≈ **5022~5023ms**（≈5000），完整百条批跨度中位 1234/1266ms，
  净速率 **15.20/14.73 行/s**。
- B 轮：SENT 相邻间距中位 14ms、>1s 空档仅 1~2 个，净速率 **35.59/37.25 行/s**。
- 两臂差异巨大且方向一致 → `relay-interval-ms` 确为实际生效的单一变量。

## outbox 分账与可靠投递语义

- 每轮负载前 `可投递 PENDING=0 / 耗尽待人工 PENDING=0`；每轮排空后两类均 =0；全表
  `retry_count>0` 本作业期间为 0。**不把 PENDING 总数当可自然排空的积压**。
- 语义无回归：relay 四轮 `relayFailLines=0`、`relayExhaustedLines=0`；eventId 复用、每轮 SENT
  精确 +2010、失败递增语义未触发。消费失败仅 A1 2 条（非 relay 失败，重投成功）。

## 精度与反例声明

- `callback→SENT`/`consume→callback` 同进程可毫秒级细分；`pub→consume`/`pub→SENT` 跨进程未 NTP
  校齐，只作粗判读；DB 为**秒精度**。**不把独立 P50 相减/相加/求比值当逐请求占比或单请求时长**。
- 日志为固定 pattern（无结构化 `recordId` 字段），关联依赖正则；轮间非同质（A1 有失败/重投）。

## TASK-143 文字口径订正（不改原始数字）

1. `docs/perf/归因-事件分段-HEAD.md` + `docs/perf/data/attr-verify-event-stage-lag.json`：
   `SENT` 不是榜单消费完成，结论收窄为「判定完成 → outbox 标记 SENT 前」。
2. 明确两个**独立**阶段 P50 的比值或大小关系**不是逐请求占比**。
3. 净投递速率差的**内部构成（批内逐行发送 / DB 写入 / 锁与自身处理）尚未分离**，不再单一归因「逐行 syncSend」。

## 实际改动清单

业务提交（7 文件）：

- spec/changes/update-verify-outbox-relay-delay/proposal.md（开工前既有未跟踪，随本变更提交，内容未改）
- spec/changes/update-verify-outbox-relay-delay/specs/sport-record-verify/spec-delta.md（同上）
- spec/changes/update-verify-outbox-relay-delay/tasks.json（同上，本次按事实勾选；组 3 第 2 步未满足条件保持未勾）
- docs/perf/复测-outbox-relay-调度间隔.md（新）
- docs/perf/data/exp-outbox-relay-interval.json（新）
- docs/perf/归因-事件分段-HEAD.md（改：仅 TASK-143 三处文字口径，数字未改）
- docs/perf/data/attr-verify-event-stage-lag.json（改：同上）

台账提交（4 文件）：

- work/mailbox/tasks/TASK-144/spec.md（新）
- work/mailbox/tasks/TASK-144/handoff.md（新，本文件）
- work/mailbox/PLAN.md（追加验收记录）
- work/mailbox/后端优化机会总览-2026-09-26.md（更新 P2 节与仓库状态）

## 本地门槛与退出码

- 决策为「不改默认值」→ 未改任何 Java/YAML/SQL，无编译/测试对象；不跑 Maven（唯一入口不适用）。
- 负载：A1 wrapper **exit 1**（Git Bash 经 PowerShell pipe 的 `echo: Bad file descriptor` 假象，
  load 本身产完整 summary `ok=2000/errors=0`）；B1/B2/A2 **exit 0**。四轮以 `summary.json
  ok=2000 errors=0` + 排空至 `PENDING=0` 为凭。
- 机器摘要 JSON `ConvertFrom-Json`：**rc=0**。
- `git diff --check`：**rc=0**（仅 autocrlf LF→CRLF 提示）。
- `bash scripts/verify/mailbox-contract.sh`（无参数）：收口提交后 **rc=0**。

## 未覆盖/缺口

- **CPU/GC/MQ broker/DB 资源指标未逐轮采样 = 未知**（未接监控采集）。
- 榜单消费 / `SENT→榜单` 段（leaderboard 未启动）；R5 质量/离路率/mapmatch CPU（PostGIS+mapmatch 未启动）。
- `--it`、`--mode=online`、CI 未跑；500/1000 并发档（禁止）。
- `maxRetry<=0`、非法 retry_count、大规模耗尽行扫描成本未覆盖。
- 本结论为**四服务局部**口径，不外推完整榜单/R5 端到端。