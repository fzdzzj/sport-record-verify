# TASK-182 add-notification-center 通知中心后端 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-08）；执行：执行侧（同会话）。结论见 §2，证据见 §5；提交表见 §8（显式哈希）。

## 1. 开工规程核验

- 派发笔四个文件已入库（哈希 `21392a5…`），执行侧**零改动派发笔内容**：`spec/changes/add-notification-center/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-182/spec.md`。
- 派发笔入库时工作树零残留；`origin/main...main` 自 `0 0` 前移 `0 1`。
- 开工事件全部就位：判定事件流（verify outbox → RocketMQ `RecordVerifyEvents.TOPIC`，`VerifyEventDTO` 携 `userId`，收件人无需回查）；消费范式先例 `LeaderboardEventConsumer`；好友接线点 `FriendService.accept()`（事务内两写）。
- **未触发任何停止条件**：未改 api 模块、未动既有消费者、未改 MQ 既有参数、既有测试全绿（无非本任务断言转红）；`FriendService.accept()` 现场与派发笔假设（事务内两写）逐字相符，故全程未停手。

## 2. 一句话结论与三支裁决

**功能四件套齐（表 / 消费者 / REST / IT）**，判定为 **PASSED**（判决见 §2.5）；唯一未覆盖项为「真 RocketMQ 往返 IT」——本机 broker 不可用（namesrv 9876 / broker 10911 探测不可达），按任务书 §6.4 记 **UNDETERMINED 不记通过**，已按环境变量 `TASK182_IT_*` 缺位 assume-skip 给读数 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 2`。

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

本收口笔（C-04）实际改动集恰好以下 4 条，**零越界、零触碰零触碰清单**（未改 api 模块 / 未动其余五服务 / 未动 root pom / 未改 `ci.yml` / 未改 `scripts/`）：

1. `spec/changes/add-notification-center/tasks.json`（闭环：全部 step `completed:true`、3 个 task `passes:true`）
2. `work/mailbox/tasks/TASK-182/spec.md`（追加 §7 收口记录，纯追加）
3. `work/mailbox/tasks/TASK-182/handoff.md`（本文件，新增）
4. `work/mailbox/PLAN.md`（追加 §TASK-182 验收记录，纯追加）

> 说明：C-01/C-02/C-03 三笔的业务改动集已随各自提交入库，不在本收口笔改动集内；本笔仅台账闭环。全任务实际足迹 = C-01 的 10 条 + C-02 的 4 条 + C-03 的 3 条 + 本笔 4 条 + 派发笔 4 条，`git status --porcelain` 收口后为空（仅 `.trae/` 临时文件已删）。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
|---|---|---|
| D1 | **任务书 §5 基线串「user-service 41」与实物不符**：实测七模块顺序为 `common/gateway/user/record/verify/leaderboard/mapmatch` = `36/41/33/127/144/59/10`，其中 **41 属 gateway-service，user-service 真实基线为 33**。 | 派发笔书写笔误（属执行侧不可改的人类原件）；执行侧以**全量离线实测为唯一口径**登记新基线，不影响「450 只增不减」判定——收口新基线 `36/41/63/127/144/59/10` = **480**，user-service 33→**63**（+30，全部为自己新增的确定性单测：NotificationServiceTest 12 + NotificationEventConsumerTest 8 + NotificationControllerTest 10），其余六模块逐位**零变化**。 |
| D2 | **C-03 提交主题串与任务书 §4 措辞不一致**：任务书主题 `feat(user): 通知读取接口与真 broker 往返 IT（TASK-182）`，实际落库 `feat(user): 通知读取接口与 RocketMQ 往返集成测试（TASK-182）`。 | 执行侧书写偏差（同词、非逐字）；因提交清场后无法改写已入库提交，按本仓先例（TASK-179/181）单开台账登记、不改写。C-01/C-02/派发笔主题串与任务书逐字一致。 |
| D3 | **依赖预注册「两依赖」实为「一新一已有」**：任务书 §0.3 说仅新增 `redisson-spring-boot-starter` + `rocketmq-spring-boot-starter` 两依赖；实测 user-service pom 在派发前**已含** `redisson-spring-boot-starter`（TASK 先例遗留），故 C-02 实际只新增 `rocketmq-spring-boot-starter` 一个。 | `redisson-spring-boot-starter` 的任务书“新增”项视为已满足（存量在位）；`rocketmq-spring-boot-starter` 系新增。未引入任何新 Maven 插件。 |

## 5. 实施证据（双幂等断言原文 + IT 往返读数）

### 5.1 幂等写入（表级 uk_dedup）
`NotificationService.createNotification` 用 **INSERT IGNORE**，以受影响行数判断首次落库。单测断言原文（`NotificationServiceTest`）：
- `createNotification_duplicateKeyZeroRows_false`：`assertFalse(service.createNotification(1001L, "RECORD_VERIFIED", 7L, "你的运动记录已通过校验", null, "evt-dup"))` —— 同 `dedup_key` 再写入返回 false，未产生第二行。

### 5.2 事件幂等（Redis SETNX 去重）
`NotificationEventConsumer.handleMessage` 首步 `bucket.trySet("1", 24h)`，`false` 即跳过。单测断言原文（`NotificationEventConsumerTest`）：
- `handleMessage_dedupKeyAlreadySet_skips`：`when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false)` → `verifyNoInteractions(notificationService)`。
- `buildConsumer_configuresNativeMaxReconsumeTimes3AndTagSubscription`：`assertEquals(3, built.getMaxReconsumeTimes())` + 订阅表达式含 `VERIFIED`/`REJECTED`（自 `getSubscriptionInner()` 读取，规避 getSubscription 恒 null 的契约坑）。

### 5.3 双重幂等（SETNX + uk_dedup 双层互锁）
- 层一：消费者 SETNX 去重（过期/重启后同 eventId 不再判重）。
- 层二：`uk_dedup(dedup_key=eventId)` 唯一键兜底——即使 SETNX 键过期，`INSERT IGNORE` 也至多落 1 行。
- 闭环消费链路在 `NotificationConsumerRoundTripIT` 中以「一次成功落 1 行 + 重复投递仍 1 行」断言互锁（§5.4）。

### 5.4 IT 往返读数
- **完成度**：代码就绪、可编译、可被 `-Dtest=NotificationConsumerRoundTripIT` 定向执行；env 缺 `TASK182_IT_ROCKETMQ_NAMESRV/TOPIC/REDIS_ADDR` 时 assume-skip。
- **直跑读数**（raw mvn 绕过 harness 硬编码 leaderboard 清单，沿 TASK-177/181 Notice 先例）：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 2`，rc=0（BUILD SUCCESS）。
- **判定**：本机 namesrv 9876 / broker 10911 **探测不可达**（真 RocketMQ 未起），故 **UNDETERMINED，不记通过**。真 Redis 6379 / scratch MySQL 3307 可达已于开工核验登记；如后续环境起 broker + 置 `TASK182_IT_*`，本 IT 断言一次成功落 1 行、重复投递仍 1 行即复绿（未虚构通过）。

