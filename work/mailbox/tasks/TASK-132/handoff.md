# TASK-132 Handoff

**执行方：子 agent（红绿取证 + 去双轨落地）。** 未 push。开工基线 `5f89566`（开工时 `git status` 仅 `?? .trae/`，无并行在途足迹）。

## 结论

| 项 | 结果 |
|---|---|
| 原生重试落地 | **已落地**：两侧消费者 `buildConsumer` 内统一 `setMaxReconsumeTimes(3)`，超次由 broker 转入 `%DLQ%verify-consumer-group` / `%DLQ%leaderboard-consumer-group` |
| 自建双轨删除 | **已删除**：`MAX_RETRY` 常量、`retryKey(...)`、`markRetryAndExceed(...)`、`sendToDlq(...)` 四件套，以及**仅为自建死信投递而存在**的 `RocketMQTemplate` 字段/构造参数（两侧构造签名 5 参 → 4 参） |
| 失败分支 | **已收敛为一条**：删去重键放行 → `rethrow`（监听器返回 `RECONSUME_LATER`），无「本地判定超阈值」分叉 |
| 幂等 | **未动**：eventId SETNX（24h TTL）、失败后删去重键放行、`verification_result` / 锚点行兜底全保留 |
| outbox 链路 | **未动**：TASK-131 的 `VerifyOutbox*` / `VerifyEventProducer` / relay 一行未碰，基线即 TASK-131 收口态 `5f89566`（其 handoff 门槛 verify 88） |
| 用例数 | 两侧**净 0**：verify 88 → 88（类级 8 → 8）、leaderboard 50 → 50（类级 9 → 9），全量 307 → 307 |
| 停止边界 | 未动消费幂等逻辑、未动 record→verify 降级链路、未动 broker/producer 配置、未改既有表结构、未 push |

## 语义等价说明（含差异如实登记）

| 维度 | 自建双轨（改前） | MQ 原生（改后） | 判定 |
|---|---|---|---|
| 重投触发 | `count` 1..3 时 `RECONSUME_LATER` | `reconsumeTimes` 0..2 时 broker `sendMessageBack` | 等价 |
| 入死信条件 | 本地 `count > 3`（第 4 次失败） | broker 侧 `reconsumeTimes >= 3`（第 4 次失败） | **逐数等价** |
| 总消费次数 | 1 次首发 + 3 次重投 = 4 | 同上 = 4 | 等价 |
| 退避 | broker `messageDelayLevel` | 同一机制 | 等价 |
| 计数载体 | Redis `verify:retry:{eventId}` / `leaderboard:retry:{eventId}`，**无 TTL**（全仓零 expire，F09 原文） | broker 消息属性 `reconsumeTimes` | 改后不再泄漏键 |
| 计数可用性 | Redis 故障时 `markRetryAndExceed` catch 返回 false → 上限静默失效 | 不依赖 Redis | 改后更稳 |
| 死信载体 | 自建普通 topic `record-verify-events-dlq`（无消费组、任何消费者可订） | `%DLQ%verify-consumer-group` / `%DLQ%leaderboard-consumer-group`（broker 内建、按组隔离） | **口径变更**，已写进 spec-delta 的 `失败进死信` 场景 |
| 死信投递失败 | 业务侧 `catch` 吞异常 + 调用方随即「视为处理完成」→ 消息既不重投也不在任何队列 = **静默丢失** | 由 broker 转投，业务侧无发送步骤 | 改后消除缺口 |
| 排查入口 | `mqadmin queryMsgByTopic record-verify-events-dlq` | `mqadmin` / 控制台按消费组查 `%DLQ%<group>` | 口径变更 |
| 多实例 | 每个实例各自判一次计数，自建 DLQ 可能重复投 | broker 单点判定 | 改后一致 |

**未变（停止边界内）**：eventId SETNX 去重与失败后删去重键放行、`RECONSUME_LATER` 路径、traceId 还原、解析失败丢弃、outbox/relay 全链路。

## 判别式与观测缝

