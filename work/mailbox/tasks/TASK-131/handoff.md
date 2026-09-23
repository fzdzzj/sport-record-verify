# TASK-131 Handoff

**执行方：子 agent（红绿取证 + 接线落地）。** 未 push。开工基线 `9e64196`（开工时 `git status` 仅 `?? .trae/`）。

## 结论

| 项 | 结果 |
|---|---|
| 判定主链路接线 | **已接线**：`VerifyService.verify` / `reviewAppeal` 两处发事件改为「与结果行同事务写 outbox PENDING 行」，经修正后的 `VerifyOutboxService` |
| 单一出口 | **已成立**：判定线程零同步发送；`VerifyEventProducer` 的同步直发 + catch-log 兜底删除，发送能力仅保留给 relay 调用 |
| eventId 语义 | **已实现**：eventId 在写侧（outbox 行）生成一次并落行，relay 投递沿用行内 eventId（消费端幂等键在重试间稳定） |
| DDL | **已落**：`sql/03-verify-db.sql` 追加 `verify_event_outbox`（幂等 IF NOT EXISTS）；scratch 库实测表存在、列与唯一键齐全、脚本连跑两次 rc=0 |
| 用例数 | verify-service **81 → 88**（+7）；全量 **300 → 307**，其余模块逐位不变 |
| 停止边界 | 未动 `VerifyDegradeService`、未动 leaderboard 消费端、未改既有表结构、未 push；DB 实测只用 scratch 容器（`mysql:8.0.46`，宿主端口 13318，独立容器 `task131-scratch-mysql`） |

## 接线落点（对照设计裁定 1-5）

1. **DDL**：`sql/03-verify-db.sql:38-55` 新增 `verify_event_outbox`——id 自增、`uk_event_id` 唯一、
   topic/tag/payload(JSON)/trace_id/status(PENDING|SENT)/retry_count/created_at/sent_at，
   另加 `idx_status_id(status,id)` 服务 relay 的 `WHERE status='PENDING' ORDER BY id LIMIT n`。
   `trace_id` 列按实体字段补（TASK-102 spec:33 列清单未列，实体已有该字段，不补列则 relay 透传 traceId 失效）。
2. **接线**：`VerifyOutboxService` 由「upsert + 同步直发」改为真写 outbox 行，两个事务方法：
   - `persistResultAndEvent(result, verdict, recordId, userId)`：`verificationResultMapper.upsert` + `outboxMapper.insert(newPendingRow(...))`；
   - `updateAppealAndEvent(appealId, from, to, operator, recheck, verdict, recordId, userId)`：
     `appealMapper.updateStatus`（乐观锁）+ 影响行数 >0 时 `outboxMapper.insert(...)`，返回影响行数。
   两者均 `@Transactional(rollbackFor = Exception.class)`，只包本地 DB 写（ADR-0009）。
   `VerifyService` 两个调用点改为委托它，类内不再持有 producer。
3. **eventId 语义**：`VerifyEventProducer.newPendingRow` 生成 UUID 与 payload（含同一 eventId）并落行；
   relay 用 `row.getPayload()` 原样投递，重发不换 id。producer 的「每次生成新 UUID」只发生在写侧。
4. **单一出口**：`VerifyEventProducer.publish(...)`（同步直发 + catch 吞异常）**删除**；
   新增 `syncSend(VerifyEventOutbox row)` 供 relay 调用（失败抛出，不再吞）。
   `VerifyOutboxRelay` 改为 `verifyEventProducer.syncSend(row)`，不再直接持有 `RocketMQTemplate`。
   producer 的发送能力**保留**（设计裁定 4 的要求），删的是「判定线程直发」这条路径。
5. **延迟语义**：事件从「判定线程同步毫秒级到达」变为「relay 周期内到达」（`verify.outbox.relay-interval-ms`
   默认 5000，首次延迟 10s）；已写进 `VerifyService` 类/方法 javadoc、relay javadoc 与 spec-delta 场景，
   proposal「Impact」节单列。榜单侧 10min 结算纠偏兜底未改（leaderboard 消费端不在本任务范围）。

### 需记录的判断（口径未由任务包直接给定）