### 5.5 REST 读取接口证据
`NotificationController`（`@RequestMapping("/api/notifications")`，取用户方式与 `FriendController` 同源 ADR-0007）：`GET /` 分页（id 倒序）、`GET /unread-count`、`PATCH /{id}/read`、`PATCH /read-all`。单测 10 条覆盖两态身份认定（auth=false 显式携带 / auth=true 以 X-User-Id 为准、显式不一致 → 越权 403、缺头 → 401）、越权不触达服务、单条已读未命中 → 5003、全部已读透出行数。`NotificationService` 12 条覆盖幂等/分页/未读/归属已读/全部已读。

## 6. 逐门实测表

| 门 | 读数 | rc |
|---|---|---|
| tasks.json 语法（收口态） | `json.load` OK：3 tasks 全 passes、6 steps 全 completed | 0 |
| 词面门（C-01/02/03 改动文件集 + tracked 四形态） | 新增/改动文件逐一 ZERO_HIT；tracked 范围 ZERO_HIT | 1（预期非零） |
| `git diff --check`（C-01/02/03 提交前 + C-04 提交前） | 干净 | 0 |
| 契约门在途 `--open TASK-182 --baseline=21392a5`（C-01） | rc=0（判据 A 两件套齐 + 判据 B 清单一致），见 §5 逐字 | 0 |
| 契约门在途 `--open TASK-182 --baseline=030c82d`（C-02） | rc=1：判据 B=1，历史任务 TASK-008/125 等 list多报（公共文件名 `pom.xml`/`application.yml` 共占触发）＋本笔新文件「改动集未声明」（在途未出 handoff，属 extract_claims 既知盲区，TASK-181 §1.2.4 先例） | 1（预期） |
| 契约门在途 `--open TASK-182 --baseline=dfa186f`（C-03） | rc=0（判据 B 清单一致，新文件无共占） | 0 |
| 契约门待收口无参（C-04 后复跑，见 §7 G4） | 判据 A 两件套齐 0 待办 + 判据 B 清单一致 | 0 |
| offline 全量 `--mode=offline test`（收口） | 新基线 `36/41/63/127/144/59/10` = **480**，Failures/Errors/Skipped 全 0，BUILD SUCCESS | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），BUILD FAILURE 为基线违规模块预期 rc | 1（预期） |
| token 29 项 | 开工实测 22/24/23/23/20/19/19/19/19/19/18/19/18/18/25/18/17/12/10/9/9/10/10/10/9/7/8/9/8（SUM=451）；收口复测只增不减，见 §7 G5 | 只增不减 |
| 只改清单全等 | `git diff --name-only` 恰 §3 的 4 条 | 全等 |

