# TASK-242: 存储慢查消灭与社交闭环及体验提升——周期榜复合索引补齐、解除好友链路打通与目标空态优化（MEDIUM 档）

> **开工基线**：76b1b47（main 分支，工作树干净，B10-B19 侦察归档与看板更新基线）
> **任务级别**：MEDIUM（存储性能优化 + 核心社交动作闭环 + 前台数据常理与空态体验提升）

## 1. 目标与背景

- **背景**：
  1. **周期榜与定时结算全表扫描慢查（B10：`leaderboard_contribution` 索引缺失）**：
     - 在 `sql/02-record-db.sql` 中，`leaderboard_contribution` 表仅有主键 `(record_id)` 和用户索引 `KEY idx_user (user_id)`；
     - 前台用户在 `leaderboard.page.vue` 查看「今日/本周/本月」周期榜时，调用 `GET /api/leaderboard/range` 触发 `LeaderboardContributionMapper.selectRangeSummaries`，按 `WHERE status = #{status} AND settled_at >= #{start} AND settled_at < #{endExclusive}` 范围聚合，因无 `(status, settled_at)` 索引导致 MySQL 触发全表扫描与临时表排序（Javadoc 明确标记为已知限制技术债挂账）；
     - 后台每 10 分钟定时结算 `settleAndReconcile` 执行 `markSettled`（`UPDATE leaderboard_contribution SET settled_at = ... WHERE status = ? AND settled_at IS NULL`）同样因缺乏该复合索引而进行整表排他锁扫描，极易引发锁冲突。
  2. **核心社交闭环与页面初载断层（B14：好友列表自加载漏调 + 解除好友接口缺失）**：
     - 用户访问 `/friends` 好友页面（`web/src/pages/friends.page.vue`）时，`onMounted` 仅调用了 `loadRequests()`，漏掉了 `loadFriends()`，导致进入页面时「我的好友列表」始终显示「无好友或未刷新」，必须手动点击「刷新列表」才会渲染；
     - 核心社交关系「只进不出」，全仓没有任何解除好友关系（删除好友）的端点、业务逻辑与前端入口，违背社交系统基本常理。
  3. **运动目标数值格式化违和与首屏空态退化（B19：次数显示 `.00` + 30 天 0 里程虚框）**：
     - 首页运动目标卡片（`web/src/pages/index.page.vue`），当目标维度为「次数」（`metric === 1`）时，显示为 `次数：3.00 / 5 次`，次数带无意义的 `.00` 小数点；
     - 当近 30 天累计里程为 0 时（新用户或久未运动），图表渲染出 30 根高度为 1px 的蓝色细线虚框，既无引导文案也无日期刻度，视觉与体验严重退化。
- **目标**：
  1. **B10 存储索引补齐与慢查消灭**：
     - `sql/02-record-db.sql` 为 `leaderboard_contribution` 补充 `KEY idx_status_settled (status, settled_at)`；
     - 新建幂等迁移脚本 `sql/migrations/add-idx-leaderboard-contribution-status-settled.sql`（采用 `information_schema.statistics` 防重）；
  2. **B14 好友社交全链路闭环**：
     - `web/src/pages/friends.page.vue`：挂载时调用 `loadFriends()`；好友表格增加「操作」列，提供「解除好友」按钮与 `a-popconfirm` 二次确认弹窗；
     - `web/src/api/client.ts`：导出 `deleteFriend(targetUserId: number | string)`，打向 `DELETE /user/api/friends/${targetUserId}`；
     - `web/src/api/__tests__/client.test.ts`：补充 `deleteFriend` 路由契约单测；
     - `user-service`：`FriendController` 增加 `DELETE /api/friends/{targetUserId}` 端点；`FriendService` 实现 `deleteFriend(userId, targetUserId)`（支持校验、Redisson 锁保护、规范化删除、幂等安全）；`FriendServiceTest` 补充完整单测；
  3. **B19 前端体验提升**：
     - `web/src/pages/index.page.vue`：当 `metric === 1`（次数）时消除 `.00` 小数点显示；近 30 天累计里程为 0 时展示优雅引导卡片（带去提交记录跳转链接）。

## 2. 修改白名单

严格限定在以下 11 个文件内（恰 9 个代码/测试文件 + 2 个台账文件）：

- 数据库与迁移（共 2 文件）：
  - `sql/02-record-db.sql`
  - `sql/migrations/add-idx-leaderboard-contribution-status-settled.sql`
- 后端接口与测试（共 3 文件）：
  - `user-service/src/main/java/com/sportverify/user/controller/FriendController.java`
  - `user-service/src/main/java/com/sportverify/user/service/FriendService.java`
  - `user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java`
- 前端代码与测试（共 4 文件）：
  - `web/src/api/client.ts`
  - `web/src/api/__tests__/client.test.ts`
  - `web/src/pages/friends.page.vue`
  - `web/src/pages/index.page.vue`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-242/spec.md`
  - `work/mailbox/tasks/TASK-242/handoff.md`

> **红线约束**：
> - 除上述 11 个文件外，其他微服务代码、网关、`package.json`、根 `pom.xml`、`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖。

## 3. 核心设计与判别式要求

### 3.1 B10 周期榜复合索引补齐（sql/02-record-db.sql & 迁移脚本）
- `sql/02-record-db.sql`：在 `leaderboard_contribution` 表结构中添加：
  ```sql
  KEY `idx_status_settled` (`status`, `settled_at`)
  ```
