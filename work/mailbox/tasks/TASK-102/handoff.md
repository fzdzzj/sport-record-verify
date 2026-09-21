# TASK-102 事件可靠性（F03/F09/F13/F22）handoff

## 完成情况
4 条发现全部落地，3 条验收命令全过（见下）。

## 每条发现改动点

### F03 本地消息表（outbox）
- `sql/03-verify-db.sql:51-67`：新增 `verify_event_outbox`（id 自增 / event_id 唯一 / topic / tag / payload JSON / status PENDING-SENT / retry_count / created_at / sent_at + idx_status_id），`CREATE TABLE IF NOT EXISTS` 幂等。
- 新增 `verify/entity/VerifyEventOutbox.java`、`verify/mapper/VerifyEventOutboxMapper.java`（selectPendingBatch / markSent / incrRetry，更新均带 status='PENDING' 条件幂等）。
- `verify/mq/VerifyEventProducer.java`：`publish` 签名不变，内部改为写 outbox PENDING 行（不再直连 MQ；写库失败抛出，verify 路径触发同事务回滚）。
- 新增 `verify/service/VerifyOutboxService.java`：`@Transactional persistResultAndEvent` = upsert verification_result + outbox insert 同事务（独立 Bean 避免自调用代理失效；事务内无任何远程调用，不违反 ADR-0009）。
- `verify/service/VerifyService.java:152`：verify 主流程改调 `verifyOutboxService.persistResultAndEvent`；`reviewAppeal` 仍回调后 `publish`（落 outbox，:286）。
- 新增 `verify/mq/VerifyOutboxRelay.java`：`@Scheduled`（`verify.outbox.relay-interval-ms` 默认 5s / `batch-size` 默认 100 / `max-retry` 默认 16），Redisson 锁 `verify:outbox:relay` tryLock(0) 防多实例重跑；扫 PENDING → syncSend → 成功 markSent / 失败 incrRetry；≥16 次仅 error 告警保留行供人工。relay 类自带 `@EnableScheduling`（VerifyApplication 不在改动清单内，verify-service 此前无调度开关）。
- leaderboard 消费侧未动。

### F09 去双轨
- `verify/consumer/VerifyEventConsumer.java`、`leaderboard/mq/LeaderboardEventConsumer.java`：删除 MAX_RETRY / 重试计数键方法 / 自建死信投递方法及 RocketMQTemplate 依赖；`startConsumer` 加 `setMaxReconsumeTimes(3)`；失败统一删去重键后抛出 → RECONSUME_LATER；类注释写明人工排查入口（`%DLQ%verify-consumer-group` / `%DLQ%leaderboard-consumer-group`）。SETNX 去重保留。
- 验收 grep：`verify-service/src/main`、`leaderboard-service/src/main` 无 retryKey/sendToDlq 残留。

### F22 并发重入互斥
- `VerifyService.java:68-69,106-113`：`lock:verify:{recordId}` tryLock(0)；拿不到锁 → 复读缓存/DB 现状返回（无结果则 VERIFYING 占位），不重判；拿到锁 double-check 后在 finally 释放；锁内行为不变，与幂等缓存正交。

### F13 缓存不一致窗口文档化
- `VerifyService.java:53-58` 类 javadoc：Caffeine 单层 ≤1min 窗口 + 收敛路径（状态机幂等 + 消费端去重 + 锚点行），不改行为。

## outbox 设计取舍
选「VerifyEventProducer.publish 保留签名、内部改写 outbox」而非 service 直写：事件体组装（eventId/Tag/JSON）本就是生产者职责，调用方零改动、侵入最小。代价：**traceId 不再随事件透传**（relay 线程无原 MDC；消息头无 traceId，消费端会自建新 trace）。
另：`reviewAppeal` 的 outbox 行在 appeal 状态更新与 Feign 回调之后单条写入，未与 appeal update 同事务（中间隔 Feign 调用，强行同事务会变长事务）；写库失败抛错给管理员，残余窗口与改造前一致。

## 验收结论
1. `mvn -s .mvn-settings.xml -q -pl verify-service,leaderboard-service -am test`：exit 0，全绿（verify 79 + leaderboard 全部，含新增 9 条用例）。
2. `verify_event_outbox` 在 03-verify-db.sql ≥1 处且含 IF NOT EXISTS：通过。
3. retryKey/sendToDlq 残留 grep：无。

## 偏差说明（需主 agent 知悉）
- **新增 2 个测试资源文件**（超出「只改文件」清单）：`verify-service/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`、`leaderboard-service/src/test/resources/...同名`，内容 `mock-maker-subclass`。原因：本环境 ByteBuddy inline mock maker 需 self-attach 外部进程，被沙箱拦截（`Could not self-attach`），验收命令裸跑全红（含未改的既有测试）；禁改 pom 的前提下，切 subclass mock maker 是唯一不改 pom 的修法，纯测试侧、零生产影响。若主 agent 环境无此限制，可删除两文件。

## 待主 agent 决定
1. 上述 2 个 mockito-extensions 文件：保留（环境普适）还是删除（依赖运行环境允许 self-attach）。
2. traceId 事件透传缺失：可接受，或在 VerifyEventDTO/表上加 trace 字段（超本任务范围）。
3. reviewAppeal outbox 行未与 appeal 状态更新同事务：是否接受现状。
