# TASK-244 台账回传（handoff.md）

> **任务**：TASK-244 治理闭环、存储优化与高并发防爆——申诉待审列表驱动、通知索引慢查消除与批量已读、总榜Redis内存防爆与定时结算对账补偿（MEDIUM 档）
> **实现工程师**：TASK-244 Implementer
> **开工基线**：`a870d1c`（main，工作树干净）
> **状态**：交付完成，门禁全绿

## 一、交付概述

- **B15 申诉待审闭环**：`VerifyController` 新增 `GET /api/appeals/pending`，`VerifyService.listPendingAppeals()` 按 `id DESC` 返回全部 PENDING 申诉；前端 `client.ts` 导出 `fetchPendingAppeals()`，`admin-appeal.page.vue` 增加「待审核申诉列表」卡片与表格（申诉ID/记录ID/申诉人/理由/提交时间），支持「填入审核」一键把 ID 填入表单，初载与终判成功后自动刷新，终结盲打 ID；`VerifyControllerTest` 与 `client.test.ts` 补齐待审契约单测。
- **B16 记录删除与榜单回撤自动对账补偿自愈**：`LeaderboardContributionMapper` 新增 `selectOrphanContributions`（查询 `status=ACTIVE` 但 `sport_record` 已不存在记录的孤儿行）；`LeaderboardService.settleAndReconcile` 结算期间先识别孤儿行并调 `updateStatus` 置为 `ROLLED_BACK`，从当期 target/ZSet 剔除，离线自愈 Feign 抖动遗留脏里程；`LeaderboardServiceTest` 补齐对账回滚与幂等单测。
- **B17 通知中心索引优化与批量标记已读**：`sql/01-user-db.sql` 为 `notification` 表补齐 `KEY idx_user_id (user_id, id)`；新建幂等迁移脚本 `sql/migrations/add-idx-notification-user-id.sql`（information_schema 防重）；`NotificationController` 新增 `PATCH /api/notifications/batch-read`，`NotificationService.batchMarkRead(userId, ids)` 批量流转 `is_read=1` 并回填 `read_at`、触发回执；前端 `client.ts` 导出 `batchMarkNotificationsAsRead(ids)`，`notifications.page.vue` 表格开启 `row-selection` 多选并增加「批量标记已读」交互（成功后清空选中并刷新列表/未读数）；`NotificationServiceTest` 与 `client.test.ts` 补齐单测。
- **B18 总榜 Redis 内存防爆与倾倒重构**：`LeaderboardService.settleAndReconcile` 将全量 `range(0,-1)` 重构为 `opsForZSet().scan(...)` 游标分批遍历比对清理（批次 500，不再整榜 dump 进 JVM 堆）；结算末尾对 `OVERALL_ZSET_KEY` 执行 `removeRange` 容量截断（保留前 10,000 名），消除无限制膨胀；`LeaderboardServiceTest` 补齐分批清理与容量截断单测。

## 二、改动清单（仅 17 文件，与白名单一致）

