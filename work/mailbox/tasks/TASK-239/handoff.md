# TASK-239 handoff：好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-239`（外约：`work/mailbox/tasks/TASK-239/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（后端服务性能优化：topFriends 由整榜分批扫描重构为 Redis Pipeline 批量 ZSCORE 检索）。
- 开工基线：`57128e8`（main 分支，工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`3cf0406`（提交信息 `feat(leaderboard): TASK-239 好友榜查询引入 Redis Pipeline 批量获取积分（C-01 实施笔）`，恰 3 文件 = 白名单实施面全等）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-238 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**。

## 2. 只改清单（相对开工基线 57128e8，恰 5 文件 = C-01 3 + C-02 2）

C-01（实施笔，恰 3 文件 = 白名单实施面全等）：

- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：

- work/mailbox/tasks/TASK-239/spec.md
- work/mailbox/tasks/TASK-239/handoff.md

> 白名单外零触碰：其他微服务代码、网关、web 前端、数据库 SQL、根 pom、`package.json`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 均零 diff；零新依赖（复用既有 Spring Data Redis 连接体系）。

## 3. 一句话结论

`LeaderboardService.topFriends` 由对总榜 `leaderboard:overall` 循环批次扫描（FRIEND_SCAN_BATCH=500）重构为基于去重好友 ID 列表经 `stringRedisTemplate.executePipelined` 单次往返批量 `ZSCORE` 点查：未上榜（null）/非正分数过滤、分数降序（同分按 member 字符串降序，100% 对齐 ZREVRANGE）、截取前 size 后走既有 `assemble` 组装；时间复杂度由 $O(N_{zset})$ 降为 $O(N_{friends})$ 且 RTT=1。业务契约不变（只显示好友、无好友/未上榜/降级返回空榜）。全套门禁全绿：Maven 87 测 0 失败 0 错误、契约门禁退出码 0。

## 4. 判别式与验收要点

- **R1 Pipeline 单次往返批量点查（绿）**：`topFriends` 保留前置校验与 `fetchAllFriendIds` 一字未改；`List<Long> distinctFriendIds = new ArrayList<>(new LinkedHashSet<>(friendIds))` 去重；`stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {...})` 内按 distinctFriendIds 顺序逐条 `connection.zSetCommands().zScore(keyBytes, String.valueOf(fid).getBytes(UTF_8))`，返回序与入参一一对应；`rawScores` 为 null 安全返回空榜。
- **R2 内存过滤/排序/截断（绿）**：元素为 Number 且 `score > 0` 才构造 `TypedTuple.of(String.valueOf(distinctFriendIds.get(i)), score)`；候选为空直接 `List.of()`；`candidates.sort` 分数降序、同分按 `b.getValue().compareTo(a.getValue())` member 字符串降序；`size` 截断后 `assemble(top)`。
- **R3 旧循环彻底移除（绿）**：整榜分批 `reverseRangeWithScores` 循环已删除；`FRIEND_SCAN_BATCH` 常量保留并加注历史说明（不再被引用）；新增单测断言 `executePipelined` 恰 1 次 + `zSetOps.reverseRangeWithScores` 0 次。
- **R4 单元测试适配与强化（绿）**：`topFriend_filtersNonFriends` 与 `topFriend_readsAllFriendPages_whenFriendsExceedSinglePage` 改打桩 `redis.executePipelined` 返回预期积分列表，既有断言全绿；`topFriend_scansBoundedBatches_untilFriendAtLeaderboardEnd` 改写为 `topFriend_usesPipeline_batchRetrievesFriendScoresInSingleRoundTrip`；新增 `topFriend_sortsFriendsByScoreDescending_andTiesByMemberDescending`（200→88.0、100/300 同分 42.5 按 member 降序 300 先、400 null 分数过滤、size=2 截断，rank 与顺序完全正确）。
- **R5 缓存调用契约适配（绿）**：`LeaderboardCacheInvocationTest.friendQueriesBypassOverallCacheAndCallFriendServiceEachTime` 断言由 `zSet.reverseRangeWithScores(0,499)` 改版为 `redis.executePipelined` 恰 2 次 + `zSet` 零交互（好友榜每请求直查 Redis、不经总体榜缓存，语义不变）；`friendServiceFailureStillReturnsEmptyBoardThroughProxy` 与总榜缓存用例零改动零回归。
- **R6 红线（绿）**：`git diff --name-only 57128e8` + untracked 恰 5 文件全等于 §2 清单（无多报无漏报）；零新依赖；白名单外文件零 diff；`PLAN.md`/`HANDOVER.md` 未触碰；未 push 未建 PR。

