# TASK-269 立项任务书（运动徽章与成就体系全链路闭环）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-18 指导亲核三块全 PASS，七门禁全绿，予以封盘）

## 1. 证据链与业务背景（2026-10-18 指导侧）

全仓 54 项技术债、压测基准套件、代码规范清零与 Sentinel 写限流防御加固已全量闭环（CI run `39101746775` 全绿达成）。系统稳定性与微服务韧性已达生产级，正式进入业务特性扩展阶段。
用户已选定**方向一：运动徽章与成就体系（Badges & Achievements）**：
1. **激励机制缺失**：目前平台具备完善的运动记录状态机、连续打卡天数 `myStreak`、运动目标 `GoalService` 以及通知中心，但缺少用户可见的成就勋章墙与正向激励反馈；
2. **零侵入高并发写主干**：经架构评估，徽章判定采用“读时结算 + 增量幂等持久化”模式（Pull-on-read Auto-unlock & Retro-fill），既能对历史记录实现零数据丢失自动追溯点亮，又对 `submitRecord` 高并发写入链路保持 **0 侵入、0 性能损耗**；
3. **社交与个性化外显**：支持用户佩戴心仪勋章并在个人中心与动态流（Feed）外显。

---

## 2. 只改清单（白名单，恰 14 业务/测试/UI + 1 台账 = 15 文件）

严格限制在以下 15 个文件内，严禁触碰白名单外任何文件：

1. `sql/02-record-db.sql`（追加 `user_badge` DDL 定义）
2. `sql/migrations/add-user-badge-table.sql`（新增迁移脚本，必须包含 `USE record_db;`）
3. `record-service/src/test/java/com/sportverify/record/db/SqlMigrationContractTest.java`（断言迁移文件数 `>= 14` 且追加新脚本校验）
4. `record-service/src/main/java/com/sportverify/record/entity/UserBadge.java`（新增实体，表名 `user_badge`）
5. `record-service/src/main/java/com/sportverify/record/enums/BadgeType.java`（新增枚举，9 大勋章定义与元数据）
6. `record-service/src/main/java/com/sportverify/record/dto/BadgeDTO.java`（新增 DTO）
7. `record-service/src/main/java/com/sportverify/record/mapper/UserBadgeMapper.java`（新增 Mapper，继承 BaseMapper）
8. `record-service/src/main/java/com/sportverify/record/service/BadgeService.java`（新增服务类，核心判定、解锁与佩戴）
9. `record-service/src/main/java/com/sportverify/record/controller/BadgeController.java`（新增 REST 控制器）
10. `record-service/src/test/java/com/sportverify/record/service/BadgeServiceTest.java`（新增服务层全量单测）
11. `record-service/src/test/java/com/sportverify/record/controller/BadgeControllerTest.java`（新增控制器契约单测）
12. `web/src/pages/index.page.vue`（个人主页增补「成就勋章墙」卡片与佩戴交互）
13. `web/src/pages/feed.page.vue`（动态流卡片作者昵称旁增补佩戴勋章外显）
14. `web/src/api/client.ts`（增补勋章相关 API 客户端请求方法）
15. `work/mailbox/tasks/TASK-269/handoff.md`（执行交接台账）

---

## 3. 核心设计与实施要求

### R1 数据库表与迁移脚本
- 表结构 `user_badge`（落 `record_db` 单库，与 `sport_goal` 保持相同设计范式）：
  ```sql
  -- 用户勋章与成就表（TASK-269）
  CREATE TABLE IF NOT EXISTS `user_badge` (
      `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
      `user_id`     BIGINT       NOT NULL COMMENT '所属用户ID',
      `badge_code`  VARCHAR(64)  NOT NULL COMMENT '勋章编码',
      `is_equipped` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否佩戴：0 否，1 是',
      `unlocked_at` DATETIME     NOT NULL COMMENT '解锁时间',
      `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
      PRIMARY KEY (`id`),
      UNIQUE KEY `uk_user_badge` (`user_id`, `badge_code`),
      KEY `idx_user_equipped` (`user_id`, `is_equipped`)
  ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='用户勋章与成就表';
  ```
- 在 `sql/02-record-db.sql` 末尾追加上述建表 DDL；
- 新增 `sql/migrations/add-user-badge-table.sql`，必须声明 `USE record_db;`，内容与主 DDL 逐字对齐；
- 在 `SqlMigrationContractTest.java` 中将迁移文件数上限从 13 改为 14，并对 `add-user-badge-table.sql` 执行 DDL 一致性断言（或保持全局扫描通过）。

### R2 领域枚举 `BadgeType`（9 大初始成就）
在 `BadgeType.java` 中声明 9 大核心成就，包含中文名、描述、分类（`MILESTONE` / `STREAK` / `SPECIAL`）、阶梯（`BRONZE` / `SILVER` / `GOLD` / `PLATINUM`）、图标（emoji/字符）、目标数值：
1. `FIRST_STEP`（"初出茅庐", "完成首次有效运动记录", "MILESTONE", "BRONZE", "🥉", 1.0）
2. `TEN_KM_RUN`（"十公里突破", "单次跑步有效里程达到 10 公里", "MILESTONE", "SILVER", "🥈", 10.0）
3. `HALF_MARATHON`（"半程马拉松", "单次跑步有效里程达到 21.0975 公里", "MILESTONE", "GOLD", "🥇", 21.0975）
4. `FULL_MARATHON`（"马拉松大师", "单次跑步有效里程达到 42.195 公里", "MILESTONE", "PLATINUM", "💎", 42.195）
5. `STREAK_7_DAYS`（"七日连胜", "连续打卡天数达到 7 天", "STREAK", "SILVER", "🔥", 7.0）
6. `STREAK_30_DAYS`（"月度恒星", "连续打卡天数达到 30 天", "STREAK", "GOLD", "⭐", 30.0）
7. `HUNDRED_KM_CLUB`（"百公里俱乐部", "历史累计运动总里程突破 100 公里", "MILESTONE", "GOLD", "🏆", 100.0）
8. `CYCLING_MASTER`（"破风达人", "单次骑行有效里程达到 30 公里", "MILESTONE", "SILVER", "🚴", 30.0）
9. `NIGHT_RUNNER`（"夜行使者", "在夜间（21:00-05:00）完成一次 5km 以上有效运动", "SPECIAL", "BRONZE", "🌙", 5.0）

