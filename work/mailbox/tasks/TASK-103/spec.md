# TASK-103 并发与性能修复（F04/F05/F06/F15/F16/F17/F18）

## 目标
修复 7 条并发/性能发现：好友锁在事务内（F04）、mapmatch 逐点 SQL（F05）、好友榜全量拉 ZSet（F06）、点赞对账全表扫（F15）、flush 批大小硬编码（F16）、计数回源无防击穿（F17）、滞留扫描缺索引（F18）。明细先读 work/mailbox/findings-summary.md 对应条目。

## 范围外
- 不改任何 pom.xml、application.yml/properties（可配项一律 @Value 默认值）
- 不改 controller / api 契约 / 其他 service
- 不引入新依赖（离线仓库 .m2-repo，settings 为 .mvn-settings.xml）

## 先读文件
- work/mailbox/findings-summary.md（F04/F05/F06/F15/F16/F17/F18）
- user-service/src/main/java/com/sportverify/user/service/FriendService.java 及其测试（FriendServiceTest、FriendConcurrencyTest）
- mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java、config/MatchProperties.java 及 MapMatchServiceTest
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java 及 LeaderboardServiceTest
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java、mapper/RecordLikeMapper.java 及 RecordLikeServiceTest
- sql/02-record-db.sql、scripts/db/migrate.sh

## 只改文件
- user-service/src/main/java/com/sportverify/user/service/FriendService.java（如需拆分可加同包新类）
- user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java、FriendConcurrencyTest.java
- mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java
- mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
- record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
- sql/02-record-db.sql、scripts/db/migrate.sh

## 要做的修改
1. F04 锁-事务顺序：把 Redisson 锁外提到事务之外——public 入口方法不加 @Transactional、先拿锁，锁内调用一个 @Transactional 的内部方法（经注入的自身代理或拆 TransactionTemplate），保证锁释放晚于事务提交。createRequest/accept/reject 三个入口都改。FriendConcurrencyTest 保持绿。
2. F05 批量预筛：match() 先按轨迹 bbox + searchRadius 一次 SQL 查出全部候选边 WKT（ST_Intersects + ST_Expand 或等价写法，走 GIST），然后逐点在内存中对候选集算垂距；单次 /match 的 DB 往返从 N 次降为 1 次。保留逐点兜底（bbox 内无候选时按离路处理）。MatchProperties 不动。
3. F06 好友榜限量：topFriends 不再 reverseRangeWithScores(0,-1)；改为分批 ZREVRANGE（如每批 500）边拉边过滤，凑满 size 即停；批大小用常量或 @Value 默认。语义不变（按分数降序、排除本人与非好友）。
4. F15 对账聚合：reconcileLikeCounts 改为一条 GROUP BY SQL 取全部 (record_id, COUNT(*))（RecordLikeMapper 加聚合方法），成员集重建仅对计数与 Redis 不一致的记录执行，不再逐记录查 userIds。
5. F16：FLUSH_BATCH 改 @Value("${record.like.flush-batch:200}") 可配。
6. F17 回源防击穿：readCount 的 DB 兜底回填加 SETNX 短锁（如 lock:like:count-init:{recordId}，3s），拿不到锁的线程短暂自旋重读 Redis（最多 ~3 次）再兜底直读 DB。
7. F18 索引：sql/02-record-db.sql 的 sport_record 加 `KEY idx_status_created (status, created_at)`；scripts/db/migrate.sh 同步补幂等的加索引语句（先查 information_schema 再 ALTER）。

## 验收命令
1. `mvn -s .mvn-settings.xml -q -pl user-service,mapmatch-service,leaderboard-service,record-service -am test` 全绿
2. `grep -c "idx_status_created" sql/02-record-db.sql` ≥ 1
3. MapMatchService 中 match() 路径 grep 验证：循环内不再有 jdbcTemplate 调用

## 完成定义
- 7 条发现落地，验收命令全过，既有测试不红
- 写 work/mailbox/tasks/TASK-103/handoff.md：完成情况 / 每条发现改动点（文件:行）/ 验收结论 / 「待主 agent 决定」清单
- 回报 ≤300 字：产出 / 校验结果 / 未解决项 / 待决策项

## 约束
- 你是上述文件的唯一写入者；最多 1 次修复重试
- 不准猜测，缺信息写 handoff「待主 agent 决定」