## 5. 验证命令与输出片段

1. **后端门禁**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl leaderboard-service -am` → `[INFO] Tests run: 87, Failures: 0, Errors: 0, Skipped: 0`、`[INFO] BUILD SUCCESS`、`MVN_EXIT=0`（Nacos 连接失败 stderr 噪音为既有环境现象，不影响结论）。
2. **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-239 --baseline=57128e8`（`D:\git\Git\bin\bash.exe`，Git Bash 实跑）→ 退出码 0：判据 A 两件套齐（spec.md + handoff.md 均在）+ 判据 B 只改清单与实际改动集一致（`git diff --name-only 57128e8` 恰 5 文件全等）。

## 6. 未覆盖、遗留风险与不得推出的结论

- **真实 Redis 往返次数未压测**：单测以打桩断言单次 `executePipelined` 调用；真实 RTT=1 与好友量级下的吞吐收益建议后续在联调/压测环境验证（白名单内无压测脚本）。
- **`--mode=online` / `--static`（Checkstyle/SpotBugs/PMD）CI 全流程未跑**（未 push、未建 PR），外部门禁待指导侧推动后补记。
- **好友数极大（>1 万）时单次 Pipeline 命令数仍为 1 条/好友**：与旧实现"逐批扫描"相比已降为单往返，但单连接命令条数随好友数线性增长；如后续好友规模异常膨胀可再分片 Pipeline（不在本任务范围）。
- **降级口径不变**：user-service 不可用仍返回空榜（不选"不过滤"），好友未拉齐（短页/空页）不返回部分榜——行为与 TASK-239 前完全一致，由既有用例 `topFriend_emptyOrDegraded_returnsEmpty`/`secondPageFallback`/`shortPageBeforeExpectedTotal` 守护。

## 7. 偏差登记

- **D1（白名单扩展，经指导侧确认）**：spec 原白名单为 4 文件，但同包既有测试 `LeaderboardCacheInvocationTest.java` 显式断言被本任务移除的旧实现细节（好友路径 `reverseRangeWithScores(0,499)` 恰 2 次），重构后该用例必然失败且该文件不在白名单内。经指导侧授权将 `LeaderboardCacheInvocationTest.java` 纳入白名单（C-01 3 文件），其 `friendQueriesBypassOverallCacheAndCallFriendServiceEachTime` 断言改版为 Pipeline 语义（executePipelined 恰 2 次 + zSet 零交互），其余用例零改动；spec.md §2/§3.2/§4 同步更新为 5 文件口径。
- **D2（行尾契约）**：本机 `core.autocrlf=true`，编辑器写入 bare LF，`git add` 时归一 CRLF（warning 属预期）；`git diff --numstat 57128e8` 复核全为行级小 diff（LeaderboardService.java 59 行改动、LeaderboardServiceTest.java 76 行、LeaderboardCacheInvocationTest.java 14 行），无整文件重写残迹。

## 8. 停止条件

白名单内实施与测试改动完成（C-01 恰 3 文件），验证命令全绿（Maven BUILD SUCCESS 87 测 0 失败 0 错误），台账两件套落盘（C-02 恰 2 文件）并经外约门禁退出码 0；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、真实环境压测、好友超大规模分片 Pipeline，须新开任务，不在本任务扩大范围。