### R3 服务层 `BadgeService` 核心逻辑
- `listMyBadges(Long userId)`：
  1. 查 `user_badge` 获取用户已解锁记录映射（按 `badge_code`）；
  2. 针对未解锁勋章，基于本地 `sportRecordMapper` 查用户已通过记录（`status IN (PASSED, RE_PASSED)`）、`SportRecordService.myStreak(userId)`、`SportRecordService.myStats(userId)` 进行快速比对；
  3. 若判定满足条件，执行 `userBadgeMapper.insert`（捕获 `DuplicateKeyException` 确保绝对幂等），`unlocked_at` 记录为当前时间；
  4. 组装返回全量 9 个 `BadgeDTO`，包含当前进度、目标值、百分比（0..100）、是否已解锁、解锁时间、是否佩戴。
- `wearBadge(Long userId, String badgeCode)`：
  - 校验该勋章是否已解锁，未解锁抛出业务异常 `400 / BADGE_NOT_UNLOCKED`；
  - 在单个事务内，先将用户所有勋章 `is_equipped = 0`，再将指定 `badge_code` 置为 `is_equipped = 1`。
- `unwearBadge(Long userId)`：
  - 将用户所有勋章 `is_equipped = 0`。
- `getEquippedBadge(Long userId)`：
  - 查询用户当前佩戴的勋章并返回（未佩戴返回 null）。

### R4 控制器 `BadgeController`
- `@RequestMapping("/api/records/my/badges")`：
  - `GET ""` -> `listMyBadges(@RequestHeader("X-User-Id") Long userId)`
  - `PUT "/{badgeCode}/wear"` -> `wearBadge(@RequestHeader("X-User-Id") Long userId, @PathVariable String badgeCode)`
  - `PUT "/unwear"` -> `unwearBadge(@RequestHeader("X-User-Id") Long userId)`
- `@RequestMapping("/api/records/users")`：
  - `GET "/{userId}/equipped-badge"` -> `getEquippedBadge(@PathVariable Long userId)`

### R5 前端集成与交互展示
- `web/src/api/client.ts`：增补 `getMyBadges()`、`wearBadge(code)`、`unwearBadge()`、`getUserEquippedBadge(userId)`；
- `web/src/pages/index.page.vue`：
  - 在「我的记录 / 个人统计」卡片下方新增「成就勋章墙」卡片；
  - 网格布局展示 9 枚勋章卡片：已解锁高亮显示，并提供「佩戴」/「卸下」按钮；未解锁置灰并显示进度条；当前已佩戴勋章显示金色徽章标签；
- `web/src/pages/feed.page.vue`：
  - 在动态流卡片的作者名字后方，若作者佩戴了勋章，外显该勋章标签（例如 `[🔥 七日连胜]`）。

### R6 测试守卫
- `BadgeServiceTest`：对 9 大徽章的判定逻辑、首次解锁插入、重复解锁幂等性、未解锁禁止佩戴、佩戴与卸下全流程进行 100% 覆盖单测；
- `BadgeControllerTest`：断言 HTTP 端点响应与错误码；
- `SqlMigrationContractTest`：纯 JVM 契约单测全绿。

---

## 4. 门禁要求

1. **Surefire 测试全量只增不减**：
   - 全仓七模块 `63/79/221/323/203/110/36` 基线总计 1035 测；`record-service` 323 测须净增（新增 `BadgeServiceTest` + `BadgeControllerTest`），全仓总测数稳步上升，0 失败、0 错误、0 跳过；
2. **静态检查 Checkstyle 保持 0 违规**：
   - `mvn-verify.sh --mode=offline --static=record-service` 必须保持 `You have 0 Checkstyle violations`（退出码 0，严禁引入新的 Javadoc 或代码规范违规）；
3. **契约门**：
   - `bash scripts/verify/mailbox-contract.sh --open=TASK-269 --baseline=3b34b81` 实测退出码 0；
4. **Token 门（29 项历史指标）**：
   - 29 项指标改动文件内零命中，C-01 保持不变，C-02 自枚举行严格各 +1；
5. **词面门**：
   - 四 locale 全 `ZERO_HIT`（退出码 1）；
6. **差异与格式检查**：
   - `git diff --check` 输出为空，纯 LF，无 BOM，无尾部空白。

---

## 5. 实施纪律

- 必须分为两次提交：
  - **C-01 提交**：实现业务逻辑、SQL 迁移与测试（恰 14 个文件）；
  - **C-02 提交**：新建 `work/mailbox/tasks/TASK-269/handoff.md`（恰 1 个文件）；
- 中文 commit message 经无 BOM 临时文件以 `git commit -F` 提交；
- 严禁 `git add -A`，封盘前严禁向远程推送。
