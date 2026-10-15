# TASK-242 台账回传（handoff.md）

> **任务**：TASK-242 周期榜复合索引补齐、解除好友链路打通与运动目标空态体验提升（MEDIUM 档）
> **实现工程师**：TASK-242 Implementer
> **开工基线**：`76b1b47`（main，工作树干净）
> **状态**：交付完成，门禁全绿

## 一、交付概述

- **B10 存储优化**：`leaderboard_contribution` 新增复合索引 `idx_status_settled (status, settled_at)`，消灭周期榜范围聚合与定时结算 `markSettled` 的全表扫描/临时表排序/排他锁扫描。
- **B14 社交闭环**：好友页面 `onMounted` 补齐 `loadFriends()`；新增「解除好友」远端链路（Controller DELETE 端点 → Service `deleteFriend` 幂等+防自删+Redisson 锁保护 → 前端 `deleteFriend` API + `a-popconfirm` 确认 + 列表刷新）。
- **B19 体验提升**：运动目标次数维度消除 `.00` 小数；近 30 天累计里程为 0 时渲染空态引导卡片并直达「去提交运动记录」。

## 二、改动清单（仅 11 文件，与白名单一致）

1. `sql/02-record-db.sql`
2. `sql/migrations/add-idx-leaderboard-contribution-status-settled.sql`
3. `user-service/src/main/java/com/sportverify/user/controller/FriendController.java`
4. `user-service/src/main/java/com/sportverify/user/service/FriendService.java`
5. `user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java`
6. `web/src/api/client.ts`
7. `web/src/api/__tests__/client.test.ts`
8. `web/src/pages/friends.page.vue`
9. `web/src/pages/index.page.vue`
10. `work/mailbox/tasks/TASK-242/spec.md`
11. `work/mailbox/tasks/TASK-242/handoff.md`

### 白名单内各改动点核对

| 文件 | 改动点 | 判别式落地 |
| --- | --- | --- |
| `sql/02-record-db.sql` | `leaderboard_contribution` 增加 `KEY idx_status_settled (status, settled_at)` | ✅ 与迁移脚本同名索引 |
| `sql/migrations/add-idx-leaderboard-contribution-status-settled.sql` | 幂等迁移（`information_schema.statistics` 防重） | ✅ 与 `add-idx-friendship-user-high.sql` 同口径 |
| `FriendController.java` | `@DeleteMapping("/{targetUserId}")` → `resolveUserId(request,null)` → `deleteFriend` → `Result.success()` | ✅ |
| `FriendService.java` | `deleteFriend`：空参校验 + 防自删 + `lock:friend:low_high` + 规范化删除 + 幂等成功 + info 日志 | ✅ |
| `FriendServiceTest.java` | `deleteFriend_success` / `_idempotent_whenNotExist` / `_self_throwsException` 及反向归一/空参用例 | ✅ 36 用例全绿 |
| `web/src/api/client.ts` | 导出 `deleteFriend(targetUserId)` → `DELETE /user/api/friends/${targetUserId}` | ✅ |
| `web/src/api/__tests__/client.test.ts` | 契约单测：断言 DELETE `/user/api/friends/123` | ✅ |
| `web/src/pages/friends.page.vue` | `onMounted` 补 `loadFriends()`；「操作」列 `a-popconfirm`「解除好友」；成功刷新 | ✅ |
| `web/src/pages/index.page.vue` | `metric===1` 消除 `.00`；30 天 0 里程空态卡片 → `/record-submit` | ✅ |

## 三、门禁验证结果

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测 | `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl user-service -am` | **BUILD SUCCESS**（user-service 183 用例全绿，含 FriendServiceTest 36） |
| 前端类型检查 | `cd web && pnpm run type-check` | **0 错误** |
| 前端单测 | `cd web && pnpm test` | **4 文件 39 用例全绿**（client.test.ts 22 用例含 deleteFriend 契约） |
| 契约门禁 | `bash scripts/verify/mailbox-contract.sh --open=TASK-242 --baseline=76b1b47` | **退出码 0**，判据 A 两件套齐 + 判据 B 清单一致 |
| 红线比对 | `git diff --name-only 76b1b47` | **恰 11 白名单文件**，白名单外零触碰 |

## 四、提交记录

- **C-01 实施笔**：`feat(perf): TASK-242 周期榜复合索引补齐、解除好友链路打通与目标空态体验提升（C-01 实施笔）`（恰 9 个代码/测试文件）
- **C-02 台账笔**：`docs(mailbox): TASK-242 存储慢查消灭与社交闭环及体验提升验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 五、红线复核

- ✅ 白名单外文件零触碰（`git diff --name-only 76b1b47` 恰 11 白名单文件）。
- ✅ 零新外部依赖（仅在白名单文件内增改代码，未改 `package.json` / 根 `pom.xml`）。
- ✅ 未 push、未建 PR。

## 六、遗留与风险

- `deleteFriend` 采用规范化 `(user_low,user_high)` 删除，任一方向解除即删除关系（幂等）。未级联清除历史好友申请的 ACCEPTED 记录（申请单状态机保真，删除关系不回溯改申请单，属既有口径，不扩容）。
- 二次确认弹窗仅前端 `a-popconfirm` 层防护，后端以防自删+幂等兜底，未引入额外最终确认状态位（符合白名单内最小改动）。