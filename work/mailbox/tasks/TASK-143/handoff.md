# TASK-143 回传：当前 HEAD 校验判定与榜单事件分段积压归因（只度量不优化）

## 回传概要

- **开工 HEAD**：`997c789aa7420e2b33d3d6c0a23cf6066846cd04`（与任务书一致）。既有脏项
  archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 及未跟踪
  `work/mailbox/后端优化机会总览-2026-09-26.md` 未触碰；未 stash、未 add -A。
- **提交**：两笔本地提交——规范三件套 + 报告 + 机器摘要为
  `125d836ea7990c9656c2e0c3d838899d15ea671c`（`perf(verify): 当前 HEAD 事件分段归因报告与机器摘要（只度量不优化）`，5 文件）；
  台账两件套 + PLAN + 总览为收口提交（`docs(mailbox): TASK-143 提交绑定与验收记录`，4 文件；
  台账提交哈希由任务回传承载，台账无法承载自身提交哈希）。未 push、未建 PR。
- **结论**：本事件链中 **outbox relay 投递是到达榜单端的支配段**（`callback→SENT` P50 **68.8s**），
  **MQ 消费积压为第二因素**（`pub→consume` P50 **12.5s**，形态为消费者排队而非网络往返），
  判定计算+落库+回调段最小（`consume→callback` P50 **327.5ms**）。
- **不声称**：查询/业务延迟提速；纯 MQ / 纯 SQL / 纯 HTTP 单项归因；跨段独立 P50 相加减；
  完整榜单链路或 R5 质量通过。

## 覆盖与环境（安全门槛满足 → 跑一轮）

- 四服务栈 `start-services` 启动 gateway/user/record/verify，8081/8082/8083 `/actuator/health` 200；
  **leaderboard/mapmatch 不在栈内**，PostGIS 容器 `Exited(255)` 4 天 → 榜单段与真实 R5 未覆盖。
- MySQL 8.0.46（`sport-verify-mysql`，宿主 3307，3.0G）/ RocketMQ 5.2.0 / Redis 7.2 / Nacos 2.3.2
  四容器 healthy；磁盘 D: 空闲 **220.8GB**；历史 outbox 无可投递积压 → 门槛满足。
- 时钟/精度：服务为宿主进程共享宿主墙钟；MySQL 会话时区 CST（=宿主）；DB `created_at/sent_at`
  为 **DATETIME 秒精度**；跨进程比对（`pub→consume`、`pub→SENT`）未另做 NTP 校齐，只作粗粒度判读。
- 环境指纹：`docs/perf/data/raw/task143-env-fingerprint.txt`（gitignore）；生效参数
  `batch-size=100`、`relay-interval-ms=5000`、`max-retry=16`、消费线程 32/40、单批 8。

## 单轮负载（唯一一轮，rc=0）

`bash scripts/perf/run-perf.sh load 100 2000 task143-stage`，**退出码 0**，仅此一次，未补跑；
raw 落 `docs/perf/data/raw/task143-stage-c100-{summary.json,raw.csv}`（gitignore，未覆盖历史 raw，未清库）。

| 指标 | 实测 |
| --- | --- |
| 成功/限流/错误 | **2000 / 0（429）/ 0**，错误率 0.00% |
| wall / QPS | 15.162s / **131.91** |
| 延迟 | P50 **704.89ms** / P95 **1141.60ms** / P99 **1452.75ms** / MAX 1836.34ms |

cohort：main `runId=lt1790422566459`（2000 条，id 40433..42432）+ 预热 `...w`（10 条，40423..40432）；
2000 条 cohort **全部终态 PASSED(2)**。

## 分段实测（同一 run 可配对样本；逐段独立，不相加减）

配对 n=2000，失败=0、**重复=1**（`recordId 40533` 多 1 条消费日志，重投）、缺失=0。

| 段 | n | 精度 | P50 | P95 | P99 | MAX |
| --- | --- | --- | --- | --- | --- | --- |
| `pub→consume`（发布→消费开始） | 2000 | ms（跨进程） | 12466.5 | 18277 | 19064.1 | 19589 |
| `consume→callback`（判定+落库+回调） | 2000 | ms（同进程） | 327.5 | 2854.8 | 4145 | 23874 |
| `callback→SENT`（等 relay 投递） | 2000 | ms（同进程） | 68805.5 | 112817.2 | 117480 | 117588 |
| `consume→SENT` | 2000 | ms | 68930.5 | 113291 | 117973.1 | 141277 |
| `pub→SENT`（端到端） | 2000 | ms（跨进程） | 80376 | 131414.1 | 136940 | 150363 |

