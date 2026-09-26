# 当前 HEAD 判定/榜单事件分段归因（TASK-143，只度量不优化）

> 对应变更：`spec/changes/measure-verify-event-stage-lag/`。本文回答：在当前 HEAD
> `997c789aa7420e2b33d3d6c0a23cf6066846cd04`、同一负载（100 并发 × 2000 请求，含 10 预热）
> 下，从「提交 → SUBMITTED 发布 → MQ 消费 → 判定落库 → outbox → SENT → 榜单」这条**事件链**里，
> 时间主要耗在哪一段。**本任务不实施任何优化**，不改服务 Java/SQL/默认配置，不调 relay 周期、
> 批次、消费线程、连接池、索引或 JVM。
> 历史 TASK-138 的 15～18s 与 PENDING 1012 属旧 HEAD/旧栈，**不作为本次实测**（见 §7）。

**结论（单一下一步假设，证据等级：直接测得）**：本事件链中**判定事件投递（outbox relay）是
到达榜单端的支配段**——同 run 配对的 `callback→SENT` P50 **68.8s**（占 `pub→SENT` P50 80.4s 的
绝大多数），而 relay 实测净投递速率仅 **≈13.4 行/s**（2010 行 / ≈150s），远低于本轮 outbox 创建
速率 **≈59 行/s**（2010 行 / 34s）。MQ 消费段（`pub→consume` P50 12.5s）是**第二因素**，其形态是
**消费者积压**而非网络往返；判定计算+落库+回调段（`consume→callback` P50 327.5ms）相对最小。
榜单消费与 R5 质量**本轮未覆盖**（leaderboard 未启用、mapmatch/PostGIS 未启动），故不声称完整榜单链路。

---

## 1. 环境快照与安全门槛

| 项 | 值 |
| --- | --- |
| HEAD | `997c789aa7420e2b33d3d6c0a23cf6066846cd04`（开工基线，与任务书一致） |
| 服务栈 | `start-services` 启动 gateway:8080 / user:8081 / record:8082 / verify:8083 四服务（宿主机进程，共享宿主时钟）；8081/8082/8083 `/actuator/health` = 200，gateway 健康经 `/actuator/health` 200 |
| 未启动 | **leaderboard-service、mapmatch-service 未在栈内**；PostGIS 容器 `Exited(255)` 已 4 天 → 榜单段与 R5 真实链路**未覆盖** |
| 中间件 | MySQL 8.0.46（容器 `sport-verify-mysql`，宿主 3307，数据 3.0G）/ RocketMQ 5.2.0 namesrv+broker / Redis 7.2-alpine / Nacos 2.3.2，四容器均 `healthy` |
| 磁盘 | D: 空闲 **220.8 GB**（远大于本轮 ≈2010×300 点增长） |
| 时钟/时区 | 服务为宿主进程，`%d{...SSS}` 与 `created_at` 同源宿主墙钟；MySQL 会话时区 CST（= 宿主），Redis/RocketMQ 容器报 UTC 但**绝对时刻一致**；DB 时间戳 `created_at/sent_at` 为 **DATETIME 秒精度** |
| 生效参数 | `verify.outbox.batch-size=100`、`relay-interval-ms=5000`、`max-retry=16`（实测生效值，见 §3）；消费线程 32/40、单批 8 |
| 服务 jar | 均以本轮 `package`/既有产物冻结；verify jar sha256 前 16 位 `36768EA21359DB33`（环境指纹 `docs/perf/data/raw/task143-env-fingerprint.txt`，gitignore） |

安全门槛：MySQL/RocketMQ/Redis 健康、磁盘充足、历史 outbox 无可投递积压、四服务可安全运行
→ **满足**，允许按 spec 的「仅四服务局部覆盖」跑一轮；因 leaderboard/R5 缺席，结论限定在事件链的
**已覆盖段**，不编造榜单端到端或 R5 质量指标。

## 2. 阶段边界、关联键与时间精度

同一 run 的样本关联键：`record.request_id = runId + "-" + seq`；`recordId` 关联 record/verify 两侧日志；
verify 消费日志给出 `eventId ↔ recordId`；outbox `payload.recordId` 反查 record。

| 段 | 起点事件 | 终点事件 | 关联键 | 时间来源/精度 | 跨进程 |
| --- | --- | --- | --- | --- | --- |
| 提交 | 客户端请求 | HTTP 响应 | `runId+seq` | raw.csv，ms | 客户端 |
| 发布 | 落库/发事件 | `RecordEventProducer` 发布日志 | recordId | record.log，ms | record 进程 |
| MQ 到达 | 发布日志 | `VerifyEventConsumer` 消费日志 | recordId | record.log ↔ verify.log，ms | **跨进程** |
| 判定 | 消费日志 | `状态回调成功` 日志（判定+落库+回调之后） | recordId | verify.log，ms | 同进程 |
| 投递 | outbox 行 `created_at` | relay `SENT` 日志 / 行 `sent_at` | outbox id / eventId / recordId | verify.log ms；DB 秒 | 同进程（relay） |
| 榜单 | `SENT` | 榜单消费/贡献 | eventId | **无观测** | **未覆盖** |

