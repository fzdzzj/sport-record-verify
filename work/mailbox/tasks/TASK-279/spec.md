# TASK-279 任务书（spec）：社交与活动批——挑战赛 + 跑团对抗赛 + 评论 + 年度报告

> 立项：2026-10-19 用户拍板（I 系候选 I2+I3+I4+I6 组合批）；开工基线：main @ 本课题立项笔（哈希由 handoff §1 开工时回填）；任务分级：**FULL 档**（跨服务新业务域 + DDL + 结算任务 + MQ 新消费者，强制独立复核）。

## 一、背景与目标

四项新业务能力（避重核对 BACKLOG F/B/D/E/G/H/I 系全量闭环记录，零重叠）：

- **I2 挑战赛活动系统**：个人挑战（自定周期里程目标）+ 官方挑战 + 参与追踪 + 完成徽章联动；
- **I3 跑团对抗赛**：团间周约战（挑战方/应战方），按成员有效里程汇总定胜负，战绩与赛程列表；
- **I4 社交评论**：动态流/记录详情页评论发布 + 删除 + 通知联动（现仅点赞，社交深度补全）；
- **I6 年度运动报告**：年度里程/活跃天数/最长单次/类型分布/社交互动统计聚合端点 + 前端报告页与分享海报。

系统无心率设备，纯 GPS 里程/配速/轨迹数据；挑战仅里程口径。

## 二、架构决策（预注册，与实况冲突即停）

1. **新表全落 record_db**：`challenge` / `challenge_participant` / `club_challenge` / `record_comment` 四表，`sql/02-record-db.sql` 仅末尾追加 + `sql/migrations/add-challenge-and-comment.sql` 幂等迁移双路径，建表 DDL 两处逐字一致（沿 club_distance_contribution 先例）；**对抗赛团里程源 = `club_distance_contribution` 锚点 (club_id, week_start) 同库聚合**（TASK-278 资产只读复用；禁实时 ZSet 读、禁改锚点表任何语义与列）。
2. **挑战进度结算**：`@Scheduled` 每日刷新 + 读时兜底计算（沿 ClubDistanceSettleService 范式，锁内幂等、best-effort 全 catch 零抛出）；完成时 BadgeService 同服务直调授予（BadgeType 新增枚举值，不动既有枚举序号）。
3. **评论通知联动**：CommentEvent MQ best-effort（producer 异步零抛出，沿 LikeEvent 范式）→ user-service 新增 `CommentEventConsumer`（编程式 DefaultMQPushConsumer + 3 原生重试 + DLQ + Redisson 24h trySet 去重，沿 NotificationEventConsumer 逐字范式；新 topic 不动既有消费者任何参数）——**user-service 仅限此 2 新文件，其余零触碰**。
4. **评论可见性门**：沿 assertCanView 单记录范式；PUBLIC/FRIENDS 记录评论区可见性对齐动态流口径；仅本人评论可删（物理删沿点赞范式）。
5. **年报口径**：`COALESCE(start_time, created_at)`（沿 myStats/goal 统一口径）；仅当年 + 上一年两档；纯聚合读端点零缓存零定时。
6. **MVP 边界**：挑战仅里程类（次数/时长类后续另立）；对抗赛仅周赛制（周一开赛周日结算，东八墙钟沿 G1 统一口径）；评论单层无楼中楼；年报不含 PR 汇总（I1 未立项不预埋）。

## 三、白名单（上限 40 项，超出即停）