- **观测缝抽取**：原生重试参数只能在**未启动**的消费者实例上读（`getMaxReconsumeTimes()`），而 `start()` 需要真 namesrv、无法离线执行。故先落**行为中性**的抽取：`startConsumer` 的「建实例 + 配置 + 订阅 + 注册监听」移入 package-private `buildConsumer(ns)`，`startConsumer` 只负责 `start()` 与字段赋值；配置顺序、订阅表达式、监听器注册逐字未变。行为中性由两侧定向 rc=0 且用例数与基线逐位一致证明（88 / 50，日志 `.trae/tmp/task132-seam-verify.log` / `task132-seam-leaderboard.log`）。
- **提交切分说明（如实登记）**：抽取与去双轨最终**同笔提交**（主代码按模块各一笔），故 git 历史里看不到「先抽取后改造」两笔；但抽取态**真实构建并跑绿过**，日志在 `.trae/tmp/`（不入库），红绿变异三段取证均在该序列上进行。
- **坑（记入 skill 候选）**：`DefaultMQPushConsumer#getSubscription()` 读的是一枚**从不被 `subscribe()` 填充**的独立字段（恒 `null`），首轮红取证因此出现一条与订阅无关的假失败（`expected: <SUBMITTED> but was: <null>`）；订阅真身在 impl 的 rebalance 容器，须经 `getDefaultMQPushConsumerImpl().getSubscriptionInner()` 读。该假失败已作废重取红，最终订阅断言为回归护栏（红绿两轮均绿）。

## 红取证（改前：仅观测缝 + 新判别式）

