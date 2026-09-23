# TASK-129 Handoff

**核实 + 文档化**。判定：**「冲突拒绝式收敛 + 消费幂等兜住」成立 → 文档化接受**，未引入任何锁。
未 push。

## 结论

| 条目 | 判定 | 一句话依据 |
|---|---|---|
| F22 | **已裁定文档化接受（2026-09-23）** | 四环节核实：双判定落库幂等 upsert 只覆盖、record 回调幂等跳过/3003 乐观锁拒绝、消费重试重入走补偿回调收敛、榜单锚点行保证只加分一次——无双份加分可复现路径 |

## 后果链证据（四环节，文件:行号）

### 环节 1：并发双判定入口与窗口（无互斥实证）

`verify-service/.../service/VerifyService.java:79-118`：verify 全方法无任何锁。
`readCachedResult`（:80）读缓存/DB 与 `initVerifying`（:87 INSERT IGNORE 占位）之间无原子性，
两个并发调用均可读到「未终判」同时进入判定。

并发重入的两个入口：

- **MQ 消费**：`VerifyEventConsumer.java:189` `verifyService.verify(event.getRecordId())`。
  消费端幂等按 **eventId** SETNX 去重（:180-185），而事件每次发布都生成新 eventId
  （`VerifyEventProducer.java:41` `UUID.randomUUID()`）——同 recordId 的两次重投/两个入口
  的 eventId 不同，去重拦不住并发重入；失败重试还会删去重键放行（:192）。
- **Feign 直调降级 / 人工重放**：`InternalVerifyController.java:42-46`
  `POST /internal/records/{recordId}/verify` 直接调 verify，无去重。

### 环节 2：结果落库冲突路径（3003 语义）

- **判定结果落库无冲突**：`VerificationResultMapper.java:17-19` `initVerifying`
  `INSERT IGNORE`（record_id 主键幂等）；`:24-28` `upsert`
  `ON DUPLICATE KEY UPDATE`（只覆盖不新增）。双判定不产生第二行，后到者覆盖终判值。
- **状态回调冲突路径**：`VerifyService.java:111-113` 回调
  `VERIFYING → PASSED/REJECTED`（带乐观锁 version）→
  record 侧 `SportRecordService.java:166-182`：
  - `:171-174` record 状态已等于目标状态 → **幂等跳过，直接成功返回**；
  - `:175-179` `updateStatus` 带 fromStatus+version 条件更新，`rows == 0` →
    `BizException(RECORD_STATUS_INVALID)`；
  - `common/.../result/ResultCode.java:44`：`RECORD_STATUS_INVALID(3003, "状态不允许该操作")`。

即 3003 只在「fromStatus 或 version 已被先到者改变」时出现；verdict 一致的常态下，
后到回调命中幂等跳过分支，连 3003 都不触发。

### 环节 3：上游重试方与收敛性

回调 3003 → `callbackStatus`（VerifyService.java:145-153）抛 BizException → verify 冒泡 →
消费端 `VerifyEventConsumer.java:190-197`：删去重键（:216-222）+
`RECONSUME_LATER` 交 MQ 退避重投（:140），重试超 3 次进 DLQ（:193-195、:236-245）。

重投重入 verify 的收敛路径：`:80 readCachedResult` → `:247-255` 从 verification_result
读到终判（upsert 已落库）→ `:81-84` `cached.isFinal()` → `:82 reconcileCallback`（:131-142）：

- record 已终态（非 VERIFYING / MANUAL_REVIEW）→ `:137` 直接 return，收敛完成；
- record 仍 VERIFYING（首轮回调前崩溃的历史残留）→ 补偿回调按 VERIFYING → 终态补迁。

### 环节 4：leaderboard 消费幂等（双份加分可复现路径排查）

并发双判定的常态（verdict 一致）会发布**两条 VERIFIED 事件，eventId 不同**——
`LeaderboardEventConsumer` 的 eventId SETNX 去重（:164-170）拦不住，
但业务层双保险兜住（`LeaderboardService.applyVerified` :110-154）：

- `:128-132` 先拿 `lock:rollback:{recordId}` Redisson 锁，同 recordId 的消费串行化
  （拿不到锁抛出让 MQ 重投，入榜不丢）；
- `:135-144` 锁内写锚点行 `INSERT IGNORE`（record_id 主键，`LeaderboardContributionMapper`）：
  第二条事件 inserted=0 → 走 `updateStatus(ROLLED_BACK → ACTIVE)` 乐观迁移，
  锚点已是 ACTIVE 时影响 0 行 → `credit=false` → `:149` 幂等跳过；
- `:145-146` ZINCRBY 仅在 credit=true 时执行——**重复 VERIFIED 事件只加分一次**。
  消费者 javadoc（:43-44）明示「重复投递即使去重键过期，INSERT IGNORE/乐观 UPDATE
  也保证只加/扣一次」。
- 兜底再保险：`:327-372` 每 10min 结算任务以 contribution ACTIVE 汇总为权威源纠偏 ZSet。

**无双份加分可复现路径。**（REJECTED 事件同理：`:184-191` 锚点行乐观迁移
ACTIVE → ROLLED_BACK 影响 0 行即幂等跳过，不产生双份扣分。）

窄窗备注（登记不立项）：两并发判定读到不同规则集（灰度比例中途变更）时可能一 VERIFIED
一 REJECTED，先到回调赢状态机、后到 3003 重投收敛；榜单先加分后按锚点回滚，净效果零加分，
无重复贡献。该窗口属规则热更新的既有最终一致设计，与重入互斥正交。

## 落盘说明

1. `VerifyService.java` verify javadoc 补「并发重入权衡」段（:78-95）：三层兜底机制 +
   重开条件，对齐 ADR-0009「无本地长事务」的表述风格。**仅 javadoc，代码零变更**。
2. `findings-summary.md` F22 标「已裁定文档化接受（2026-09-23，TASK-129）」+ 四环节证据 +
   重开条件。
3. 本 handoff + `PLAN.md` 收口记录。

## 验证记录

核实 + 注释类任务，无先红/后绿/变异环节（无代码行为改动）。

## 复跑结果

- **offline 全量**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → 见 PLAN.md 收口记录
  （任务包所写 299 为过期锚点：TASK-127 起基线为 300，本任务仅 javadoc 与台账，数字应零扰动）。
- **契约**：在途 `--baseline=ae811e0` + 收口后无参数，见 PLAN.md。
- **词面自检**：CI 同款正则双 locale，见 PLAN.md。

## 只改清单

- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java（仅 javadoc）
- work/mailbox/findings-summary.md
- work/mailbox/tasks/TASK-129/spec.md
- work/mailbox/tasks/TASK-129/handoff.md
- work/mailbox/PLAN.md
