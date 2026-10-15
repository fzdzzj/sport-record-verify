# TASK-240 回传台账（C-02 台账笔）

> **任务**：存储性能与网络吞吐双重优化——friendship 表 user_high 索引补齐与动态流 Feed 点赞态批量下推（MEDIUM 档）
> **开工基线**：d2c6d62（main，工作树干净）
> **实施侧回传**：完成，门禁全绿，C-01/C-02 两笔交付。

## 1. 改动清单（恰 10 个白名单文件）

- sql/01-user-db.sql
- sql/migrations/add-idx-friendship-user-high.sql
- record-service/src/main/java/com/sportverify/record/controller/LikeController.java
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
- web/src/api/client.ts
- web/src/api/__tests__/client.test.ts
- web/src/pages/feed.page.vue
- work/mailbox/tasks/TASK-240/spec.md
- work/mailbox/tasks/TASK-240/handoff.md

## 2. 门禁结果

### 2.1 后端 Maven 门禁
- 命令：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am`
- 结果：BUILD SUCCESS；record-service 用例全绿，0 失败 0 错误（含新增 getLikesBatch 批量用例）。

### 2.2 前端门禁（web 目录）
- `pnpm run type-check`：0 错误。
- `pnpm test`：33 用例全绿（新增 getLikesBatch 路由契约用例 R1）。

### 2.3 契约门禁
- 命令：`bash scripts/verify/mailbox-contract.sh --open=TASK-240 --baseline=d2c6d62`
- 结果：退出码 0，判据 A 两件套齐备 + 判据 B 改动集与改动清单一致。

### 2.4 红线复核
- `git diff --name-only d2c6d62` 严格等于上述 10 个白名单文件；零新外部依赖；未 push、未建 PR。

## 3. 设计说明

### 3.1 B6 存储慢查消灭（friendship.user_high 索引）
- 根因：`FriendService.listFriendIds/listFriends` 构造 `WHERE user_low = ? OR user_high = ?`，
  原主键 `(user_low, user_high)` 仅覆盖 user_low 前缀，user_high 维度缺独立索引，OR 查询无法
  索引合并（index merge），随关系数增长退化全表扫描/扫尽主键。
- 变更：`sql/01-user-db.sql` friendship 建表语句增至 `KEY idx_user_high (user_high)`；
  新建幂等迁移 `sql/migrations/add-idx-friendship-user-high.sql`，基于
  `information_schema.statistics` 判断索引存在则跳过（存量库安全，可重复执行）。
  边界与既有 `add-idx-record-status.sql` 同口径（不进 initdb.d，由 migrate.sh 按序拾取）。

### 3.2 B7 服务端点赞批量下推（RTT=1）
- 端点：`POST /api/records/likes/batch`（经网关 `/record/api/records/likes/batch`），
  入参 `List<Long> recordIds`，返回 `Result<List<LikeDTO>>`；身份认定仍沿用
  `resolveUserId`（auth 会话 X-User-Id 为准，降级态显式 userId）。
- 服务：`RecordLikeService.getLikesBatch(recordIds, userId)`：
  1. 空安全：null/空列表 → `Collections.emptyList()`；
  2. 去重保序：过滤 null + `distinct()`，保留首次出现顺序；
  3. 可见性保护：逐 id `selectById` + `assertCanView`，记录不存在或校验异常（BizException）
     安全跳过，绝不因单条记录导致整批 Feed 报错；
  4. 单次往返：`stringRedisTemplate.executePipelined` 批量 GET(count) 与（userId 非空）
     SISMEMBER(liked)，按序解包；
  5. 穿透回源：某记录 count 缓存 null → 回退既有 `readCount(id)`（DB COUNT(*) + 回填）。

### 3.3 B7 前端单请求直出
- `web/src/api/client.ts` 导出 `getLikesBatch(recordIds)` → `POST /record/api/records/likes/batch`。
- `web/src/pages/feed.page.vue` 重构 `loadLikes()`：由 20 次 `Promise.allSettled(getRecordLike)`
  并发改为单次 `getLikesBatch(ids)`，一次网络往返回填 `likeMap`，catch 捕获异常静默降级，
  保持页面可用性。原 `getRecordLike` 仍在 client.ts 保留（供其他页面单条点赞态使用）。

### 3.4 测试覆盖
- 后端 `RecordLikeServiceTest` 新增 7 个 getLikesBatch 用例：批量 Pipeline 点查、空/null 入参、
  去重保序、缓存未命中回源、不可视记录过滤、userId=null 降级、全不可视空返回。
- 前端 `client.test.ts` 新增 R1 `getLikesBatch` 路由契约用例（方法/路径/payload）。

## 4. 提交（C-01 / C-02）
- C-01 实施笔（恰 8 个实施文件）哈希：`86be2d8`（提交信息 `feat(perf): TASK-240 存储索引补齐与Feed点赞态批量下推（C-01 实施笔）`，恰 8 文件 = 白名单实施面全等）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-239/238 先例，实际以 `git log` 为准）。