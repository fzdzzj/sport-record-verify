# 提案：只检验 verify outbox relay 批间调度空档（TASK-144）

## Why

TASK-143 在 HEAD `997c789aa7420e2b33d3d6c0a23cf6066846cd04` 的**四服务局部** c100×2000 单轮中，2000 条可关联样本 `callback→SENT` P50 为 68.8s；约 2010 条 outbox 在 ~150s 内投递完，榜单与真实 R5 未覆盖。新核对 `docs/perf/data/raw/task143-stage-samples.csv`（同一 run，未修改原始文件）：把 SENT 时间排序，以相邻间距 >1s 作**诊断性**分批，20 个长空档中位 5025ms（5017～5550ms）；18 个完整百条批的首末 SENT 跨度中位 1327.5ms。源码 `VerifyOutboxRelay` 使用 `@Scheduled(fixedDelayString="${verify.outbox.relay-interval-ms:5000}")`、单轮上限 100、逐行 `syncSend`。这些观测与固定延迟主导批间空档一致，**不能**由此断定整轮 CPU、纯 MQ 往返或 SQL 各自耗时，也不能保证调小延迟在重复负载下收益稳定。

最强反例：日志中 SEND 成功与 `markSent` 后打印的时间不是完整批次起止；若该轮限流、到达速率、relay 锁冲突、MQ 或 DB 背景负载不同，长空档/净速率不能机械归因于单一配置。`@Scheduled` 为 fixedDelay（本轮结束后再等待），不是 fixedRate；减少 5s 间隔可能增加 DB 扫描、RocketMQ 压力，也不能保证到达速率 59 行/s 的稳态可持续。TASK-143 报告用两个独立 P50 的比值描述“占比”的文字不能当逐请求占比；若需要占比，必须对同一记录的时长作配对计算。

本提案只把**调度延迟**作为单一候选因素做同 jar、同负载对照。先验证生效参数和批次边界；仅在重复实验可靠、语义与资源门槛都满足时，才将该单一配置写入部署默认值。否则只记录不推荐/未定，不叠加批次、并发发送或 MQ 参数。

## What Changes

1. 冻结当前 HEAD 与既有脏项。核对 `VerifyOutboxRelay`/配置来源、`fixedDelay` 语义、实际生效批次=100、重试上限=16、日志/DB 关联键与服务健康。用 TASK-143 原始样本复核「长空档」但**不**当作当前基线的重复跑次；逐轮收集真实 relay 批次起止/行数/锁竞争或能从日志证明的近似指标，不能量到的标未知。
2. 有条件地用**同一 jar**以 A-B-B-A 次序至多四轮 c100×2000（每轮内置 10 预热），仅改变 `verify.outbox.relay-interval-ms`：A=5000ms，B=500ms；保持 batch-size=100、max-retry=16、消费线程、连接池、MQ、索引、JVM 不变。每轮开始前确认可投递 PENDING=0、耗尽行单列、同 run 可关联、磁盘和中间件健康；每轮结束等该轮 outbox 排空，失败也占预算，不能为凑结论加跑。raw/log 用唯一标签保存于忽略目录，严禁清库。
3. 对**同 run 配对**的 `callback→SENT` P50/P95/P99、relay 有效投递速率及批间空档/批内跨度，连同提交 QPS、错误率、可投递/耗尽 PENDING、CPU/DB/MQ 压力、失败/重试/重复计数做 A/B 对照。预设可比门槛：四轮提交 QPS 相对四轮中位数均在 ±15% 内，并按 10s 桶核对 outbox 创建形态；不满足则记未定，不因 B 改善而放宽门槛。日志缺失、任何失败/重试混入也降级为未定。禁止用独立 P50 相减、把全局 SENT 差量当该 run 速率、或声称榜单/R5 已覆盖。
4. 只有 B 的两轮都较两轮 A 中**较好者**使 `callback→SENT` P50 至少下降 20%，P95 不劣于 A 较好者，且提交成功率、回调与 relay 语义无回归、资源无不可接受增长，才推荐把**唯一一项**默认配置 `verify.outbox.relay-interval-ms=500` 写入 verify-service classpath YAML；同步新增默认值/装配判别测试，运行仓库 offline verify-service test。否则**不改运行默认值**；记录不推荐或证据不足，不再换 B 档试探。不更改服务 Java 业务逻辑、批量上限、锁、eventId、retry_count、消息幂等、SQL 或消费者配置。
5. 在 TASK-143 已提交报告/机器摘要中做**文字口径订正，不改原始数值**：将“到达榜单端”收窄为“outbox 标记 SENT 前”；删除独立 P50 相除的“占比”说法；把 13.4 行/s 低于 20 行/s 的原因从“逐行 syncSend”改为“批内发送/DB 工作等未分离”。同一作业完成新报告/机器摘要、三件套勾选、TASK-144 spec/handoff、PLAN 和统一总览，核对只改清单、`git diff --check` 与无参数 mailbox 契约，必要时仅提交本任务文件（业务/台账允许分两笔），不 push/建 PR。只读或未采纳的结果不冒充性能修复；不称 offline 为 CI。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的可靠投递延迟验证与实验决策口径；无 API、状态机或事件载荷变更。
- 预计文件：本提案三件套；`docs/perf/` 新报告与摘要、TASK-143 原报告与机器摘要的限定文字订正；TASK-144 两件套、PLAN、统一总览。**只有符合验收条件**才修改 `verify-service/src/main/resources/application.yml` 和增加/扩展针对实际 YAML 值的测试。raw/log 忽略不入库。不要扩大到其他后端模块。
- 数据与覆盖：四轮最多约 8040 条记录及相应轨迹点，先检查 D: 空间和已有库/队列；不清理演示数据。四服务局部结论不能外推 R5 正常或榜单消费完整链路。

## 停止条件

环境、数据关联、有效配置或起栈状态不确定，或出现健康/磁盘/消息积压异常时立即停止，不为满足四轮强行继续。B 两轮收益方向不一致、到达速率不具可比性、故障/重试混入或资源指标恶化时不改默认值；若失败发生在第一轮，它仍计入四轮预算。任何结果均不叠加调批次、并发发送、连接池、JVM、SQL 或索引作为“补救”。