命令与退出码：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` → **rc=1**（`.trae/tmp/task132-red-verify.log`）；`--pl leaderboard-service test` → **rc=1**（`.trae/tmp/task132-red-leaderboard.log`）。

verify（`Tests run: 88, Failures: 4, Errors: 0, Skipped: 0`）失败原文（逐字摘录）：

```
[ERROR]   VerifyEventConsumerTest.buildConsumer_enablesNativeMaxReconsumeTimes3:84 expected: <3> but was: <-1>
[ERROR]   VerifyEventConsumerTest.handleMessage_failure_neverConsultsSelfBuiltRetryKey:143
redissonClient.getAtomicLong(<any string>);
Never wanted here:
-> at com.sportverify.verify.consumer.VerifyEventConsumerTest.handleMessage_failure_neverConsultsSelfBuiltRetryKey(VerifyEventConsumerTest.java:143)
But invoked here:
-> at com.sportverify.verify.consumer.VerifyEventConsumer.markRetryAndExceed(VerifyEventConsumer.java:240) with arguments: [verify:retry:evt-1]
[ERROR]   VerifyEventConsumerTest.handleMessage_failureAfterSelfBuiltThreshold_rethrows:155 Expected java.lang.RuntimeException to be thrown, but nothing was thrown.
[ERROR]   VerifyEventConsumerTest.handleMessage_deleteDedupKeyRedisError_stillRethrows:169
... But invoked here:
-> at com.sportverify.verify.consumer.VerifyEventConsumer.markRetryAndExceed(VerifyEventConsumer.java:240) with arguments: [verify:retry:evt-1]
```

leaderboard（`Tests run: 50, Failures: 4, Errors: 0, Skipped: 0`）同构 4 条：

```
[ERROR]   LeaderboardEventConsumerTest.buildConsumer_enablesNativeMaxReconsumeTimes3:86 expected: <3> but was: <-1>
[ERROR]   LeaderboardEventConsumerTest.handleMessage_failure_neverConsultsSelfBuiltRetryKey:159
... But invoked here:
-> at com.sportverify.leaderboard.mq.LeaderboardEventConsumer.markRetryAndExceed(LeaderboardEventConsumer.java:231) with arguments: [leaderboard:retry:evt-x]
[ERROR]   LeaderboardEventConsumerTest.handleMessage_failureAfterSelfBuiltThreshold_rethrows:171 Expected java.lang.RuntimeException to be thrown, but nothing was thrown.
[ERROR]   LeaderboardEventConsumerTest.handleMessage_deleteDedupKeyRedisError_stillRethrows:187
... But invoked here:
-> at com.sportverify.leaderboard.mq.LeaderboardEventConsumer.markRetryAndExceed(LeaderboardEventConsumer.java:231) with arguments: [leaderboard:retry:evt-x]
```

两点解读：①`expected: <3> but was: <-1>` 坐实「改前根本没有设置过重试上限」（`-1` 为客户端默认）；②Mockito 的「But invoked here」直接点到 `markRetryAndExceed(...)`，即失败路径确实在写自建计数键，调用实参 `verify:retry:evt-1` / `leaderboard:retry:evt-x` 与 F09 原文的键名逐字一致。**行号漂移**：TASK-128 登记 `:225-233`（verify）/ `:216-224`（leaderboard），本次实测为 `:240` / `:231`——漂移由观测缝抽取插入 12 行造成。

`handleMessage_failureAfterSelfBuiltThreshold_rethrows` 的红最具说服力：把自建计数桩塞到旧口径的「超阈值」（`incrementAndGet()=4`），改前**不抛异常**（第 4 次失败被本地转投自建 DLQ 并 `return`），故 `assertThrows` 必红；改后同一条断言反过来证明「无论如何都 rethrow 交 MQ」。

## 绿取证（改后）

同命令：verify → **rc=0 / BUILD SUCCESS / `Tests run: 88, Failures: 0, Errors: 0, Skipped: 0`**（`.trae/tmp/task132-green-verify.log`）；leaderboard → **rc=0 / `Tests run: 50, Failures: 0`**（`.trae/tmp/task132-green-leaderboard.log`）。类级：`VerifyEventConsumerTest` 8、`LeaderboardEventConsumerTest` 9，与基线逐位一致，零跳过。

过程注记（如实登记，非用例语义红）：绿轮首跑两模块 rc=1，为**编译红**「找不到符号：方法 markRetryAndExceed / sendToDlq」——同一条消息内对同一文件并行提交多处编辑时，后一笔写入覆盖了前一笔（verify 的 catch 分支与 leaderboard 的字段删除未落盘），重放同样编辑后消失。同轮 leaderboard 另因 `RocketMQTemplate` import 已删而字段未删产生编译红，同因同解。

## 变异验证（TASK-106 手法）

冻结修订（绿轮后另修一处注释口径：监听器 catch 的「未超重试阈值」措辞随本地阈值判定一并更新）后 `cp` 修复态副本 + `sha256sum` 留底（`0855e22a…` / `8a34c0f7…`，`.trae/tmp/task132-mut.sha256`）→ 逐文件删除 `c.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);` 一行 → 定向复现红（`.trae/tmp/task132-mutation.log`，rc=1 ×2）：

```
[ERROR]   VerifyEventConsumerTest.buildConsumer_enablesNativeMaxReconsumeTimes3:83 expected: <3> but was: <-1>
[ERROR] Tests run: 88, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   LeaderboardEventConsumerTest.buildConsumer_enablesNativeMaxReconsumeTimes3:86 expected: <3> but was: <-1>
[ERROR] Tests run: 50, Failures: 1, Errors: 0, Skipped: 0
```

即配置判别式**只因那一行而红**（其余 87 / 49 条全绿）。还原：`sha256sum -c` 两文件 **OK** + `cmp` **零差异**。全程未用 `git stash`（备份/还原走 `cp` + 哈希）。

## 撤销用例逐条登记（判据随口径变更撤销）

### verify-service（类内 8 → 8；删除 3 / 改写 3 / 新增 3）

| # | 原用例 | 处置 | 理由 |
|---|---|---|---|
| 1 | `handleMessage_retryExceeded_sendsToDlq` | **撤销** | 判据是「自建计数超阈值 → 向 `record-verify-events-dlq` 投递」；自建投递代码按设计删除，该行为不存在，判据随口径变更撤销 |
| 2 | `handleMessage_dlqSendFailure_swallows` | **撤销** | 判据是「自建 DLQ 发送失败吞异常、不阻断消费」；改后无发送方→无此分支 |
| 3 | `handleMessage_retryCounterRedisError_stillRethrows` | **撤销** | 判据是「重试计数键 Redis 异常时降级为继续重投」；计数代码已删，分支不存在 |
| 4 | `handleMessage_retryNotExceeded_rethrowsForRetry` | 改写 → `handleMessage_failure_neverConsultsSelfBuiltRetryKey` | 保留「失败即 rethrow 交 MQ」核心，去掉 retryCounter 桩，新增「零 `getAtomicLong`」断言（自建轨消失的行为判据） |
| 5 | `handleMessage_deleteDedupKeyRedisError_stillRethrows` | 改写 | 保留「去重键删除异常不阻断重投」，去掉 retryCounter 桩、补零 `getAtomicLong` 断言 |
| 6 | `handleMessage_unparseableBody_discards` | 改写 | 原判据「不向自建 DLQ 发送」已无发送方；改为「不占用去重键（零 `getBucket`）、不触发校验」 |
| 7 | `handleMessage_duplicateEvent_skips` / `handleMessage_firstTime_consumes` | 保留 | 幂等语义未变，原样保留 |
| 8 | `buildConsumer_enablesNativeMaxReconsumeTimes3` | **新增（判别式）** | 原生重试参数=3 |
| 9 | `handleMessage_failureAfterSelfBuiltThreshold_rethrows` | **新增（判别式）** | 旧口径「超阈值」下仍 rethrow |
| 10 | `buildConsumer_subscribesSubmittedTag` | **新增（回归护栏）** | 观测缝抽取不得丢订阅；红绿两轮均绿，非判别式 |

### leaderboard-service（类内 9 → 9；删除 3 / 改写 2 / 新增 3）

| # | 原用例 | 处置 | 理由 |
|---|---|---|---|
| 1 | `handleMessage_retryExceeded_sendsToDlq` | **撤销** | 同 verify #1（同构代码） |
| 2 | `handleMessage_dlqSendFailure_swallows` | **撤销** | 同 verify #2 |
| 3 | `handleMessage_retryCounterRedisError_stillRethrows` | **撤销** | 同 verify #3 |
| 4 | `handleMessage_retryNotExceeded_rethrows` | 改写 → `handleMessage_failure_neverConsultsSelfBuiltRetryKey` | 同 verify #4 |
| 5 | `handleMessage_deleteDedupKeyRedisError_stillRethrows` | 改写 | 同 verify #5 |
| 6 | `handleMessage_duplicateEvent_skips` / `_verified_applyVerified` / `_rejected_rollback` / `_unknownType_skips` | 保留 | 幂等与 eventType 分发语义未变 |
| 7 | `buildConsumer_enablesNativeMaxReconsumeTimes3` | **新增（判别式）** | 同 verify #8 |
| 8 | `handleMessage_failureAfterSelfBuiltThreshold_rethrows` | **新增（判别式）** | 同 verify #9 |
| 9 | `buildConsumer_subscribesVerifiedAndRejectedTags` | **新增（回归护栏）** | 观测缝抽取不得丢 VERIFIED/REJECTED 双 Tag 订阅 |

**净额**：删 6 增 6 改 5 保留 6，两模块用例数**均回到基线值**（88 / 50），无净减，符合「删 N 增 M、不允许净减」的指导侧特批口径。

## 模块用例数增减明细

| 模块 | 基线 | 终态 | 差 |
| --- | --- | --- | --- |
| common | 20 | 20 | 0 |
| gateway | 30 | 30 | 0 |
| user | 33 | 33 | 0 |
| record | 80 | 80 | 0 |
| **verify** | **88** | **88** | **0**（类级 `VerifyEventConsumerTest` 8 → 8） |
| **leaderboard** | **50** | **50** | **0**（类级 `LeaderboardEventConsumerTest` 9 → 9） |
| mapmatch | 6 | 6 | 0 |
| 合计 | 307 | 307 | 0 |

## offline 全量汇总

`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，逐模块 `20/30/33/80/88/50/6 = 307`（Failures 0 / Errors 0 / Skipped 0）。开工基线同命令 **rc=0 / 307**（`.trae/tmp/task132-offline-baseline.log`），终态 `.trae/tmp/task132-offline-final.log`。

