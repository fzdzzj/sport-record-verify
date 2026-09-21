# TASK-108 Handoff

**实现方：主 agent 本人**（用户指令"实现"）。未 commit、未 push。

## 结论

每日榜单快照落地：表 + 实体 + Mapper + 结算写入 + 只读端点。
`Tests run` 42 → **45**、全仓 271 → **274**，规范口径 `mvn -B -ntp -o -s .mvn-settings.xml test` → BUILD SUCCESS。

## 改动清单

| 文件 | 动作 |
|---|---|
| `sql/02-record-db.sql` | 末尾追加 `leaderboard_daily_summary` DDL |
| `sql/migrations/add-leaderboard-daily-summary.sql` | 新建（同一份 DDL，本仓无 Flyway，两处人工同步已注明） |
| `entity/LeaderboardDailySummary.java` | 新建（`@TableName` + `@TableId(IdType.INPUT)`，联合主键） |
| `mapper/LeaderboardDailySummaryMapper.java` | 新建：`upsertFromActiveContributions` / `deleteStaleToday` / `selectTopByDate` |
| `service/LeaderboardService.java` | 加 1 个构造依赖；`settleAndReconcile()` 追加第 4 步报表写入；新增 `dailyReport(LocalDate,int)` |
| `controller/LeaderboardController.java` | 新增 `GET /api/leaderboard/daily?date=&size=` |
| `service/LeaderboardServiceTest.java` | 新增 3 条用例，构造器加第 3 个 mock |
| `service/LeaderboardWritePathStaysUnproxiedTest.java` | 仅补构造参数（哨兵语义未动） |

原子性按 ADR-0009 办：汇总写入是**单条** `INSERT ... SELECT ... ON DUPLICATE KEY UPDATE`，
残留清理是**单条** `DELETE ... LEFT JOIN`，全程**没有**加 `@Transactional`，也没有为报表另起锁
（复用结算已有的 `lock:scheduler:leaderboard`）。哨兵测试仍绿，证明代理边界没被撬动。

## 红凭据（三条用例各自种一次，非"写完就绿"）

| 变异 | 结果 |
|---|---|
| A：把两行报表写入换成 `int upserted = 0; int purged = 0;` | `settleAndReconcile_writesDailyReportSnapshotOnce` 红：**Wanted but not invoked**（`LeaderboardServiceTest:345`）；负向那条按 `never()` 语义必然绿，故不拿它充当证据 |
| B：把 upsert 挪到 `tryLock` **之前** | `settleAndReconcile_lockNotAcquired_doesNotTouchReport` 红：**Never wanted here**（`:356`） |
| C：`Math.min(topN, DAILY_REPORT_MAX_SIZE)` 去掉钳位 | `dailyReport_ranksRowsAndClampsSize` 红：**Argument(s) are different! Wanted ... 500**（`:379`） |

三次均已还原，现场 `grep` 复核：无 `upserted = 0` 残留、`Math.min(topN` 在位、两行 mapper 调用在 `:366/:368`。

## 顺手补的一个正确性缺口（超出 spec 一行，说明理由）

spec 只要求 upsert。但 upsert 只覆盖"仍有 ACTIVE 贡献"的用户：用户当日被**全量回滚**后
`GROUP BY` 不再产出该行，报表会永远挂着回滚前的旧里程。故加了 `deleteStaleToday`（同样单语句、同样无事务）。
只清 `stat_date = CURDATE()`，与"只写今天"的口径对称。

## MySQL 真跑凭据（已补，不再是缺口）

脚本：`work/mailbox/verification/task108-sql-smoke.sql`，跑在**独立 scratch 库** `task108_verify`，
用完 `DROP DATABASE`；**没碰 `record_db` 一行数据**。容器 `sport-verify-mysql`（compose 的 mysql:8.0）
用完已 `docker compose stop mysql` 恢复原状（起之前它是停着的）。

| 步骤 | 结果 |
|---|---|
| 建表（含 `(stat_date, total_distance DESC)` 降序索引） | 成功，MySQL 8.0 语法合法 |
| A 首条 upsert | user 100 → 88.05/2 行（42.50+45.55 汇总正确）、user 101 → 88.05/1；`status=1` 的 102 被正确排除 |
| B upsert 跑第二遍 | `COUNT(*) = 2`，幂等无重复行 |
| C 改 distance 后再 upsert | 100 → 145.55，覆盖生效 |
| D `deleteStaleToday`（101 全量回滚后） | 101 的快照行被删除，只剩 100 —— 我自行补的那个正确性缺口真起作用 |
| E `selectTopByDate` | 别名映射 `statDate/userId/totalDistance/recordCount/updatedAt` 全部取到值 |
| EXPLAIN | `key = idx_date_score`、`Extra = Using index` —— 降序索引不但合法，还被优化器用上 |

## HTTP 层凭据（已补）

新建 `leaderboard-service/src/test/java/com/sportverify/leaderboard/controller/LeaderboardControllerDailyTest.java`
（standalone MockMvc，不起上下文），4 条：ISO 日期绑定 + size 透传、`size` 缺省＝50、
`date=20/09/2026` → 400（服务端日志实证 `MethodArgumentTypeMismatchException`）、缺 `date` → 400。
变异取证：`defaultValue = "50"` 改成 `"10"` → `Tests run: 4, Failures: 1`；还原后 4/4 绿。

## 最终数字（规范口径）

`mvn -B -ntp -o -s .mvn-settings.xml -pl leaderboard-service -am test` → **49** 绿（39→42→45→49）
`mvn -B -ntp -o -s .mvn-settings.xml test` → 全仓 **278** 绿 / BUILD SUCCESS

## 诚实边界（剩下的）

只验到"三条 SQL 在真 MySQL 8.0 上手工执行正确"。**没有**验证 Spring/MyBatis-Plus 运行时把
`#{activeStatus}` 替换成参数后走通的端到端链路（那需要起 leaderboard-service + Nacos + Redis + MySQL 全栈）。
`LeaderboardServiceTest` 里 mapper 是 mock，所以"注解 SQL 能被 MyBatis 解析成语句"这一步仍只有
手工执行这一条证据。

## 待主 agent 决定

1. **口径**：报表存的是"截止当日累计"，产品若要"当日新增里程"需改 DDL 与 SELECT（当前假设已写进 spec 与本文件）。
2. **历史日残留**：`deleteStaleToday` 只管今天。若某用户昨天的快照在今天因回滚失真，不自动回补——需要"重算指定日期"的能力吗？
3. **端点鉴权**：已按"仅超级管理员"收口——`0c66aae` 把外部路径
   `/leaderboard/api/leaderboard/daily` 追加进 `app.auth.admin.paths`（走 add-admin-rbac 既有机制，
   未在 leaderboard-service 内另造鉴权），并由 `LeaderboardDailyAdminOnlyTest` 从 yml 读真实配置守住。
   **残余**：治理面只在网关判，直连 leaderboard-service:8084 可绕过（与 ADR-0007 内网信任边界同口径），
   要收紧需上网络策略或让服务侧再校验一次 `X-Role`。
4. ~~是否补真 MySQL 冒烟~~ 已做（见上两节）。