- `pub→consume` 是 **MQ 网络 + 消费者积压的混合**（早期记录近即时：40433 发布 19:36:09.871→消费 19:36:09.922；
  后期记录 42432 发布 19:36:24.571→消费 19:36:43.629 达 19.06s）→ **不拆成纯 MQ 延迟**。
- `consume→callback` P95 抬升与 R5 熔断降级（mapmatch 不可用、read-timeout 3s）同源 → 不指认纯 SQL/纯 HTTP 单项。
- `callback→SENT` 为支配段；relay 单轮 ≤100 行 / 5s 上限 20 行/s，实测净速率 ≈13.4 行/s。

## outbox 分账（maxRetry 生效值 = 16）

| 时点 | 可投递 PENDING(`retry<16`) | 耗尽待人工 PENDING(`retry>=16`) | SENT |
| --- | --- | --- | --- |
| 负载前 | **0** | **0** | 20100 |
| 负载后 | **0** | **0** | 22110（=20100+2010） |

- 全表 `retry_count>0` 行数 = **0** → 本轮零重试、无历史耗尽行；两类 PENDING 最老年龄均 **N/A（count=0）**。
  **PENDING 总数不当作可自然排空的积压**。
- 本轮 2010 行 `created_at` 19:36:10~19:36:44、`sent_at` 19:36:11~19:38:41，**最老 created→sent = 117s**（秒精度）；
  创建速率 ≈59 行/s vs relay 净速率 ≈13.4 行/s → 排队增长，19:38:41 排空。

## R5 与榜单

- **R5 未参与**：2010 条全部「匹配服务熔断降级不命中」（`cause=mapmatch 不可用`），判定由 R1-R4 产出 → R5 质量未覆盖。
- **榜单未覆盖**：leaderboard 未启动，无 `eventId→贡献` 观测 → 不声称榜单端到端。

## 精度与反例声明

- `consume→callback`、`callback→SENT` 同在 verify.log（同进程）可作毫秒级细分；
  `pub→consume`、`pub→SENT` 跨进程、依赖宿主共享时钟且未校齐，只作 10s 量级粗判读。
- DB 秒精度不支持毫秒级 DB 细分。**不把不同段的独立 P50 相减或相加**当作单请求时长。
- 历史 TASK-138 的 15～18s 与 PENDING 1012 属旧 HEAD/旧栈，**不填入本次实测**。

## 实际改动清单

业务提交：

- spec/changes/measure-verify-event-stage-lag/proposal.md（开工前既有未跟踪，随本变更提交，内容未改）
- spec/changes/measure-verify-event-stage-lag/specs/sport-record-verify/spec-delta.md（同上）
- spec/changes/measure-verify-event-stage-lag/tasks.json（同上，本次按事实勾选）
- docs/perf/归因-事件分段-HEAD.md（新）
- docs/perf/data/attr-verify-event-stage-lag.json（新）

台账提交：

- work/mailbox/tasks/TASK-143/spec.md（新）
- work/mailbox/tasks/TASK-143/handoff.md（新，本文件）
- work/mailbox/PLAN.md（追加验收记录）
- work/mailbox/后端优化机会总览-2026-09-26.md（既有未跟踪指导文件，本次更新 TASK-143 节并首次纳入版本控制）

## 本地门槛与退出码

- **不跑 Maven**：本任务为只读度量，未改任何 Java/SQL/yml/properties 与 `LoadTest`，无编译/测试对象；
  唯一入口 `scripts/verify/mvn-verify.sh` 不适用（无依赖来源冲突需仲裁）。
- 机器摘要 JSON：`ConvertFrom-Json` 校验 **rc=0**（含 head/segments 键）。
- `git diff --check`：**rc=0**（仅 autocrlf LF→CRLF 提示，无空白/冲突标记）。
- `mailbox-contract.sh`：收口提交后无参数运行 **rc=0**（判据 A 两件套齐全；本任务足迹不在工作树 → 判据 B 跳过）。

## 未覆盖/缺口

- 榜单消费 / `SENT→榜单` 段：leaderboard 未启动，无观测。
- R5 质量 / 离路率 / mapmatch CPU：PostGIS+mapmatch 未启动，全降级。
- 跨进程时钟校齐：未做 NTP 校准，只粗判读。
- 毫秒级 DB 细分：`created_at/sent_at` 秒精度不可得。
- 失败/重试路径：本轮零失败零重试，未注入 MQ/DB 故障。
- 500/1000 并发档、`--it`、`--mode=online`、CI：未跑。
- 下一步假设为**唯一一条**（relay 投递吞吐），未在本作业实施任何参数调整。
