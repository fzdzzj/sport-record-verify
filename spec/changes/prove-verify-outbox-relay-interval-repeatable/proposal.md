# 提案：复测 relay 调度间隔（预热吸收冷启动的可比性口径）并条件式落地默认值（TASK-162）

## Why

TASK-144 已实测 `verify.outbox.relay-interval-ms` 5000→500 的 `callback→SENT` 延迟改善门**已满足**（两轮 B 较 A 较好者 −65.15% / −67.75%、P95 不劣），但**预注册可比性门失败**：A1 提交 QPS 130.91 相对四轮中位 155.205 偏 **−15.65%**（限 ±15%），且 A1 混入 2 次消费失败 + 2 次重投 ⇒ 裁决 **UNDETERMINED**、未改默认值，`update-verify-outbox-relay-delay` 的 tasks.json 第 3 项至今 `completed=false / passes=false`。

生产侧一手代码事实（指导侧亲读，Level A）：`VerifyOutboxRelay.java` 第 126–127 行 `@Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}", initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")`；第 157 行每 tick 只调用一次 `selectPendingBatch`、**无排空循环**；`application.yml` 里没有任何 `verify.outbox` 键 ⇒ 生效值即默认，稳态投递上限 = 100 行 / 5 s = **20 行/s**。

**唯一问题：把「首轮冷启动」这个可比性缺陷修掉后重跑单因素 A-B-B-A，收益是否可重复？** 修法是把冷启动吸收进丢弃预热轮：W0（全局，起栈后首轮之前）+ W_cell（每次 verify-service 重启后）全部丢弃但不进统计、不进中位数。若可重复且全部门槛通过，则把**这一项**默认值落地到 verify-service 的 classpath YAML 并加绑定测试。本任务**不**回答并发（`relay-send-concurrency` 全程保持 1）、批次大小、池容量、SQL/索引、消费者线程、MQ 参数；TASK-144 停止条件「任何结果均不叠加调批次、并发发送、连接池、JVM、SQL 或索引作为补救」被继承。

## What Changes

1. 冻结起点 HEAD `121273d6cf46fd1e8968d4817f8134d71edffda0`。四服务局部栈：容器只 `docker start` 五个（mysql / namesrv / broker / redis / nacos；**不 recreate / 不改配置 / 不删卷**），`sport-verify-postgis` 不起（leaderboard/mapmatch 本轮不覆盖，与 TASK-144 同口径）；`mvn-verify.sh --mode=offline package` rc=0 产出四个 jar 并记 sha256，**A/B 计数轮之间不重建任何 jar**。
2. 唯一变量 = `verify.outbox.relay-interval-ms`：**A = 5000**（无注入，走 `@Scheduled` 默认）、**B = 500**（命令行注入 `--verify.outbox.relay-interval-ms=500`）。全程不动：`relay-send-concurrency`=1、`batch-size`=100、`max-retry`=16、消费线程 32/40、Hikari（无 `maximum-pool-size` ⇒ 默认 10）、JVM、MQ、Nacos。
3. 轮次协议：预热轮 W0（全局首轮前）与 W_cell（每次重启后）**全部丢弃**但逐轮留档；计数轮严格顺序 A1→B1→B2→A2（替换轮仅追加尾部，最多 2 个）。每轮 6 步：前置（四端口健康、可投递/耗尽 PENDING=0、id 下界、磁盘、before 采样）→ 负载 `load 100 2000`（warmup 10 / timeout 60 / user-total 16）→ 排空到 0（A 档 400 s / B 档 200 s 超时即无效）→ id 上界 + after 采样 + **立即**复制 verify.log/record.log（重启会截断）→ `task144-parse.ps1` 配对（**不改脚本**）→ 记账。预算硬上限 **6 计数轮 + 4 预热轮**，失败轮照占预算。
4. 逐轮预注册有效性门 **V1–V7**（V1 `ok=2000/errors=0/limited429=0`；V2 零 `RECONSUME_LATER` / 零 `RecordApiFallback`；V3 relay 失败行/耗尽行 = 0；V4 轮前/轮后 PENDING 闭合；V5 配对完整 `cohort.records == cohort.outboxLinked == callbackToSent.n`；V6 提交 QPS 相对全部有效计数轮中位数偏差 ±15%；V7 创建形态可比）。**V6 是自指定义**（有效集依赖中位数、中位数依赖有效集）：判别作业枚举全部可辩护读法（预注册基准池 / 全量一次性中位 / 最大自洽不动点 / 到达序滚动池）逐读法给有效轮集合，**不单方选择最宽松读法**；V7 只交原始三桶数字，通过/失败口径由指导侧复核。
5. 预注册裁决门（一字未放宽）：**改善门** = 两轮有效 B 的 P50 **均**较两轮有效 A 中较好者下降 ≥20%，且两轮 B 的 P95 均不劣；**资源门**五项（`timeout_total` 增量 = 0、零锁异常、`pending/active` 与净投递并列、B/A `Com_select` 倍数如实且与收益并列、磁盘开工/收口各记）；**语义门**五项（`retry_count>0`=0、耗尽增量=0、`uk_event_id` 零重复、markSent=cohort、零 `RECONSUME_LATER`）。三支：**落地支**（双 cell ≥2 有效轮 + 三门全过 ⇒ 落地）；**未定支**（任一 cell <2 有效轮 / V6/V7 不成立 / 改善门未达 / 资源语义有红 ⇒ 不改任何默认值）；**反证支**（双 B P50 劣于 A 较好者或两轮 B 方向矛盾 ⇒ 记不推荐）。任一 cell 预算耗尽仍 <2 有效轮 ⇒ **直接落未定支，不加跑**。
6. 条件式落地（**仅落地支**）：`application.yml` 纯新增（numstat `N/0`、`^verify:` 根键仍**恰好 1 个**）+ 新增纯 JUnit 测试类（`YamlPropertySourceLoader` 断言 `relay-interval-ms`=500、反射断言 `@Scheduled.fixedDelayString` 字面仍为 `${verify.outbox.relay-interval-ms:5000}`、断言根键唯一；无 `@SpringBootTest`、无中间件/网络依赖）+ C 确认轮（`>1s` 空档 ≤5 且中位 ≤2000 ms 且 C 轮 P50 落两轮有效 B 中位 ±25% 内）；C 不过 ⇒ 按编辑前备份字节回滚（`cmp` rc=0）+ 删新测试类 + offline 复跑证明回 120。
7. 预登记反例 5 条**逐字登记**并逐条说明触碰情况（见文末）。