- **终判路径的「结果行」取 appeal 行**：`reviewAppeal` 原本只改 appeal 行、不写 `verification_result`，
  故同事务的本地第二笔只能是 appeal 行更新。**未**顺手把 `verification_result.verdict` 改成改判后判定：
  那会改变 `getVerificationResult` 的读语义（历史上终判后结果行保留原判），属行为变更而非接线，
  已登记为未决（见文末），不在本任务擅自扩大。
- **规格三件套的判定依据**：主规格存在事件发布/幂等的需求对象
  （`spec/specs/sport-record-verify/spec.md:897` 「校验事件与幂等」，其正文即「经 RocketMQ 发布
  SUBMITTED/VERIFIED/REJECTED 并以 eventId 去重」）→ 命中「有则建」条件，故建
  `spec/changes/wire-verify-outbox/`：**MODIFIED**「校验事件与幂等」+ **ADDED**「判定事件可靠投递」
  （EARS：WHEN 判定结果落库, 系统 SHALL 同事务写事件待发行行, 事件 SHALL 由 relay 唯一投递）。

## 红① 取证（接线缺失实证）

判别式：`VerifyServiceTest.verify_mainPath_doesNotPublishDirectly`——判定主链路跑完后，
断言 mock 的 producer 的同步直发方法**零调用**。基线代码该断言必然红（当时 API 尚是 `publish`）。

命令与退出码：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test` → **rc=1**（BUILD FAILURE），
日志 `.trae/tmp/task131-red1.log`。失败原文（逐字）：

```
[ERROR] com.sportverify.verify.service.VerifyServiceTest.verify_mainPath_doesNotPublishDirectly -- Time elapsed: 0.063 s <<< FAILURE!
org.mockito.exceptions.verification.NeverWantedButInvoked:

verifyEventProducer.publish(
    <any>,
    <any long>,
    <any long>
);
Never wanted here:
-> at com.sportverify.verify.mq.VerifyEventProducer.publish(VerifyEventProducer.java:39)
But invoked here:
-> at com.sportverify.verify.service.VerifyService.verify(VerifyService.java:124) with arguments: [PASSED, 1, 100]

[ERROR] Tests run: 82, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

「But invoked here」直接点到 `VerifyService.java:124`——与 TASK-128 handoff 记的 `:105-109` 同处直发点
（行号漂移由 TASK-129 的 javadoc 插入造成），即「判定线程同步直发」在基线仍然存在。

接线完成后同一条判断改由**新 API** 表达（`verify_mainPath_doesNotSendDirectly`：`outboxMapper.insert` 命中
一次 + `rocketMQTemplate.syncSend` 零调用），因为被删掉的方法已不存在于受理面。

## 红② / 绿② 取证（DDL 缺失实证）

环境：scratch 容器 `task131-scratch-mysql`（`mysql:8.0.46`，宿主 13318，独立卷，非 compose 实例）。

红②（基线脚本，日志 `.trae/tmp/task131-ddl-red.log`）：

```
### 红②（基线）：按 sql/03-verify-db.sql 初始化 verify_db
mysql_apply_rc=0
--- 表存在性（期望 1，实测如下）---
0
--- verify_db 现有表 ---
appeal
rule_version
verification_result
--- 脚本内 verify_event_outbox 命中数 ---
0
```

即：脚本本身能干净执行（rc=0），但建出来的库里**没有** outbox 表——relay 扫空表报错的根因坐实。

绿②（补 DDL 后同一路径重建，日志 `.trae/tmp/task131-ddl-green.log`）：

```
apply#1_rc=0
apply#2_rc=0            ← 连跑两次：IF NOT EXISTS 幂等成立
--- 表存在性（期望 1）---
1
--- 列与唯一键 ---
id bigint / event_id varchar(64) / topic varchar(128) / tag varchar(64) / payload json /
trace_id varchar(64) / status varchar(16) DEFAULT PENDING / retry_count int DEFAULT 0 /
created_at datetime / sent_at datetime
PRIMARY(id)  uk_event_id(event_id)  idx_status_id(status,id)
--- 脚本内 verify_event_outbox 命中数（期望 >=1）---
1
```

## 绿与变异

- 定向绿：`--mode=offline --pl verify-service test` → **rc=0 / BUILD SUCCESS**，
  `Tests run: 88, Failures: 0, Errors: 0, Skipped: 0`（日志 `.trae/tmp/task131-green2.log`）。
  中途一次编译红（`BaseMapper.insert` 的 `T(1)`/`Collection<T>` 二义：`insert(any())` 不明确）已修为
  类型化匹配器 `insert(any(VerifyEventOutbox.class))`，非用例语义红，如实记录。