计时与失败路径核对：relay 为 `@Scheduled(fixedDelay=5000)` + Redisson 锁 `verify:outbox:relay`
单轮取批 ≤100 行、逐行 `syncSend`；发送成功标 `SENT`，失败 `retry_count+1`，`retry_count>=16`
不再取批（TASK-142）。失败/重投会让同一 `recordId` 出现多条消费日志——本轮用**首条消费**作起点、
**额外条数**单独计重复（§4）。

**精度声明**：`pub→consume`、`pub→SENT` 为**跨进程**比对，依赖宿主共享时钟且未另做 NTP 校齐，
只作粗粒度判读（这两段数值在 10s 量级，ms 抖动不影响结论）；`consume→callback`、`callback→SENT`
同在 verify.log **同进程**，ms 可作毫秒级细分。**不同段的独立 P50 不相互加减**当作单请求时长。

## 3. 单轮负载（唯一一轮，失败也计入）

`bash scripts/perf/run-perf.sh load 100 2000 task143-stage`，**退出码 0**，仅此一次，未补跑。
原始数据：`docs/perf/data/raw/task143-stage-c100-{summary.json,raw.csv}`（gitignore，未覆盖历史 raw，未清库）。

| 指标 | 实测 |
| --- | --- |
| 成功/限流/错误 | **2000 / 0（429）/ 0**，错误率 0.00%，状态码全 200 |
| wall / QPS | 15.162s / **131.91** |
| 延迟 | P50 **704.89ms** / P95 **1141.60ms** / P99 **1452.75ms** / MAX 1836.34ms |

同 run 落库 cohort：main `runId=lt1790422566459`（2000 条，id 40433..42432），预热 `...w`（10 条，id 40423..40432）。
2000 条 cohort 记录**全部终态 `PASSED`(2)**，无滞留 SUBMITTED/VERIFYING。

## 4. 分段实测（同一 run 可配对样本）

配对样本 n=2000（cohort 主运行）；失败=0（全部成功落终态、无 relay 失败、无 `retry_count>0` 行）；
重复=**1**（`recordId 40533` 出现 1 条额外消费日志，Mo 重投）；缺失=0（每段 2000 条全部配对成功）。

| 段 | n | 精度 | P50 | P95 | P99 | MAX |
| --- | --- | --- | --- | --- | --- | --- |
| `pub→consume`（发布→消费开始） | 2000 | ms（跨进程） | **12466.5** | 18277 | 19064.1 | 19589 |
| `consume→callback`（判定+落库+回调） | 2000 | ms（同进程） | **327.5** | 2854.8 | 4145 | 23874 |
| `callback→SENT`（等 relay 投递） | 2000 | ms（同进程） | **68805.5** | 112817.2 | 117480 | 117588 |
| `consume→SENT` | 2000 | ms | 68930.5 | 113291 | 117973.1 | 141277 |
| `pub→SENT` | 2000 | ms（跨进程） | **80376** | 131414.1 | 136940 | 150363 |

判读（逐段独立，不相减）：

- `pub→consume` P50 **12.5s**：形态是**消费者积压**——将发布时刻与消费时刻对照可见，早期记录
  近乎即时（如 40433：发布 19:36:09.871 → 消费 19:36:09.922，51ms），**后期记录**消费显著滞后
  （如 42432：发布 19:36:24.571 → 消费 19:36:43.629，19.06s）。2000 条在约 35s 内发布，
  消费被拉长到 19:36:43 才追平；该段**混含 MQ 网络投递 + 消费者排队**，**不拆成纯 MQ 延迟**。
- `consume→callback` P50 **327.5ms**、P95 **2854.8ms**：单条判定计算 + verify 降落库 + Feign 回调；
  P95 抬升与 R5 熔断降级（mapmatch 不可用、read-timeout 3s）同源，**不据此指认纯 SQL 或纯 HTTP 单项**。
- `callback→SENT` P50 **68.8s**：判定完成到事件被 relay 投出的等待，是本链**支配段**。relay 单轮
  ≤100 行 / 5s（上限 20 行/s），本轮实测净速率 ≈13.4 行/s（2010 行 / ≈150s，见 §5），
  低于 outbox 创建速率 ≈59 行/s → 排队增长、SENT 被拖到分钟级。
