# TASK-128 Handoff

**核实方：子 agent（纯核实，无代码改动）**。未 push。

## 结论

| 条目 | 判定 | 一句话依据 |
|---|---|---|
| F03 | **仍在（outbox 组件已建但从未接线，属死代码）** | 判定主链路仍是 upsert 后直发 + catch 吞异常；全仓无任何代码写 verify_event_outbox 行；运行日志实证表不存在 |
| F09 | **仍在** | 两个消费者双轨重试 + retryKey AtomicLong 无 TTL 原样都在 |

派发背景的两处归因修正：
1. outbox 落地提交 a048745 对应的是 **TASK-102**（非 TASK-108；TASK-108 是每日榜单快照，见其 handoff.md）。
2. a048745 只**新增**了 6 个文件（entity/mapper/relay/事务写入器 + 2 个测试），**从未改动**
   VerifyService / VerifyEventProducer——「已闭环」不成立，findings 台账原文的「仍在」判断是对的。

## F03 证据（文件:行号 + 关键原文）

### 1) 判定主链路未走 outbox，仍是直发

`verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`
- `:105-109`（verify 主流程）：

```java
// 证据落库（record_id 主键 upsert；重复判定只覆盖不新增）
verificationResultMapper.upsert(toEntity(result));

// 发 VERIFIED / REJECTED 事件（Tag 区分，规范「校验事件与幂等」）
verifyEventProducer.publish(result.getVerdict(), recordId, record.getUserId());
```

- `:233-235`（reviewAppeal 终判路径）：

```java
// 发事件：改判通过 → VERIFIED；维持拒绝 → REJECTED
verifyEventProducer.publish(dto.isPass() ? Verdict.PASSED : Verdict.REJECTED,
        appeal.getRecordId(), appeal.getUserId());
```

全类无 VerifyOutboxService 注入/调用。

### 2) producer 直发 + catch 仅 log（旁路原样）

`verify-service/src/main/java/com/sportverify/verify/mq/VerifyEventProducer.java:53-60`：

```java
rocketMQTemplate.syncSend(RecordVerifyEvents.TOPIC + ":" + (passed
                ? RecordVerifyEvents.TAG_VERIFIED : RecordVerifyEvents.TAG_REJECTED),
        builder.build());
log.info("已发布 {} 事件：recordId={}, traceId={}", event.getEventType(), recordId, traceId);
} catch (Exception e) {
    // 事件发送失败不阻断判定回调；生产可加本地消息表补偿
    log.error("发布 {} 事件失败：recordId={}", event.getEventType(), recordId, e);
}
```

catch 注释原文「生产可加本地消息表补偿」即承认补偿未做。F03 引用的 :57-60 行号在当前代码仍然准确。

### 3) outbox 行全仓无写入方

- `git log --all -S "persistResultAndEvent" -- .../VerifyService.java` → **空**（任何提交里 VerifyService 都没调过它）。
- `git log --all -S "verifyOutboxService"` → 唯一命中 `6650ae3`（docs/mailbox 台账提交，即 TASK-102 handoff 的描述文字本身）。
- 全仓 grep `new VerifyEventOutbox|outboxMapper.insert` → 仅 `VerifyOutboxRelayTest.java:55`（测试构造）。
- `VerifyOutboxService.persistResultAndEvent`（VerifyOutboxService.java:31-36）实现是
  `verificationResultMapper.upsert(result)` + `verifyEventProducer.publish(...)`——**它自己也不写 outbox 行**，
  Javadoc「同事务落『判定结果 upsert + outbox 事件行』」与实现不符（publish 内部是直发）。

### 4) relay 在跑，但扫的是永远空表 + 表压根没建

`VerifyOutboxRelay.java:55-57` 定时 5s 扫 `selectPendingBatch`（重发逻辑本身完整：成功 markSent、
失败 incrRetry 下轮再试、超 16 次告警留行——组件质量没问题，缺的是上游写入方）。
但：
- `sql/` 全目录 grep `verify_event_outbox` → **0 命中**（TASK-102 spec.md:33/40 明确要求 DDL 写进
  sql/03-verify-db.sql 且验收 grep ≥1，未达成）；
- 运行日志 `logs/verify.out:84`：`Table 'verify_db.verify_event_outbox' doesn't exist`
  （BadSqlGrammarException，每个 relay 周期刷一遍）。

### 5) 台账漂移