## 静态检查（leaderboard-service，本仓唯一接入模块）

`bash scripts/verify/mvn-verify.sh --static=leaderboard-service` → **rc=0 / BUILD SUCCESS**（`.trae/tmp/task132-static.log`）：checkstyle **0 violations**、PMD 无失败项（`PMD version: 7.17.0` 已分析、`BUILD SUCCESS`）、SpotBugs **Total bugs: 9**——基线为 10（9 条 `EI_EXPOSE_REP2` + 1 条 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`，见 TASK-018 记账），本次随仅供自建 DLQ 的 `RocketMQTemplate` 注入字段删除而恰好减少 1 条；`failThreshold=High` 配置未动，存量仍全为 Medium ⇒ 删除未被读取的字段**未新增任何违规**。checkstyle 只扫主源码（`includeTestSourceDirectory=false`），测试改动不在其口径内。

## 词面自检

脚本 `.trae/tmp/wording-check-132.sh`（CI 同款正则与排除，UTF-8 承载、命令行纯 ASCII）：`LC_ALL=C` 与默认 locale 双跑，结果见 PLAN 收口记录。

## 契约

见 PLAN.md 收口记录（在途 `--baseline=5f89566` + 收口后无参数两跑）。

## 只改清单

- verify-service/src/main/java/com/sportverify/verify/consumer/VerifyEventConsumer.java
- verify-service/src/test/java/com/sportverify/verify/consumer/VerifyEventConsumerTest.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumerTest.java
- spec/changes/adopt-native-mq-retry/proposal.md
- spec/changes/adopt-native-mq-retry/tasks.json
- spec/changes/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-132/spec.md
- work/mailbox/tasks/TASK-132/handoff.md
- work/mailbox/PLAN.md

## 未决与后续

1. **公开文档口径漂移（不在只改清单，本任务未改）**：`docs/判定引擎-开发总览.md:104` 仍写「失败按 `verify:retry:{eventId}` 计数，3 次后投 DLQ」；同文件 `:103` 与 `docs/运动记录校验系统需求文档（审批版）.md:345` 仍把 `record-verify-events-dlq` 记为死信 topic。三处需另立微变更同步（改动集一旦越界即触发契约判据 B「改动集未声明」）。
2. **`api` 模块残留**：`RecordVerifyEvents.java:9` 的 javadoc 与 `:25` 的 `DLQ_TOPIC` 常量在本仓已无调用方（`grep` 仅命中自身与文档）。常量是 public API、删除无授权，按「未覆盖」登记。
3. **leaderboard `application.yml:59`**：注释「生产端仅供死信投递（消费重试超阈值 → record-verify-events-dlq）」随本次删除失效，且 producer 段已无发送方（`RocketMQTemplate` 仅 verify 侧 still 使用）。yml 不在只改清单。
4. **spec 归档顺序**：本变更与在途 `wire-verify-outbox` 同时 MODIFIED「校验事件与幂等」。本 delta 已按**目标态**整段书写（含对方的 SHALL 行与 `判定事件经待发行表投递` 场景），任一先后归档均收敛到同一终态，但**建议先归档 `wire-verify-outbox`**（否则中间态会出现引用尚不存在需求的场景）。两个变更均待归档。
5. **真 broker 端到端未覆盖**：`%DLQ%<group>` 的实际生成与消息转投需真 broker（本机 FlClash/中间件状态下未起 Nacos+RocketMQ 全链路），TASK-110 的 `RocketMqBrokerRoundTripIT` 不在本次改动集。语义按 RocketMQ 官方路径（`consumerSendMsgBack`：`reconsumeTimes >= maxReconsumeTimes` 时转 DLQ）与离线单测断言登记，**记为未覆盖**，不得写成通过。
6. **findings F09 状态标注未改**（`work/mailbox/findings-summary.md` 不在只改清单）；F09 原文「两份消费者公共逻辑抽到 common」也未做（不在任务范围，属可选重构）。
7. **`PLAN.md` 公共文件过冲**：本任务改动集含 `PLAN.md`，历史 handoff 正文提到该文件名即触发在途契约的强校验交叠（既往已多次登记），非本任务清单不一致。