- **SQL（2）**：`sql/02-record-db.sql`（仅末尾追加）、`sql/migrations/add-challenge-and-comment.sql`（新增）；
- **record-service 主类（16）**：entity/Challenge.java、mapper/ChallengeMapper.java、entity/ChallengeParticipant.java、mapper/ChallengeParticipantMapper.java、service/ChallengeService.java、controller/ChallengeController.java、entity/ClubChallenge.java、mapper/ClubChallengeMapper.java、service/ClubChallengeService.java、controller/ClubChallengeController.java、entity/RecordComment.java、mapper/RecordCommentMapper.java、service/RecordCommentService.java、controller/RecordCommentController.java、service/YearReportService.java、enums/BadgeType.java（仅追加枚举值）；
- **record-service 测试（4）**：ChallengeServiceTest.java、ClubChallengeServiceTest.java、RecordCommentServiceTest.java、YearReportServiceTest.java；
- **user-service（2，仅此两件）**：mq/CommentEventConsumer.java、test/mq/CommentEventConsumerTest.java；
- **api 模块（2）**：dto/YearReportDTO.java、dto/event/CommentEventDTO.java（沿 VerifyEventDTO 范式：@Data + @NoArgsConstructor + @AllArgsConstructor，幂等键 Javadoc）；
- **web（8）**：src/api/client.ts、pages/challenges.page.vue、pages/challenge-detail.page.vue、pages/year-report.page.vue、pages/feed.page.vue（评论区插入）、pages/record-detail.page.vue（评论区插入）、e2e/helpers.ts（新端点 mock）、typed-router.d.ts（构建产物提交）；
- **择实（2）**：web vitest 新增 spec ×1、api/record 既有测试适配（如 BadgeType 扩展波及的既有断言）；
- **台账（5）**：本 spec、handoff、work/mailbox/PLAN.md、BACKLOG.md、HANDOVER.md。

**零触碰红线**：gateway/verify/leaderboard/mapmatch 四服务 src 与 test 全量；user-service 除白名单 2 件外 src/test；根 pom 与各模块 pom；package.json（新页面零新依赖）；docker-compose；.github；web 既有依赖与构建配置；既有 V 序列迁移脚本（只新增）；状态机/MQ 既有消费者参数；`club_distance_contribution` 锚点表语义与列；`SportRecordService.java` 既有行（年报落独立 YearReportService，主服务零触碰）。

## 四、门禁要求（FULL 档全量逐位）

1. offline 全量单测：基线 **1208** = 63/79/250/411/203/145/57 只增不减（新测试落位预估 record/user 两模块净增）；
2. 静态门 record-service + user-service：Checkstyle 0 违规、SpotBugs High=0、PMD 通过；
3. 前端三件套：vue-tsc 0 错 / vitest 全绿 / E2E 全绿（新页面 mock 需补齐 helpers，防 E14 假绿复发）；
4. 契约门 `scripts/verify/mailbox-contract.sh --open=TASK-279 --baseline=<立项笔哈希>`：判据 A 两件套齐、判据 B 改动集双向全等；
5. token 门 29 项：C-02 树 = 基线 + 29（handoff §6 自枚举所致逐项 +1；29 项权威清单沿 TASK-181 §6，派发包附指引）；
6. 词面门四 locale（default/C/zh_CN.UTF-8/C.UTF-8）ZERO_HIT + 阳性对照（正则现场提取自 ci.yml，字面量不入任何入库文件）；
7. diff 净（`git diff --check` 空）+ 新文件纯 LF 无 BOM 末尾换行 + 既有文件保持 CRLF；
8. DDL 门：migrations scratch 库双跑（二跑 CREATE TABLE IF NOT EXISTS 全跳过）+ `scripts/db/migrate.sh` 全量 16+1/16+1 + `docker compose config -q`。

## 五、预注册裁决与停止条件

裁决 1–6 见 §二（表落库/结算范式/通知联动/可见性门/年报口径/MVP 边界），实施侧遇两难按裁决执行不再申报。停止条件（触发即停止修改并汇报）：

- 需触碰白名单外文件或任一红线面；
- 锚点表需任何语义/列改动（只读复用前提被破坏）；
- MQ 既有消费者参数需改动；
- 单测无法纯 JVM 落地（如必须真实 MQ/Redis 才能验证的语义，登记后改派 IT 口径）；
- 范式冲突（本 spec 与仓内惯例两难且无先例可援），先登记后处置，禁止静默适配。

## 六、阅读顺序

先读本 spec 全文 → `work/mailbox/tasks/TASK-278/handoff.md` §4（锚点表与结算范式实施实录，本课题 R2 里程源与 R1 结算的直接先例）→ `user-service/src/main/java/com/sportverify/user/mq/NotificationEventConsumer.java`（R3 消费者逐字范式）→ `record-service/.../service/RecordLikeService.java`（点赞通知 producer 与物理删范式）→ 开工。
