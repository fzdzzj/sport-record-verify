# TASK-240: 存储性能与网络吞吐双重优化——friendship表user_high索引补齐与动态流Feed点赞态批量下推（MEDIUM 档）

> **开工基线**：d2c6d62（main 分支，工作树干净，TASK-239 封盘笔与优化看板登记）
> **任务级别**：MEDIUM（存储与网络双重性能优化：好友关系表索引消除全表扫描 + Feed 点赞批量下推单次 RTT）

## 1. 目标与背景

- **背景**：
  1. **存储层慢查风险（B6：Friendship 表 user_high 索引缺失）**：
     `user-service` 中 `FriendService.listFriendIds(userId)` 与 `listFriends(userId, ...)` 查询好友关系时，构造条件为 `WHERE user_low = ? OR user_high = ?`。当前 `sql/01-user-db.sql` 中 `friendship` 表的主键为 `PRIMARY KEY (user_low, user_high)`，仅能覆盖 `user_low` 的前缀查找；在 `user_high` 维度缺少独立索引，导致 MySQL 在处理 OR 查询时无法进行高效的索引合并（index merge），随着关系数增加将退化为全表扫描或扫尽全主键。
  2. **网络吞吐瓶颈（B7：动态流 Feed 点赞态 20 次高频并发 HTTP 请求）**：
     前端 `web/src/pages/feed.page.vue:153-160` 在加载好友动态流列表（默认 20 条）后，通过 `Promise.allSettled(ids.map((id) => getRecordLike(id)))` 向后端连续发起 20 次独立的 HTTP 请求（`GET /record/api/records/{id}/like`）。每一个请求均需走网关转发、Filter 校验与独立的 Redis `SISMEMBER` / `GET` 查询，导致前端网络拥塞、服务端连接资源与往返 RTT 被放大 20 倍。
- **目标**：
  1. **B6 存储索引补齐**：
     - 在 `sql/01-user-db.sql` 中为 `friendship` 表补齐 `KEY idx_user_high (user_high)`；
     - 新建幂等迁移脚本 `sql/migrations/add-idx-friendship-user-high.sql`（采用 `information_schema.statistics` 条件判断模式，供存量库安全执行）；
  2. **B7 服务端点赞批量下推与 Redis Pipeline**：
     - `LikeController.java` 新增批量查询端点 `POST /api/records/likes/batch`，入参接收 `List<Long> recordIds`，返回 `Result<List<LikeDTO>>`；
     - `RecordLikeService.java` 实现 `getLikesBatch(List<Long> recordIds, Long userId)`：
       - 空值与空列表安全返回 `List.of()`；
       - 对传入 `recordIds` 去重保序；
       - 权限与可见性保护：加载记录并经 `sportRecordService.assertCanView` 校验，对不存在或不可见的记录安全过滤/跳过，绝不因单条记录异常导致整批 Feed 报错；
       - 单次往返（RTT=1）：使用 `stringRedisTemplate.executePipelined` 批量查询点赞数（`like:count:{id}`）与已赞状态（`SISMEMBER like:record:{id}:users {userId}`）；
       - 缓存穿透兜底：若某条记录的 Redis 计数为 null，回退既有 `readCount(id)` 逻辑补齐；
  3. **B7 前端单请求直出**：
     - `web/src/api/client.ts` 导出 `getLikesBatch(recordIds: number[]): Promise<Result<LikeDTO[]>>`，请求 `POST /record/api/records/likes/batch`；
     - `web/src/pages/feed.page.vue` 重构 `loadLikes()`：调用 `getLikesBatch(ids)`，单次网络请求直接回填 `likeMap`，捕获异常降级保证页面可用性；
  4. **测试与契约全覆盖**：
     - `RecordLikeServiceTest.java` 增加批量点赞查询的单元测试（覆盖多条记录批量返回、Pipeline 交互断言、未登录/未点赞/已点赞混合、缓存未命中兜底、不可视记录过滤等）；
     - `web/src/api/__tests__/client.test.ts` 增加 `getLikesBatch` 请求方法与路由契约测试；
     - 既有单元测试与契约门禁全绿通过。

