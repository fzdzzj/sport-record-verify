# 提案：当前 HEAD 校验判定与榜单事件分段积压归因

## Why

TASK-138 在旧 HEAD/旧栈、R5 全降级的 c100×2000 负载中观察到「提交→判定落库」约 15～18s 和 outbox PENDING 1012 / SENT 998；这不是当前 HEAD 的分段数据，更不能把 outbox PENDING 全部当可发送队列。TASK-142 已使重试耗尽行不占取批窗口，但该行仍保留 PENDING，且不再每轮产生日志告警。当前尚不知道时间究竟耗在 SUBMITTED 消费排队、record Feign/SQL、校验/R5、结果及 outbox 落库、relay 投递，还是榜单消费。

**已核代码与强反例**：`run-perf.sh start-services` 只启动 gateway/user/record/verify 四服务，不启动 leaderboard/mapmatch；历史 R5 不可用。`LoadTest` 的 raw.csv 只有 seq/HTTP 延迟，不含 recordId；请求幂等键含一次运行的 `runId`，但现有摘要不写 runId。跨模块的 `record.created_at`、`verification_result.checked_at`、outbox `created_at/sent_at` 是 DATETIME 秒精度，不能直接推导毫秒级队列/处理时长；日志中 recordId、eventId、traceId 可用于关联，但不同进程时钟、重复消费、异步提交回调会使简单减法失效。应先核对拓扑、共同样本及计时边界，再决定能测哪些段。

本提案**仅做一次受控归因，不实施性能优化**。不预设哪个队列主导；不调 relay 周期、批次、消费线程、连接池、索引、JVM、缓存或事务边界。

## What Changes

1. 冻结 HEAD、已有脏项及服务/中间件版本，画出每个阶段的「开始/结束事件、关联键、精度、失败/重试路径」。优先复用已有请求幂等键、日志、DB 时间与 broker/consumer 观测；`LoadTest` 若缺可复现的运行 ID，可**仅**为摘要/日志补 `runId` 输出和相应小测试，不改请求体、并发、样本、限流统计或服务业务代码。缺可靠阶段边界则标未知，不凭各段独立 P50 相减。
2. 读-only 基线：当前 outbox 分别统计 `status=PENDING AND retry_count<maxRetry`（可投递）及 `>=maxRetry`（待人工），各自 count、最老 created_at 年龄及本次运行范围；与 SENT 单独分组。不得用总 PENDING 冒充活跃积压，也不得仅靠重复日志的行数当实际消费数。确认 record/verify/leaderboard、MQ、MySQL 和可选 PostGIS/mapmatch 健康，核对真实 R5 是否生效、时钟/时区及数据库已有历史行。绝不删除/清理演示数据卷。
3. 磁盘和现有积压允许时，在**同一 HEAD/同一服务配置**下最多执行一次 `bash scripts/perf/run-perf.sh load 100 2000 task143-stage`（额外预热 10 属该轮）；该轮无论成功失败均计入上限，不重跑凑指标。先检查约 2010×300 点增长空间；raw/log 留忽略目录。尽可能先起完整服务栈与真实路网，若 leaderboard 或 mapmatch 无法健康/无路网数据，允许在已健康四服务上跑这一轮的**明确部分覆盖**，但绝不声称完整榜单链路或 R5 质量通过；如果连安全四服务都不具备，则只记静态/基线与未覆盖，不造数据。
4. 只对能可靠关联到该轮的样本做成对阶段统计：提交→SUBMITTED 发布/消费、消费开始→判定落库、判定/outbox 创建→SENT、SENT→榜单消费/贡献入库（若有可靠观测）；MQ 消费到达与排空率、relay 批次耗时/行数、可投递与待人工积压变化。各阶段分别列 n、精度、P50/P95/P99/最大值及失败/重复/缺失数；同一 eventId/recordId 的重投要去重并独立计数。跨进程日志时钟未校齐或只有秒级 DB 时间时只报告相应精度/范围，不能给毫秒级细分或凭差分指认 SQL/HTTP/R5 单项。
5. 写出一份报告及机器摘要：列当前最强证据支持的一段（如无可比证据则明确“主瓶颈未确定”）、反例、下一步只改一段的假设与停止条件。更新唯一总览 `work/mailbox/后端优化机会总览-2026-09-26.md`：它目前是预先存在的未跟踪指导文件，本任务首次纳入版本控制时要在 TASK-143 清单中明确声明，不改写历史事实。一次作业完成必要的验证、三件套勾选、TASK-143 handoff/PLAN、契约及本地提交；不拆成单条命令或单笔 commit 的回合，不 push/建 PR。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的运行时瓶颈归因与可靠投递可观测边界；不改变判定、事件和榜单业务语义。
- 预计交付：`docs/perf/` 下新报告与机器摘要；raw/log 位于既有忽略目录；仅确有必要时小改 `scripts/perf/LoadTest.java`（输出 runId）及测试，不在本提案修改服务 Java/SQL/默认配置。三件套、本任务 mailbox 两件套与 PLAN；上述指导总览作为**已有未跟踪文件**在本任务内更新并首次提交，须纳入只改清单。若无法以只读观测完成阶段区分，不扩大服务插桩或启用新的生产端点，直接记录未知。
- 安全/资源：负载最多一轮，环境不足时停；不清库、不破坏历史 raw，不向表写故障种子；服务栈起停需记录并恢复开工状态。时钟不同步、outbox 重试/申诉终判混入、同名历史事件、R5/榜单缺席都是强反例，必须列出。

## 停止条件

完整链路无法就绪不等于四服务局部数据无效，但必须缩窄结论；连请求与 DB/日志样本不能可靠关联时不执行大负载。任何持续服务异常、磁盘不足、MySQL/MQ 不健康或已有队列不能判断新旧来源时停止，记录退出码与未覆盖。任务结束不自动修改参数或将「诊断分段结果」宣称为上线提速。
