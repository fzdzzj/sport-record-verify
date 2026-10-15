# TASK-244: 治理闭环、存储优化与高并发防爆——申诉待审列表驱动、通知索引慢查消除与批量已读、总榜Redis内存防爆与定时结算对账补偿（MEDIUM 档）

> **开工基线**：a870d1c（main 分支，工作树干净，TASK-244 方案 C 立项准备基线）
> **任务级别**：MEDIUM（管理治理闭环 + 消息中心索引慢查优化与批量已读 + 总榜 Redis 内存防爆与孤儿贡献离线对账补偿）

## 1. 目标与背景

- **背景**：
  1. **申诉管理待办闭环缺失（B15）**：
     - 当前 `web/src/pages/admin-appeal.page.vue` 仅有手动输入申诉 ID 的表单，管理员无法直观获知当前有哪些处于待审状态的申诉单，必须依靠盲打或线下传达 ID；
     - `verify-service` 仅暴露 `POST /api/appeals/{id}/review` 终判接口，缺乏查询待审申诉队列的端点。
  2. **记录删除后榜单里程残留与离线自愈缺失（B16）**：
     - 在 `record-service` 的 `SportRecordService.deleteRecord` 中，由于网络偶发抖动或微服务异常，Feign 调用 `leaderboardApi.rollbackContribution(recordId)` 失败时仅记录 WARN 日志，物理记录已被删除但 `leaderboard_contribution` 仍残留 `status = ACTIVE`，导致榜单里程长期处于脏态；
     - `leaderboard-service` 的定时结算任务 `settleAndReconcile` 虽有对账逻辑，但仅根据 `leaderboard_contribution` 自身 ACTIVE 状态汇总，未感知已被删除记录的孤儿贡献。
  3. **通知中心分页慢查与批量标记缺失（B17）**：
     - `sql/01-user-db.sql` 中 `notification` 表仅有 `KEY idx_user_read (user_id, is_read, id)`；
     - `NotificationService.pageNotifications` 查询为 `WHERE user_id = ? ORDER BY id DESC`，因查询条件中跳过了 `is_read` 字段，MySQL 无法有效利用索引直接完成排序，执行计划出现 `Using filesort` 慢排序；
     - 后端 `NotificationController` 仅有单条已读和全部已读，缺乏按指定 ID 集合批量已读的端点；前端 `notifications.page.vue` 表格缺少行多选复选框，用户无法选择多条批量标已读。
  4. **总榜 Redis 内存防爆与结算 JVM 倾倒隐患（B18）**：
     - `LeaderboardService.settleAndReconcile` 在定时结算清理残留成员时，执行 `stringRedisTemplate.opsForZSet().range(OVERALL_ZSET_KEY, 0, -1)` 将总榜全量成员一次性加载至 JVM 堆内存，随着用户规模扩大极易引发严重的 GC 停顿与 OOM；
     - `OVERALL_ZSET_KEY` 缺乏最大容量控制与裁剪机制，随着时间推移 Redis 内存无限制膨胀。

- **目标**：
  1. **B15 申诉待审闭环**：
     - `verify-service` 增加 `GET /api/appeals/pending` 接口，按 `id DESC` 返回所有 `PENDING` 状态申诉；
     - 前端 `admin-appeal.page.vue` 增加「待审核申诉列表」卡片与表格，支持一键填入表单或快捷处理，彻底终结盲打 ID。
  2. **B16 记录删除与榜单回撤自动对账自愈**：
     - 在 `leaderboard-service` 的定时结算 `settleAndReconcile` 中引入孤儿贡献对账补偿：通过 `LeaderboardContributionMapper` 识别处于 `ACTIVE` 状态但 `sport_record` 表中记录已不存在的孤儿贡献行，将其自动置为 `ROLLED_BACK` 终态，并从汇总及 ZSet 中剔除，实现跨服务删除失败后的定时离线自愈。
  3. **B17 通知中心索引优化与批量标记**：
     - `sql/01-user-db.sql` 为 `notification` 表补齐 `KEY idx_user_id (user_id, id)` 复合索引并编写幂等迁移脚本，彻底消除 `Using filesort`；
     - 后端 `NotificationController` 增加 `PATCH /api/notifications/batch-read`（入参 `List<Long> ids`），`NotificationService` 实现批量更新并触发回执；
     - 前端 `notifications.page.vue` 开启表格行多选（`row-selection`），提供「批量标记已读」按钮并打通交互。
  4. **B18 总榜 Redis 内存防爆与倾倒重构**：
     - `LeaderboardService.settleAndReconcile` 重构 `range(0,-1)` 逻辑：采用 `scan(...)` 游标分批遍历比对并清理无 ACTIVE 贡献的残留成员，消除全量 dump 进 JVM 堆的风险；
     - 增加总榜最大保留数量裁剪机制（如保留前 10,000 名），防止未活跃成员长期堆积占用 Redis 内存。

