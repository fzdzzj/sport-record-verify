# TASK-103 handoff — 并发与性能修复（F04/F05/F06/F15/F16/F17/F18）

## 完成情况
7 条发现全部落地，验收命令全过，既有测试不红。

## 每条发现改动点（文件:行）

- **F04 锁-事务顺序**：`user-service/.../FriendService.java:62` 注入 `PlatformTransactionManager`；
  createRequest(:102)/accept(:178)/reject(:229) 三入口去掉事务注解、先拿锁，锁内经
  `TransactionTemplate` 执行事务体 doCreateRequest(:110)/doAccept(:186)/doReject(:237)，
  锁在 finally 于事务提交后释放。accept 原 `@Transactional` 已移除（语义由编程式事务等价承载）。
  测试：FriendServiceTest 重写 `accept_lockWrapsTransaction_unlockAfterCommit`（断言入口无
  @Transactional + commit 先于 unlock）；FriendServiceTest/FriendConcurrencyTest setUp 补
  transactionManager 桩与新构造参数。
- **F05 批量预筛**：`mapmatch-service/.../MapMatchService.java:70` match() 先调
  fetchCandidateEdges(:114)——轨迹 bbox 四向外扩 searchRadius，`ST_Intersects + ST_MakeEnvelope`
  一次 SQL 取全部候选边（GIST）；逐点循环(:72)纯内存算垂距，distanceToNearestRoad(:136) 候选空时
  仍按半径封顶兜底。DB 往返 N→1。MatchProperties 未动。测试补「单次 DB 往返」用例。
- **F06 好友榜限量**：`leaderboard-service/.../LeaderboardService.java:74` 新增常量
  FRIEND_SCAN_BATCH=500；topFriends(:237-280) 改分批 ZREVRANGE 边拉边过滤，凑满 size 或
  批不满（榜尾）即停，不再 reverseRangeWithScores(0,-1)。测试桩改 (0,499) 并补跨批扫描用例。
- **F15 对账聚合**：`record-service/.../mapper/RecordLikeMapper.java:72` 新增
  `selectCountsByRecord()`（一条 GROUP BY 出全部 record_id+COUNT，内嵌 RecordLikeCount 行类）；
  `RecordLikeService.java:277` reconcileLikeCounts 只对计数漂移/缺失记录覆盖计数并重建成员集，
  一致记录跳过。旧方法 selectDistinctRecordIds 已按主 agent 追加指令删除
  （注解 SQL、无 XML、无测试引用），删后 `mvn -s .mvn-settings.xml -q -pl record-service -am test` 全绿。
- **F16 flush 批可配**：`RecordLikeService.java:84` `FLUSH_BATCH` 常量改为
  `@Value("${record.like.flush-batch:200}") private int flushBatch`（默认 200，行为不变）。
- **F17 回源防击穿**：`RecordLikeService.java:363` readCount 加 SETNX 短锁
  `lock:like:count-init:{recordId}`（3s TTL），持锁者双检+查库回填+finally 删锁；
  未持锁线程自旋重读 Redis（50ms×3，常量 :99-101）后兜底直读 DB 不回填。
- **F18 索引**：`sql/02-record-db.sql:29` sport_record 加
  `KEY idx_status_created (status, created_at)`；`scripts/db/migrate.sh` 尾部新增幂等补索引段
  （先查 information_schema.statistics 再 ALTER），并修掉「无迁移文件即 exit 0」导致索引段被跳过的
  早退分支。

## 验收结论
1. `mvn -s .mvn-settings.xml -q -pl user-service,mapmatch-service,leaderboard-service,record-service -am test`
   全绿：user 35 / record 80 / leaderboard 23 / mapmatch 7，BUILD SUCCESS，退出码 0。
2. `grep -c "idx_status_created" sql/02-record-db.sql` = 1 ✓。
3. MapMatchService 中 jdbcTemplate 仅出现在 fetchCandidateEdges（match 逐点循环内无任何 DB 调用）✓。

## 环境注意（非本次改动引入）
本会话沙箱禁止子进程管道，Mockito 5.7 inline MockMaker 的 ByteBuddy 外部进程自附加会失败
（未改动的 AuthServiceTest 等同样报错）。验收需追加
`-DargLine="-Djdk.attach.allowAttachSelf=true"` 让 ByteBuddy 走进程内自附加。此问题在我改动前即存在。

## 待主 agent 决定
- 无。（argLine 不固化进 pom——主 agent 已决策；selectDistinctRecordIds 已删除。）
