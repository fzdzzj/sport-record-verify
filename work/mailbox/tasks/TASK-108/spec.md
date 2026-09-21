# TASK-108 每日排行榜报表（走现有 @Scheduled，不引入调度框架）

## 目标
把"每日榜单快照"真正落进 DB，并给一个只读查询端点。
背景：TASK-004 的 XXL-JOB 桩已按 PLAN.md 的 D5(a) 全部删除（jar 本就不在 `.m2-repo` 内、
类还被 `<excludes>` 排除编译）。**全仓现在没有任何 `DailySummary` 表/实体/mapper**
（已核实：`grep -rl DailySummary` 命中 0），所以这是从零实现，不是补接线。

## 先读文件
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
  （`:307-362` 的 `settleAndReconcile()`：已有 Redisson 锁 `lock:scheduler:leaderboard` 防多实例重复执行，
  `:332-333` 已经拿到 `selectActiveSummaries()` 的**每用户 ACTIVE 累计里程**——报表的数据源就在手里，别另查一遍）
- 同文件 `:20-30` 常量区（`SETTLE_FIXED_DELAY_MS` 等命名风格）
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java`
  （继承 `BaseMapper`，SQL 全用 `@Insert`/`@Update`/`@Select` 注解，无 XML）
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/entity/LeaderboardContribution.java`
  （`@Data` + `@TableName` + `@TableId(type = IdType.INPUT)`，Javadoc 里标审批版章节）
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java`
  （`@RequestMapping("/api/leaderboard")`、返回 `Result<...>`）
- `sql/02-record-db.sql`（`:48-58` 是 `leaderboard_contribution` 的 DDL 与注释风格；榜单表留在 record_db 共用）
- `sql/migrations/`（现有命名：`add-idx-record-seq.sql`、`add-user-role.sql`）
- `docs/adr/0009-事务边界.md`（**必读**，见下方硬约束）

## 只改/新建文件
- 新建 `leaderboard-service/src/main/java/com/sportverify/leaderboard/entity/LeaderboardDailySummary.java`
- 新建 `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardDailySummaryMapper.java`
- 新建 `sql/migrations/add-leaderboard-daily-summary.sql`，并在 `sql/02-record-db.sql` 末尾追加同一段 DDL
  （两处必须逐字一致，本仓无 Flyway/Liquibase，schema 靠这两个文件人工同步）
- 改 `LeaderboardService.java`：在 `settleAndReconcile()` 的结算成功分支内调用一次快照 upsert
- 改 `LeaderboardController.java`：新增 `GET /api/leaderboard/daily?date=yyyy-MM-dd&topN=20`
- 新建对应测试（实体/mapper 不需要单测，Service 与 Controller 需要）

## 表设计（口径已定，按此实现）
```sql
CREATE TABLE IF NOT EXISTS `leaderboard_daily_summary` (
    `stat_date`      DATE          NOT NULL COMMENT '统计日期（服务器时区，快照当日累计）',
    `user_id`        BIGINT        NOT NULL COMMENT '用户ID',
    `total_distance` DECIMAL(10,2) NOT NULL COMMENT '截止当日 ACTIVE 累计里程（公里）',
    `record_count`   INT           NOT NULL DEFAULT 0 COMMENT '构成该累计的 ACTIVE 贡献条数',
    `updated_at`     DATETIME      NOT NULL COMMENT '本轮结算写入时间',
    PRIMARY KEY (`stat_date`, `user_id`),
    KEY `idx_date_score` (`stat_date`, `total_distance` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='榜单每日快照表（每轮结算幂等 upsert）';
```
口径为**截止当日的累计快照**（不是当日增量）：结算任务手里正好是 ACTIVE 汇总，累计口径零额外查询；
增量由相邻两日相减得到。**这是本任务的假设，若产品要的是"当日新增里程"，停下写进「待主 agent 决定」。**

## 原子性硬约束（ADR-0009，违反即退回）
- **禁止**给 `settleAndReconcile` 或任何榜单写路径加 `@Transactional`（`docs/adr/0009-事务边界.md:37-38`、`:53`）。
- 因此 upsert 必须是**单条 SQL 自身原子**：
  ```java
  @Insert("INSERT INTO leaderboard_daily_summary (stat_date, user_id, total_distance, record_count, updated_at) "
        + "SELECT CURDATE(), user_id, SUM(distance), COUNT(*), NOW() "
        + "FROM leaderboard_contribution WHERE status = #{activeStatus} "
        + "GROUP BY user_id "
        + "ON DUPLICATE KEY UPDATE total_distance = VALUES(total_distance), "
        + "record_count = VALUES(record_count), updated_at = VALUES(updated_at)")
  int upsertFromActiveContributions(@Param("activeStatus") Integer activeStatus);
  ```
  一条语句完成"按用户汇总 + 幂等写入"，既不需要事务，也不要在 Java 侧循环逐行写（N 次往返）。
- 多实例并发：`settleAndReconcile` 外层已有 Redisson 锁，**不要**为报表再引入第二把锁。

## 必须做的测试（每条都要先证能红）
1. `LeaderboardServiceTest` 里新增：结算执行后**必须调用一次** `upsertFromActiveContributions(ACTIVE.code)`
   （`verify(mapper, times(1))`）；取到锁失败的那条路径**不许**调用。
   红证：把 Service 里那行 upsert 注释掉 → 必须红；还原 → 绿。
2. `LeaderboardController` 新端点的测试：`date` 缺失/格式非法 → 按 `Result` 的错误口径返回，
   正常请求返回按 `total_distance` 降序且 `rank` 从 1 开始。
3. 端点若需要按日期查询，mapper 提供一个 `selectByDate(statDate, limit)`，测试断言传参正确即可（无 DB，别引 testcontainers）。

## 禁止
- 新增任何第三方依赖（`.m2-repo` 离线集合外一律不可用）；引入 XXL-Job/Quartz/ShedLock 一律禁止
- 改 `CacheConfig.java`、`CacheConfigTest.java`、`LeaderboardWritePathStaysUnproxiedTest.java`、`pom.xml`
- 改 `settleAndReconcile` 现有的 ZADD/ZREM/markSettled 三步语义（本任务只**追加**一次 upsert）
- commit、push

## 验收命令（规范口径，缺 `-o -s` 视为未验收）
```bash
cd /d/code/sports && mvn -B -ntp -o -s .mvn-settings.xml -pl leaderboard-service -am test
# 本任务前：Tests run: 39 / BUILD SUCCESS
cd /d/code/sports && mvn -B -ntp -o -s .mvn-settings.xml test
# 全仓基线 268，只许多不许红
```
无本地 MySQL 也要跑一遍 DDL 语法自查：`sql/migrations/add-leaderboard-daily-summary.sql` 里的
`KEY idx_date_score (... DESC)` 在 MySQL 8 才合法——若你在 handoff 里改口说"环境是 5.7 要降为普通索引"，
必须给出你据以判断的文件/配置行号（`docker-compose.yml` 里 mysql 镜像 tag），不准猜。

## 完成定义
`work/mailbox/tasks/TASK-108/handoff.md` 写：改了哪几处（file:line）/ 两条红证原文 / 复跑结果 /
「待主 agent 决定」（含"累计 vs 增量"口径是否要产品确认）。回报 ≤200 字。