TASK-102 handoff.md:12-13 声称「VerifyService.java:152 改调 persistResultAndEvent；reviewAppeal
仍回调后 publish（落 outbox，:286）」——与提交事实不符（见上第 3 点 git -S 取证），属历史台账虚报。

### F03 判定：仍在

事件丢失窗口与 findings 原文描述完全一致，未消除。差距原文（供裁定立项）：
1. VerifyService.verify 与 reviewAppeal 两处发事件未接入 outbox；
2. VerifyOutboxService 需改为真写 outbox 行（注入 VerifyEventOutboxMapper insert PENDING）；
3. sql/03-verify-db.sql 补 verify_event_outbox DDL（TASK-102 spec 已写明表结构）；
4. producer.publish 的 catch-log 吞异常去留需裁定（若主链路改走 outbox，直发兜底与 relay 重发
   会双发；消费端 eventId 幂等可兜，但需在 handoff 说明取舍）。
5. TASK-102 handoff 虚报接线，归档/修订口径待裁定。

## F09 证据（文件:行号 + 关键原文）

### verify 侧 `VerifyEventConsumer.java`

- `:52` `private static final int MAX_RETRY = 3;`（自建阈值）
- `:140` 失败 `return ConsumeConcurrentlyStatus.RECONSUME_LATER;`（broker 原生重投仍在）
- `:225-233` markRetryAndExceed：

```java
private boolean markRetryAndExceed(String eventId) {
    try {
        long count = redissonClient.getAtomicLong(retryKey(eventId)).incrementAndGet();
        return count > MAX_RETRY;
    } catch (Exception ex) {
        log.warn("重试计数失败：eventId={}", eventId);
        return false;
    }
}
```

AtomicLong 无任何 expire/TTL（全仓 grep `getAtomicLong` 仅上述两处 + 两个测试 mock，无其他 touch 点）。
- `:236-245` sendToDlq → `rocketMQTemplate.syncSend(RecordVerifyEvents.DLQ_TOPIC, body)`（自建 DLQ 仍在）。

### leaderboard 侧 `LeaderboardEventConsumer.java`

- `:55` `MAX_RETRY = 3`；`:126` RECONSUME_LATER；`:216-224` markRetryAndExceed（与 verify 侧同构，:218 incrementAndGet 无 TTL）；`:227-236` sendToDlq。

与 findings 原文引用的行号（52-54/225-233、55-57/215-224）逐一吻合，零改动。

### F09 判定：仍在

TASK-102 spec 第 2 条（去双轨：`setMaxReconsumeTimes(3)` + 删 retryKey/sendToDlq）完全未落地。

## 附带发现（超出本任务范围，仅登记）

TASK-102 spec 共 4 条（F03/F09/F13/F22），按当前代码看仅 outbox 组件骨架落地；F22 的
Redisson 重入锁（VerifyService.verify 入口 `lock:verify:{recordId}`）在 :79-118 无踪影。
F13/F22 现状未逐条核实，不在本任务范围。

## 只改清单

- work/mailbox/findings-summary.md
- work/mailbox/tasks/TASK-128/spec.md
- work/mailbox/tasks/TASK-128/handoff.md
- work/mailbox/PLAN.md

## 验证记录

无代码改动，无先红/后绿/变异环节（核实类任务按 skill 走反向取证）。

- offline 全量：`bash scripts/verify/mvn-verify.sh --mode=offline test` → 见下方「复跑结果」节
- 契约：`--baseline=c2479ad` 在途 + 收口后无参数，退出码见「复跑结果」节
- 词面自检：双 locale，见「复跑结果」节

## 复跑结果

- **offline 全量**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，
  模块合计 `20/30/33/80/81/50/6 = 300`（Failures 0 / Errors 0 / Skipped 0），与 TASK-127 收口锚点
  逐位一致**零扰动**（任务包所写 299 为过期锚点：TASK-127 已把基线抬到 300，本任务无代码改动，数字不动）。
  日志 `.trae/tmp/task128-offline.log`。
- **词面自检**（CI 同款正则 `.trae/tmp/task128-wording.sh`）：`LC_ALL=C` **ZERO-HIT**（rc=0）；
  默认 locale 2 命中均为 TASK-118 起登记的本机伪影（`api/.../MapMatchResultDTO.java:17/36`，本任务未触碰），如实登记按未覆盖计。
- **契约**：在途 `--baseline=c2479ad` 判据 B 结果 + 收口提交后无参数 rc，见 PLAN.md 收口记录（收口时回填）。