1. `sql/01-user-db.sql`
2. `sql/migrations/add-idx-notification-user-id.sql`
3. `verify-service/src/main/java/com/sportverify/verify/controller/VerifyController.java`
4. `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`
5. `verify-service/src/test/java/com/sportverify/verify/controller/VerifyControllerTest.java`
6. `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`
7. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`
8. `user-service/src/test/java/com/sportverify/user/service/NotificationServiceTest.java`
9. `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java`
10. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
11. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
12. `web/src/api/client.ts`
13. `web/src/api/__tests__/client.test.ts`
14. `web/src/pages/admin-appeal.page.vue`
15. `web/src/pages/notifications.page.vue`
16. `work/mailbox/tasks/TASK-244/spec.md`
17. `work/mailbox/tasks/TASK-244/handoff.md`

### 白名单内各改动点核对

| 文件 | 改动点 | 判别式落地 |
| --- | --- | --- |
| `sql/01-user-db.sql` | `notification` 表补齐 `KEY idx_user_id (user_id, id)` | ✅ B17 |
| `add-idx-notification-user-id.sql` | 新建幂等迁移脚本（information_schema.statistics 防重，口径与 add-idx-friendship-user-high 一致） | ✅ B17 |
| `VerifyController.java` | 新增 `GET /api/appeals/pending` → `listPendingAppeals()` | ✅ B15 |
| `VerifyService.java` | 实现 `listPendingAppeals()`（PENDING 过滤 + id DESC） | ✅ B15 |
| `VerifyControllerTest.java` | `GET /api/appeals/pending` 返回待审列表透传单测 | ✅ B15 |
| `NotificationController.java` | 新增 `PATCH /api/notifications/batch-read`（body=List\<Long\> ids） | ✅ B17 |
| `NotificationService.java` | 实现 `batchMarkRead`（非空校验 + 归属/未读条件 UPDATE + 触发回执） | ✅ B17 |
| `NotificationServiceTest.java` | 成功流转 / 幂等忽略已读 / 入参缺失归 0 单测 | ✅ B17 |
| `LeaderboardContributionMapper.java` | 新增 `selectOrphanContributions`（ACTIVE 且 sport_record 无记录）+ `UserMileage.recordId` 字段 | ✅ B16 |
| `LeaderboardService.java` | `settleAndReconcile` 续：孤儿对账回滚 + scan 游标分批清理残留 + `removeRange` 容量截断（前 10,000） | ✅ B16/B18 |
| `LeaderboardServiceTest.java` | 孤儿回滚/幂等/scan 分批/容量截断/不再 range 全量 dump 单测 | ✅ B16/B18 |
| `web/src/api/client.ts` | 导出 `fetchPendingAppeals()`、`batchMarkNotificationsAsRead(ids)` 与 `AppealDTO` | ✅ B15/B17 |
| `web/src/api/__tests__/client.test.ts` | 待审队列与批量已读契约单测 | ✅ B15/B17 |
| `admin-appeal.page.vue` | 待审列表卡片 + 「填入审核」一键填 ID + 初载/终判后自动刷新 | ✅ B15 |
| `notifications.page.vue` | 表格 `row-selection` 多选 + 「批量标记已读」交互 | ✅ B17 |

## 三、门禁验证结果

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测 | `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service -am` | **BUILD SUCCESS**，verify/user/leaderboard 全用例通过（含 B15/B16/B17/B18 新增用例） |
| 前端类型检查 | `cd web && pnpm run type-check` | 0 错误 |
| 前端单测 | `cd web && pnpm test` | 全部通过（4 文件 / 41 用例） |
| 契约门禁 | `bash scripts/verify/mailbox-contract.sh --open=TASK-244 --baseline=a870d1c` | **退出码 0**，判据 A 两件套齐 + 判据 B 清单一致 |
| 红线比对 | `git diff --name-only a870d1c`（含未跟踪） | 恰 17 白名单文件，白名单外零触碰 |

## 四、提交记录

- **C-01 实施笔**：`feat(governance): TASK-244 治理闭环、存储优化与高并发防爆（C-01 实施笔）`（恰 15 个代码/测试/SQL 文件）
- **C-02 台账笔**：`docs(mailbox): TASK-244 治理闭环、存储优化与高并发防爆验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 五、红线复核

- ✅ 白名单外文件零触碰（`git diff --name-only a870d1c` 含未跟踪恰 17 白名单文件；`record-service`、网关、`pom.xml`、`package.json`、`BACKLOG.md`、`PLAN.md`、`HANDOVER.md` 均未动）。
- ✅ 零新外部依赖（仅白名单内增改代码，未改 `package.json` / 根 `pom.xml` / 网关）。
- ✅ 未 push、未建 PR。

## 六、遗留与风险

- B18 的 scan 游标推进基于「上一批末尾 member 是否在后一批」的尽力而为判据：scan 本身无序，多实例结算受锁串行化保护，单实例内此时序不对条件（页面大小 500 的边界批次）下偶漏的残留会在下一轮 10min 结算覆盖收敛；清理是兜底纠偏，非实时强一致。
- B16 孤儿对账在 `selectActiveSummaries` 聚合**前**执行，孤儿行先流转 `ROLLED_BACK`，故本轮 target 已剔除脏里程，ZSet 同步纠偏；并发中他进程已回滚时 `updateStatus` 影响 0 行静默跳过，幂等。
- `idx_user_id (user_id, id)` 对 pageNotifications（`WHERE user_id=? ORDER BY id DESC`）提供精准备序覆盖，消除 history 路径 Using filesort；`read-all`/`batch-read` 仍走 `idx_user_read` 前缀，读多写少演示规模可接受。