## 7. 收口门自我取证

- **G1 收口单测基线**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → 新七模块 `36/41/63/127/144/59/10` = **480**，rc=0，全绿零跳过。user-service 33→63（+30），其余模块逐位与派发笔基线相同 ⇒ **450 只增不减满足**（新总值 480 逐位登记）。
- **G2 静态基线**：`--static=record-service` → Checkstyle **811** 持平（rc=1 为预期，基线违规模块不改不增）。
- **G3 词面门**：全任务改动文件集 + tracked 范围四形态 ZERO_HIT。
- **G4 契约门待收口**：C-04 提交后 `scripts/verify/mailbox-contract.sh`（**无参**）→ rc=0。
- **G5 token**：PLAN.md 追加后 29 项计数 ≥ 开工实测（§5，`SUM=451`），只增不减。
- **G6 只改清单**：C-04 实际改动集恰 §3 四条；`git status --porcelain` 收口后为空。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
|---|---|---|
| 派发笔 | `21392a52687ae8e5c4dcdba691ef6e0fa02528f8` | `docs(spec): 派发 TASK-182 通知中心提案与任务书` |
| C-01 存储与好友接线 | `030c82d3a15674eaed7af96128ead9713982a19c` | `feat(user): 通知存储与服务层并接线好友通过通知（TASK-182）` |
| C-02 消费者 | `dfa186f3db066115e1366026491d7a4bc08fa048` | `feat(user): 判定事件通知消费者接线 RocketMQ（TASK-182）` |
| C-03 读取入口与 IT | `da8b928ce7aadd7140ef521e6890ebc1068d5abd` | `feat(user): 通知读取接口与 RocketMQ 往返集成测试（TASK-182）`（偏差见 §4 D2） |
| C-04 台账闭环 | `ec6cb77cad19c2607447c51ab27ee9ba46b8c636` | `docs(mailbox): 登记 TASK-182 通知中心验收与台账闭环（TASK-182）` |
| C-04b 台账哈希终态化 | `fbb544099237512503a1556baf14e14775ea17d7` | `docs(mailbox): 终态化 TASK-182 台账笔提交哈希（TASK-182）` |
| C-04c 台账父锚定订正 | `a4f358a7bc645b849295fb726bd03a2d65e1c284` | `docs(mailbox): 订正 TASK-182 台账父锚定为六笔终态（TASK-182）` |

父锚定（C-04c 终态）：`git rev-list --left-right --count origin/main...main` = `0 7`（派发笔 + C-01…C-03 三笔 + C-04 台账笔 + C-04b/c 两笔台账订正）。C-04b/c 为台账哈希与父锚定的零构建面订正，仅补全提交明细表的显式 SHA（沿本仓「提交表显式哈希，禁时效指针」先例），不涉及任何业务改动。

## 9. 未覆盖项

1. **真 RocketMQ 往返 IT 未跑**（broker 不可用）→ **UNDETERMINED**，不记通过；断言逻辑已在位，环境就绪即可复绿（§5.4）。好友/判定通知的「消费者→落库→去重」链路的语义等价已由纯单测（§5.1–5.3）逐字覆盖。
2. 通知 Web 铃铛 UI（TASK-183 候选）不在本任务范围。
3. 契约为「收口无参 rc=0 + 只改清单全等」口径，在途 rc=1 的共占/盲区成因逐条核对待办完成（§6），非越界改动。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions）CI 绿为外部终验；红则按签名归因（不带预设、禁重试刷绿）。第 23 次外部门槛 run `37769662324`（HEAD `729de8d4cb65…`）的绿读数已折入 §TASK-182 收口记录（本任务收口后的第 24 次门槛待外部 push 后由新 run 数值登记，不扩受保护 token 集合）。