## 2. 修改白名单

严格限定在以下 17 个文件内（恰 15 个代码/测试/SQL 文件 + 2 个台账文件）：

- 数据库与 SQL 迁移（共 2 文件）：
  - `sql/01-user-db.sql`
  - `sql/migrations/add-idx-notification-user-id.sql`
- 后端 verify-service（共 3 文件）：
  - `verify-service/src/main/java/com/sportverify/verify/controller/VerifyController.java`
  - `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`
  - `verify-service/src/test/java/com/sportverify/verify/controller/VerifyControllerTest.java`
- 后端 user-service（共 3 文件）：
  - `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`
  - `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`
  - `user-service/src/test/java/com/sportverify/user/service/NotificationServiceTest.java`
- 后端 leaderboard-service（共 3 文件）：
  - `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java`
  - `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
  - `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
- 前端 web（共 4 文件）：
  - `web/src/api/client.ts`
  - `web/src/api/__tests__/client.test.ts`
  - `web/src/pages/admin-appeal.page.vue`
  - `web/src/pages/notifications.page.vue`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-244/spec.md`
  - `work/mailbox/tasks/TASK-244/handoff.md`

> **红线约束**：
> - 除上述 17 个文件外，`record-service`、网关、`package.json`、根/子 `pom.xml`、`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖。

## 3. 核心设计与判别式要求

### 3.1 B15 申诉待审队列与管理后台表格驱动闭环（verify-service + web）
- **VerifyController**：
  新增端点：
  ```java
  @GetMapping("/pending")
  public Result<List<AppealDTO>> listPendingAppeals() {
      return Result.success(verifyService.listPendingAppeals());
  }
  ```
  外部经网关 `/admin/api/appeals/pending`（网关 StripPrefix=1 路由至 `/api/appeals/pending`）访问。
- **VerifyService**：
  实现 `listPendingAppeals()`：
  ```java
  public List<AppealDTO> listPendingAppeals() {
      List<Appeal> list = appealMapper.selectList(new LambdaQueryWrapper<Appeal>()
              .eq(Appeal::getStatus, AppealStatus.PENDING.getCode())
              .orderByDesc(Appeal::getId));
      return list.stream().map(this::toDto).toList();
  }
  ```
- **VerifyControllerTest**：
  补充 `GET /api/appeals/pending` 返回待审列表的控制器单测。
- **前端 client.ts / client.test.ts**：
  - 导出 `fetchPendingAppeals(): Promise<AppealDTO[]>`，请求 `/admin/api/appeals/pending`；
  - 补充路由契约测试。
- **前端 admin-appeal.page.vue**：
  - 增加待审申诉卡片与表格：展示申诉ID、记录ID、申诉人、申诉理由、提交时间、操作；
  - 操作列提供「填入审核」按钮，点击自动将申诉单 ID 填入表单输入框，并支持手动刷新待审列表；终判成功后自动重新加载待审列表。

### 3.2 B16 记录删除与榜单回撤自动对账补偿自愈（leaderboard-service）
- **LeaderboardContributionMapper**：
  新增查询与清理已删除记录的孤儿贡献：
  ```java
  @Select("SELECT lc.record_id AS recordId, lc.user_id AS userId, lc.distance AS totalDistance " +
          "FROM leaderboard_contribution lc " +
          "WHERE lc.status = #{status} " +
          "AND NOT EXISTS (SELECT 1 FROM sport_record sr WHERE sr.id = lc.record_id)")
  List<UserMileage> selectOrphanContributions(@Param("status") Integer status);
  ```
- **LeaderboardService.settleAndReconcile**：
  - 在结算聚合前或聚合后，执行孤儿贡献对账：
    ```java
    List<UserMileage> orphans = contributionMapper.selectOrphanContributions(ContributionStatus.ACTIVE.getCode());
    for (UserMileage orphan : orphans) {
        int rows = contributionMapper.updateStatus(orphan.getRecordId(),
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode());
        if (rows > 0) {
            log.info("定时结算发现已删除记录的孤儿贡献，自动对账回滚：recordId={}, userId={}", orphan.getRecordId(), orphan.getUserId());
        }
    }
    ```
  - 将孤儿贡献流转至 `ROLLED_BACK` 终态后，随本轮 `selectActiveSummaries` 的最新数据重新对账覆盖 ZSet，彻底自愈 Feign 抖动遗留的脏里程。
- **LeaderboardServiceTest**：
  - 补充对账清理孤儿贡献并更新状态为 ROLLED_BACK 的单测。