- `sql/migrations/add-idx-leaderboard-contribution-status-settled.sql`：
  ```sql
  USE record_db;

  SET @stmt := IF(
      (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'leaderboard_contribution' AND index_name = 'idx_status_settled') = 0,
      'ALTER TABLE `leaderboard_contribution` ADD INDEX `idx_status_settled` (`status`, `settled_at`)',
      'SELECT ''leaderboard_contribution.idx_status_settled 已存在，跳过'' AS msg');
  PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  ```

### 3.2 B14 好友社交全链路闭环（后端 FriendController/FriendService + 前端 friends.page.vue/client.ts）
- **FriendController**：
  - 新增端点 `@DeleteMapping("/{targetUserId}")`；
  - 调用 `resolveUserId(request, null)` 获取当前操作者 `userId`；
  - 调用 `friendService.deleteFriend(userId, targetUserId)`；
  - 返回 `Result.ok()` 或 `Result.success()`。
- **FriendService**：
  - 入参校验：`userId` 与 `targetUserId` 均非空；`userId.equals(targetUserId)` 抛出参数异常（不能与自己解除关系）；
  - 锁保护：取 `low = Math.min(userId, targetUserId)`, `high = Math.max(userId, targetUserId)`，获取 Redisson 锁 `lock:friend:low_high`；
  - 锁内执行：`friendshipMapper.delete(new LambdaQueryWrapper<Friendship>().eq(Friendship::getUserLow, low).eq(Friendship::getUserHigh, high))`；
  - 幂等性：即使数据库中不存在该关系，也返回成功（影响 0 行不抛异常）；
  - 记录 info 日志。
- **FriendServiceTest**：
  - 补充 `deleteFriend_success`（正常解除关系）；
  - 补充 `deleteFriend_idempotent_whenNotExist`（不存在关系时幂等成功）；
  - 补充 `deleteFriend_self_throwsException`（不能解除自己）。
- **web/src/api/client.ts**：
  - 导出 `deleteFriend(targetUserId: number | string): Promise<Result<void>>`，打向 `DELETE /user/api/friends/${targetUserId}`。
- **web/src/api/__tests__/client.test.ts**：
  - 补充 `deleteFriend` 契约测试，断言向 `/user/api/friends/123` 发起 DELETE 请求。
- **web/src/pages/friends.page.vue**：
  - `onMounted` 钩子中，同时调用 `loadRequests()` 和 `loadFriends()`；
  - `friendColumns` 增加「操作」列（`{ title: '操作', key: 'actions', width: 120 }`）；
  - 表格 `#bodyCell` 渲染解除好友操作：使用 `a-popconfirm`（提示「确定解除该好友关系吗？」），点击后调用 `onDeleteFriend(record.userId)`；解除成功后重新拉取 `loadFriends()`。

### 3.3 B19 前端目标数值格式化与空态退化（index.page.vue）
- **运动目标次数显示**：
  - 当 `g.metric === 1` 时，已达成值消除 `.00` 小数显示（例如显示为 `3 / 5 次` 而非 `3.00 / 5 次`，距离仍保留两位小数如 `12.50 km`）；
- **近 30 天每日里程空态**：
  - 当近 30 天累计总里程为 0 时（`dailyBars.every(b => b.distance === 0)`），不再渲染 30 根 1px 细线虚框；
  - 渲染优雅的空态指引卡片（例如「近 30 天暂无运动记录，开始记录第一次运动吧！」），并提供直达「去提交运动记录 &rarr;」链接（路由直达 `/record-submit`）。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. 后端验证：
     - `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl user-service -am`（BUILD SUCCESS，全用例通过）；
  2. 前端验证（在 `web` 目录下执行）：
     - `pnpm run type-check`：0 错误；
     - `pnpm test`：全部用例全绿通过；
  3. 契约门禁：
     - `bash scripts/verify/mailbox-contract.sh --open=TASK-242 --baseline=76b1b47`（退出码 0，两件套齐备且白名单一致）；
  4. `git diff --name-only 76b1b47` 严格全等于上述 11 个白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(perf): TASK-242 周期榜复合索引补齐、解除好友链路打通与目标空态体验提升（C-01 实施笔）`（恰 9 个代码与测试文件）
  - C-02 台账笔：`docs(mailbox): TASK-242 存储慢查消灭与社交闭环及体验提升验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 5. 验收与门禁回填

- **后端单元测试**：✅ `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl user-service -am` → BUILD SUCCESS（user-service 183 用例全绿，含 FriendServiceTest 36 用例：新增 deleteFriend_success / _idempotent_whenNotExist / _self_throwsException / 反向归一 / 空参拦截）。
- **前端类型门禁**：✅ `cd web && pnpm run type-check` → 0 错误。
- **前端单测门禁**：✅ `cd web && pnpm test` → 4 文件 39 用例全绿（client.test.ts 22 用例，含 deleteFriend 契约单测）。
- **契约门禁**：✅ `bash scripts/verify/mailbox-contract.sh --open=TASK-242 --baseline=76b1b47` → 退出码 0，判据 A 两件套齐 + 判据 B 清单一致。
- **提交记录**：
  - C-01 实施笔：`feat(perf): TASK-242 周期榜复合索引补齐、解除好友链路打通与目标空态体验提升（C-01 实施笔）`
  - C-02 台账笔：`docs(mailbox): TASK-242 存储慢查消灭与社交闭环及体验提升验收与台账闭环（C-02 台账笔）`
- **红线复核**：✅ `git diff --name-only 76b1b47` 恰 11 白名单文件；白名单外零触碰、零新依赖、未 push、未建 PR。

> 台账详情见 `work/mailbox/tasks/TASK-242/handoff.md`。