## 2. 修改白名单

严格限定在以下 10 个文件内：

- 存储与数据库变更（共 2 文件）：
  - `sql/01-user-db.sql`
  - `sql/migrations/add-idx-friendship-user-high.sql`
- 后端记录服务（共 3 文件）：
  - `record-service/src/main/java/com/sportverify/record/controller/LikeController.java`
  - `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`
  - `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`
- 前端交互与客户端（共 3 文件）：
  - `web/src/api/client.ts`
  - `web/src/api/__tests__/client.test.ts`
  - `web/src/pages/feed.page.vue`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-240/spec.md`
  - `work/mailbox/tasks/TASK-240/handoff.md`

> **红线约束**：
> - 除上述 10 个文件外，其他微服务代码、网关、`package.json`、根 `pom.xml`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖（复用既有 Spring Data Redis、Redisson、Axios 体系）。

## 3. 核心设计与判别式要求

### 3.1 friendship 表索引与幂等迁移（sql/01-user-db.sql & sql/migrations/）

- **sql/01-user-db.sql**：
  在 `friendship` 建表语句中增加 `KEY idx_user_high (user_high)`：
  ```sql
  CREATE TABLE IF NOT EXISTS `friendship` (
      `user_low`   BIGINT   NOT NULL COMMENT '较小用户ID',
      `user_high`  BIGINT   NOT NULL COMMENT '较大用户ID',
      `created_at` DATETIME DEFAULT NULL COMMENT '建立时间',
      PRIMARY KEY (`user_low`, `user_high`),
      KEY `idx_user_high` (`user_high`),
      CONSTRAINT `ck_friendship_low_lt_high` CHECK (`user_low` < `user_high`)
  ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4 COMMENT ='好友关系表（user_low < user_high）';
  ```
- **sql/migrations/add-idx-friendship-user-high.sql**：
  对齐既有迁移规范（如 `add-idx-record-status.sql`），面向存量库采用 `information_schema.statistics` 幂等模式：
  ```sql
  USE user_db;

  SET @stmt := IF(
      (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'friendship' AND index_name = 'idx_user_high') = 0,
      'ALTER TABLE `friendship` ADD INDEX `idx_user_high` (`user_high`)',
      'SELECT ''friendship.idx_user_high 已存在，跳过'' AS msg');
  PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  ```

### 3.2 批量点赞查询端点与 Pipeline 聚合（LikeController & RecordLikeService）

- **LikeController.java**：
  ```java
  /**
   * 批量查询点赞状态（TASK-240：Feed 动态流批量下推，单次往返获取多条记录点赞态与计数）。
   */
  @PostMapping("/likes/batch")
  public Result<List<LikeDTO>> getLikesBatch(@RequestBody List<Long> recordIds,
                                             @RequestParam(value = "userId", required = false) Long userId,
                                             HttpServletRequest request) {
      return Result.success(recordLikeService.getLikesBatch(recordIds, resolveUserId(request, userId)));
  }
  ```
- **RecordLikeService.java**：
  1. 入参校验：`recordIds == null || recordIds.isEmpty()` 直接返回 `Collections.emptyList()`；
  2. 去重过滤：`List<Long> distinctIds = recordIds.stream().filter(Objects::nonNull).distinct().toList();`；
  3. 可见性与记录过滤：对每个 id 获取 `SportRecord record = sportRecordMapper.selectById(id)`，若 record 存在，调用 `sportRecordService.assertCanView(record, userId)`。捕获 `BizException` 或校验失败则安全跳过，收集所有通过校验的 `authorizedRecords`；
  4. Redis Pipeline 单次往返：
     若 `authorizedRecords` 为空，返回 `List.of()`；
     通过 `stringRedisTemplate.executePipelined` 批量查询每个授权记录的 `COUNT_KEY_PREFIX + id`，以及当 `userId != null` 时查询 `usersKey(id)` 的 `sIsMember`；
     每个记录对应 1 个或 2 个响应项，按顺序解包。若 count 缓存项为 null，回退 `readCount(recordId)` 补齐；
     构造 `LikeDTO.of(recordId, count, liked)` 返回列表。

### 3.3 前端 API 封装与 Feed 单请求重构（client.ts & feed.page.vue）

- **web/src/api/client.ts**：
  ```typescript
  export async function getLikesBatch(recordIds: number[]): Promise<Result<LikeDTO[]>> {
    return api.post('/record/api/records/likes/batch', recordIds)
  }
  ```
- **web/src/pages/feed.page.vue**：
  重构 `loadLikes()` 函数：
  ```typescript
  /** 列表加载完毕后异步批量拉取当前页可见记录的点赞态；单次请求下推，失败不影响整页渲染与列表可用性 */
  async function loadLikes() {
    const ids = rows.value.map((r) => r.id)
    if (ids.length === 0) return
    try {
      const res = await getLikesBatch(ids)
      if (res.data && Array.isArray(res.data)) {
        res.data.forEach((item) => {
          likeMap.value[item.recordId] = { liked: item.liked, likeCount: item.likeCount }
        })
      }
    } catch {
      // 批量点赞态查询失败静默跳过（容错降级）
    }
  }
  ```

### 3.4 单元测试适配与强化

- **RecordLikeServiceTest.java**：
  - 新增测试用例 `getLikesBatch_returnsBatchLikesInSinglePipeline`：打桩 `executePipelined`，验证多条记录在一次调用中正确返回点赞数与 liked 状态；
  - 新增测试用例 `getLikesBatch_filtersInaccessibleRecords_andHandlesCacheMiss`：验证非公开记录在鉴权失败时被安全过滤，且 count 缺失时触发 DB 回填；
- **client.test.ts**：
  - 新增测试用例 `getLikesBatch hits POST /record/api/records/likes/batch with payload`：验证请求路径、方法及 payload 正确性。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am`：全部用例全绿通过，0 错误 0 失败；
  2. 前端验证（在 `web` 目录下执行）：
     - `pnpm run type-check`：0 错误；
     - `pnpm test`：32+ 用例全绿；
  3. `bash scripts/verify/mailbox-contract.sh --open=TASK-240 --baseline=d2c6d62`：退出码 0，两件套齐备且白名单一致；
  4. `git diff --name-only d2c6d62` 严格全等于上述 10 个白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(perf): TASK-240 存储索引补齐与Feed点赞态批量下推（C-01 实施笔）`（恰 8 个代码/SQL/前端文件）
  - C-02 台账笔：`docs(mailbox): TASK-240 存储与网络性能优化验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 5. 验收与门禁回填

- **Maven 门禁（已回填）**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am` → BUILD SUCCESS，record-service 46 用例全绿（0 失败 0 错误，含新增 getLikesBatch 批量用例）。
- **前端门禁（已回填）**：`pnpm run type-check` → 0 错误；`pnpm test` → 33 用例全绿。
- **契约门禁（已回填）**：`scripts/verify/mailbox-contract.sh --open=TASK-240 --baseline=d2c6d62` → 退出码 0，判据 A 两件套齐备 + 判据 B 改动集与改动清单一致。
- **提交（已回填）**：C-01 实施笔 `feat(perf): TASK-240 存储索引补齐与Feed点赞态批量下推（C-01 实施笔）`：`86be2d8`（8 实施文件）；C-02 台账笔为本 handoff 所在提交（`docs(mailbox)`，自指哈希，实际以 `git log` 为准）。
- **红线复核（已回填）**：`git diff --name-only d2c6d62` 严格全等于 10 个白名单文件；零新依赖；未 push、未建 PR。