- `consume→SENT` ≈ `consume→callback` + 等待 relay 之和的形态量级（**非逐样本相加**，仅形态对照）；
  `pub→SENT` 为跨进程端到端观测，量级 80s，与上述两段量级一致。

## 5. outbox 分账（maxRetry 实际生效值 = 16）

口径：用当前生效 `max-retry=16` 把 `status='PENDING'` 切成**可投递**（`retry_count<16`）与
**重试耗尽待人工**（`retry_count>=16`），与 `SENT` 分组；单列 count 与最老年龄。**不把 PENDING
总数当可自然排空的积压**。

| 时点 | 可投递 PENDING | 耗尽待人工 PENDING | SENT |
| --- | --- | --- | --- |
| 负载前 | **0** | **0** | 20100 |
| 负载后 | **0** | **0** | 22110（= 20100 历史 + 2010 本轮） |

- 全表 `retry_count>0` 行数 = **0** → 本轮无任何重试；无历史耗尽行混入，两类 PENDING 最老年龄均 **N/A（count=0）**。
- 本轮 2010 行：`created_at` 19:36:10~19:36:44，`sent_at` 19:36:11~19:38:41，**最老 `created→sent` = 117s**（DB 秒精度）。
- relay 排空曲线（按 10s 桶）：19:36 起每 10s 投出 ~100–200 行，至 19:38:41 排空 → **净速率 ≈13.4 行/s**，
  与「批 100 / 周期 5s」理论上限 20 行/s 的差距来自逐行 `syncSend` 与自身处理开销。
- 结论：**本轮不存在耗尽待人工积压**，故「PENDING 总数」在此环境即 0，不涉及「把总 PENDING 当可投递」的失真；
  分账口径已建立，供后续出现耗尽行时直接复用。

## 6. R5 与榜单

- **R5（mapmatch）未参与**：PostGIS/mapmatch 未启动，2010 条判定全部走「匹配服务熔断降级不命中」
  （`cause=mapmatch 不可用`），判定由 R1-R4 产出 → **R5 质量/离路率本轮未覆盖**。
- **榜单未覆盖**：leaderboard-service 不在栈内，无 eventId→榜单消费/贡献观测 → 榜单端到端与
  「SENT→榜单处理」段**未覆盖**，本文不声称榜单链路已通。

## 7. 旧环境数字（不得当本 HEAD 实测）

TASK-138（HEAD `58cd104`、旧栈、R5 全降级）的「提交→判定落库 15～18s」与 outbox「PENDING 1012 /
SENT 998」来自**旧 HEAD/旧消费参数/旧环境**，本文**不引用为当前值**，也不据此推断当时存在耗尽行堵头。
本 HEAD 的独立快照是 §3/§4 的数字（提交 P50 704.89ms、事件链分段见 §4）。

## 8. 下一步假设（一句话，本任务不实施）

若下一步只动**一段**：先给 `VerifyOutboxRelay` 的投递吞吐建立同负载基线（单轮净投递行数/耗时、
逐行 `syncSend` 与批内并发/批大小变量的对照），验证「提高 relay 投递吞吐可缩短 `callback→SENT`」
这一**唯一**假设——**不在此作业**调整 relay 周期、批次、消费线程、池、SQL、索引或 JVM。
`pub→consume` 的消费者积压为**第二因素**，留待该假设验证后另立判别。

## 9. 未覆盖项（如实记账）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 榜单消费 / SENT→榜单段 | **未覆盖** | leaderboard-service 未启动，无 eventId→贡献观测 |
| R5 质量 / 离路率 / mapmatch CPU | **未覆盖** | 未启动 mapmatch+PostGIS，R5 全降级 |
| 跨进程时钟校齐 | 未覆盖 | 用宿主共享时钟，未做 NTP/父子时钟校准；跨进程段只作粗粒度判读 |
| 毫秒级 DB 细分 | 不可得 | `created_at/sent_at` 为 DATETIME 秒精度 |
| 失败/重试路径实测 | 未覆盖 | 本轮零失败、零重试，未注入 MQ/DB 故障 |
| 500/1000 并发档 | 未覆盖 | 任务限定 c100×2000，禁止改档 |
| `--it` / `--mode=online` / CI | 未覆盖 | 本轮为只读度量，未改业务代码，无依赖来源冲突需仲裁 |

---

*产物：`docs/perf/data/attr-verify-event-stage-lag.json`（机器可读摘要）；原始数据
`docs/perf/data/raw/task143-stage-c100-*` 与 `task143-*`（gitignore，未覆盖历史 raw，未清库）。
本任务未修改任何业务 Java、SQL、服务 yml/properties、Mapper、过滤器，未调 JVM，未加索引，
未改连接池、relay 周期/批次、消费线程或 MQ 参数，未改 `LoadTest` 请求体/负载行为。*