- 变异验证（`cp` 备份 + sha256 留底 → 注释掉 `VerifyOutboxService:45` 的 outbox insert → 复现红 → 还原）：
  定向复现 **rc=1**，3 条红全部指向 outbox 写入缺失（日志 `.trae/tmp/task131-mutation.log`）：

```
VerifyOutboxServiceTest.persistResultAndEvent_upsertsThenInsertsPendingRow     (VerificationInOrderFailure)
VerifyServiceTest.verify_mainPath_doesNotSendDirectly                          (Wanted but not invoked: verifyEventOutboxMapper.insert)
VerifyServiceTest.verify_mainPath_passesAndWritesOutboxRow                     (Wanted but not invoked: verifyEventOutboxMapper.insert)
[ERROR] Tests run: 88, Failures: 3, Errors: 0, Skipped: 0
```

  还原后 `sha256sum -c` **OK**、`cmp` **零差异**（备份哈希 `d79c2804…9b43` 前后一致）。

## 模块用例数变化

| 模块 | 基线 | 终态 | 差 |
| --- | --- | --- | --- |
| common | 20 | 20 | 0 |
| gateway | 30 | 30 | 0 |
| user | 33 | 33 | 0 |
| record | 80 | 80 | 0 |
| **verify** | **81** | **88** | **+7** |
| leaderboard | 50 | 50 | 0 |
| mapmatch | 6 | 6 | 0 |
| 合计 | 300 | 307 | +7 |

verify 明细（终态）：`VerifyServiceTest` 9→10、`VerifyOutboxServiceTest` 1→4、`VerifyEventProducerTest` 4→6、
`VerifyOutboxRelayTest` 4→5。零删除、零跳过。

## offline 全量汇总

`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，
逐模块 `20/30/33/80/88/50/6 = 307`（Failures 0 / Errors 0 / Skipped 0）。
开工基线同命令 **rc=0 / 300**（`.trae/tmp/task131-offline-baseline.log`），
终态 `.trae/tmp/task131-offline-final.log`。

## 词面自检

脚本 `.trae/tmp/wording-check-131.sh`（CI 同款正则，UTF-8 承载，命令行纯 ASCII）：
`LC_ALL=C` **ZERO-HIT**（rc=0）；默认 locale 见本文末「未决」节登记。

## 契约

见 PLAN.md 收口记录（在途 `--baseline=9e64196` + 收口后无参数两跑）。

## 只改清单

- sql/03-verify-db.sql
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyEventProducer.java
- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
- verify-service/src/main/java/com/sportverify/verify/service/VerifyOutboxService.java
- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyEventProducerTest.java
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java
- verify-service/src/test/java/com/sportverify/verify/service/VerifyOutboxServiceTest.java
- verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java
- spec/changes/wire-verify-outbox/proposal.md
- spec/changes/wire-verify-outbox/tasks.json
- spec/changes/wire-verify-outbox/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-131/spec.md
- work/mailbox/tasks/TASK-131/handoff.md
- work/mailbox/PLAN.md

## 未决与后续

1. **终判后 `verification_result.verdict` 是否随改判更新**：本任务未改（保持既有读语义），
   如需一致化（改判通过后结果行反映 RE_PASSED 语义）应另立变更并评估 `getVerificationResult` 的读方。
2. **规格归档**：`spec/changes/wire-verify-outbox/` 为在途变更，归档（并入主规格 + 移 archive/）
   按既有流程在后续任务收口；本任务只落三件套。
3. **relay 延迟语义的外部可见性**：事件延迟由 5s 周期决定，若压测/演示口径要「毫秒级入榜」，
   需调 `verify.outbox.relay-interval-ms` 或另立变更（当前由 10min 结算纠偏兜底，未改消费端）。
4. **F09（消费端双轨重试）不在本任务范围**，归 TASK-132；`record → verify` 降级链路未触碰。
5. **词面自检默认 locale 残留**：如与 TASK-118 起的既登记伪影（`api/.../MapMatchResultDTO.java` 之类
   本任务未触碰的文件）命中，按未覆盖登记，判据以 `LC_ALL=C` 为准。