### 3.3 B17 通知中心索引优化与批量标记已读（sql + user-service + web）
- **sql/01-user-db.sql**：
  在 `notification` 表定义中补充复合索引：
  ```sql
  KEY `idx_user_id` (`user_id`, `id`)
  ```
- **sql/migrations/add-idx-notification-user-id.sql**：
  新建幂等迁移脚本，使用 `information_schema.statistics` 检查防重，语法与口径与既有迁移脚本严格一致。
- **NotificationController**：
  新增端点：
  ```java
  @PatchMapping("/batch-read")
  public Result<Integer> batchMarkRead(
          @RequestBody List<Long> ids,
          HttpServletRequest request) {
      Long resolved = resolveUserId(request, null);
      int updated = notificationService.batchMarkRead(resolved, ids);
      return Result.success(updated);
  }
  ```
- **NotificationService**：
  实现 `batchMarkRead(Long userId, Collection<Long> ids)`：
  - 校验非空（空则返回 0）；
  - `notificationMapper.update` 带 `userId` 与 `in(Notification::getId, ids)` 及 `is_read = 0` 条件，更新 `is_read = 1` 与 `read_at = now`；
  - 若更新行数大于 0，调用 `publishReceipt(NotificationReadReceipt.all(userId, now))` 发布回执。
- **NotificationServiceTest**：
  - 增加 `batchMarkRead` 成功流转未读、幂等忽略已读以及越权隔离断言单测。
- **前端 client.ts / client.test.ts**：
  - 导出 `batchMarkNotificationsAsRead(ids: number[]): Promise<number>`，`PATCH /user/api/notifications/batch-read`；
  - 补充契约单测。
- **前端 notifications.page.vue**：
  - `a-table` 增加 `:row-selection="{ selectedRowKeys: selectedIds, onChange: onSelectChange }"`；
  - 表格上方增加「批量标记已读」按钮（`:disabled="selectedIds.length === 0"`，显示选中数量）；
  - 成功后清空选中项并刷新列表及未读计数。

### 3.4 B18 总榜 Redis 内存防爆与倾倒重构（leaderboard-service）
- **LeaderboardService.settleAndReconcile**：
  - 将 `range(OVERALL_ZSET_KEY, 0, -1)` 重构为基于 `opsForZSet().scan(...)` 的游标分批迭代（批次大小如 500），逐批比对 `target` 中是否还存在该成员；
  - 收集无 ACTIVE 贡献的残留成员分批调用 `opsForZSet().remove(...)` 移除；
  - 容量保护：在结算完成阶段，执行总榜容量截断保护（如 `opsForZSet().removeRange(OVERALL_ZSET_KEY, 0, -10001)`），确保 `OVERALL_ZSET_KEY` 仅保留前 10,000 名用户，防止历史未活跃用户无限累积撑爆 Redis 内存。
- **LeaderboardServiceTest**：
  - 补充重构后的分批清理残留与容量上限裁剪的单元测试。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. 后端验证：
     - `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service -am`（BUILD SUCCESS，全用例通过）；
  2. 前端验证（在 `web` 目录下执行）：
     - `pnpm run type-check`：0 错误；
     - `pnpm test`：全部用例全绿通过；
  3. 契约门禁：
     - `bash scripts/verify/mailbox-contract.sh --open=TASK-244 --baseline=a870d1c`（退出码 0，两件套齐备且白名单一致）；
  4. `git diff --name-only a870d1c` 严格全等于上述 17 个白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(governance): TASK-244 治理闭环、存储优化与高并发防爆（C-01 实施笔）`（恰 15 个代码与测试文件）
  - C-02 台账笔：`docs(mailbox): TASK-244 治理闭环、存储优化与高并发防爆验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 5. 验收与门禁回填（待实施回传）

> 由 TASK-244 Implementer 实施完成后回填。

- **后端单元测试**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service -am` → BUILD SUCCESS，verify/user/leaderboard 三服务全用例通过（含 B15/B16/B17/B18 新增用例）。
- **前端类型门禁**：`cd web && pnpm run type-check` → 0 错误。
- **前端单测门禁**：`cd web && pnpm test` → 全部通过（4 文件 / 41 用例）。
- **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-244 --baseline=a870d1c` → 退出码 0，判据 A 两件套齐 + 判据 B 清单一致。
- **提交记录**：
  - C-01 实施笔：`feat(governance): TASK-244 治理闭环、存储优化与高并发防爆（C-01 实施笔）`（恰 15 个代码/测试/SQL 文件）
  - C-02 台账笔：`docs(mailbox): TASK-244 治理闭环、存储优化与高并发防爆验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）
- **红线复核**：`git diff --name-only a870d1c`（含未跟踪）恰 17 白名单文件，白名单外零触碰；零新外部依赖；未 push、未建 PR。

> 台账详情见 `work/mailbox/tasks/TASK-244/handoff.md`。