## Impact

- 规范：仅增加 `sport-record-verify` 的「relay 调度间隔判别与条件式落地」证据要求（预热吸收冷启动、V1–V7 与三支裁决不得放宽、自指门槛多读法披露、未定/反证支不得改默认值、结论不得外推），不改可靠投递语义或主规范总稿。
- 文件：`docs/perf/复测-outbox-relay-调度间隔-可重复性.md` + `docs/perf/data/exp-outbox-relay-interval-repeatable.json`、本三件套（纯 ADDED）、`work/mailbox/tasks/TASK-162/{spec.md, handoff.md}`、`work/mailbox/PLAN.md` 纯追加 1 节；原始日志与中间产物在 ignored `docs/perf/data/raw/task162-*` 与 `.trae/tmp/*`（不入库）。**未落定支 ⇒ `application.yml` 与新测试类不产生**。
- 环境：演示库新增约 1.3 万行记录与相应轨迹点（含预热轮；运行证据，**不得清理**）；容器只 `docker start`（不 recreate / 不改配置 / 不删卷）；`task131-scratch-mysql` 与 `task161_pool_scratch` 零触碰；`sport-verify-postgis` 保持 Exited。
- 既有 archive、`.trae/`、`add-verify-degrade-status-index/` 原状保留；不 stash、不 `git add -A`、不 push/PR。

## 判定与停止条件

起栈失败 / 健康不过 / Nacos 存在覆盖 `verify.outbox` 的配置 / 磁盘 <100 GB / 演示库耗尽行增长 / 负载 429 或 errors>0 / relay 失败行或耗尽行 / 日志或配对样本缺失 / 任一 cell 预算耗尽仍不足 2 有效轮 ⇒ 立即停手回报，不为凑轮次强行继续、不为救结论放宽门槛。无论落哪一支：除本条落地外不改任何配置（`relay-send-concurrency` 保持 1）、不换 B 档取值（不试 200/1000/2000 ms）、不叠加 batch/并发/池/JVM/SQL/索引/MQ 补救、不翻案 TASK-144 的 UNDETERMINED、不改写 TASK-143/144/145/152/156/161 任何数字。

**本轮实跑归属（事后登记，不改写上文预注册文字）**：落**未定支** —— 预算 6/6 计数轮耗尽，A cell 仅 1 个有效轮（A1；A2/A3/A4 因 V6 失败），V6 四种读法全部收敛未定；未改 `application.yml`、未加测试类、无 C 轮、无回滚。详见 `docs/perf/复测-outbox-relay-调度间隔-可重复性.md` §0/§6。

## 预登记反例（5 条，逐字登记）与触碰情况

1. **「B 档改善已由 TASK-144 证明，可以直接落地」**——不成立：TASK-144 的改善门虽满足，但**可比性门失败**，其裁决是 UNDETERMINED；本任务不重判 TASK-144，而是用修好可比性的新实验独立取证。若本轮可比性再失败，结论仍是未定。
   触碰情况：**未触碰** —— 本轮可比性确再失败（V6 四读法全收敛未定），裁决同未定，未落地。
2. **「500ms 一定比 5000ms 好，所以可以顺手试 200ms/1000ms 找最优」**——禁止：换档试探就是钓鱼。B 档取值 **500ms** 在本任务书里预注册死，不换、不扫。
   触碰情况：**未触碰** —— B 档全程锁死 500ms，未换档扫描。
3. **「间隔调小后 relay 更快，所以并发也可以一起开」**——禁止：`relay-send-concurrency` 全程 = 1；TASK-160 的代码默认关闭，TASK-161 只到「证据不足」，两者都不构成本轮开启并发的授权。
   触碰情况：**未触碰** —— `relay-send-concurrency` 全程 = 1，未开并发。
4. **「P50 从 57s 降到 19s 说明单行成本下降了」**——不成立：本轮只改 tick 频率，单行 `syncSend`+`markSent` 成本未测未变；净投递速率上升来自空档缩短，不得表述为「单行变快」或「SQL 优化」。
   触碰情况：**未触碰** —— 报告未做单行成本表述；净速率上升全部归因于空档缩短（tick 频率）。
5. **「资源没报红就说明 500ms 无代价」**——不成立：空扫 tick 频率约 10× 是结构性代价，必须与收益并列披露；`Com_select` 增量倍数缺失即视为披露不完整，指导侧会退回。
   触碰情况：**未触碰** —— 空扫 tick 频率约 10× 的代价与收益并列披露（报告 §7④ 含 `Com_select` 倍数 ≈1.001 与结构性解释）。
