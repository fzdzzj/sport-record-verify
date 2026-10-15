# TASK-239: 好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返（MEDIUM 档）

> **开工基线**：57128e8（main 分支，工作树干净，TASK-238 封盘笔，CI run 38692154084 全 job 绿）
> **任务级别**：MEDIUM（后端服务性能优化：topFriends 由整榜分批扫描重构为 Redis Pipeline 批量 ZSCORE 检索）

## 1. 目标与背景

- **背景**：
  1. **好友榜分批扫描全榜性能灾难（LeaderboardService.java:273-288）**：
     当前 `topFriends(Long userId, int size)` 实现为：先拉取用户的全部好友 ID 集合，随后通过 `for (long start = 0; ; start += FRIEND_SCAN_BATCH)` 对 `leaderboard:overall` 总榜 ZSet 循环调用 `reverseRangeWithScores` 批次扫描（每批 500 条）。若用户的好友在总榜排名靠后（如万名之后），或者好友多未上榜，该循环将沿 ZSet 持续遍历直到榜尾，产生数十至数百次 Redis 网络往返（RTT），并拉取传输海量无关数据，成为高频接口的潜在性能瓶颈。
  2. **缺少基于好友列表的反向点查优化**：
     由于用户的好友数量通常有限（通常几十至数百人，单次分页拉齐上界已受保护），从总榜正向扫描过滤非好友的复杂度为 $O(N_{\text{total\_zset}})$；而若直接针对好友 ID 进行 Redis `ZSCORE` 批量点查，复杂度直接降为 $O(N_{\text{friends}})$ 且可利用 Pipeline 在单次网络往返（RTT=1）内完成全部计算。
- **目标**：
  1. 重构 `LeaderboardService.topFriends`：利用 `stringRedisTemplate.executePipelined` 批量查询所有好友在 `leaderboard:overall` 中的得分（`zScore`），单次往返获取全部好友积分；
  2. 内存排序与截断：过滤出有积分好友，按积分降序（同分按 member 字符串逆序稳定排列，100% 对齐 Redis ZREVRANGE 行为），截取前 `size` 名，交由既有 `assemble` 组装返回；
  3. 严格保持既有业务契约：
     - 只显示好友，本人与非好友均不在榜；
     - 无好友、好友未上榜、size <= 0 返回空榜；
     - Feign 调用异常或降级时安全返回空榜；
  4. 适配与强化单元测试：保持 `leaderboard-service` 既有用例全绿，增加 Pipeline 命中断言与同分/未上榜过滤单测。

## 2. 修改白名单

严格限定在以下 5 个文件内：

- 后端排行榜服务（共 3 文件）：
  - `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
  - `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
  - `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`（TASK-239 实施期经指导侧确认扩展：该测试编码被重构移除的旧实现细节，需同步适配 Pipeline 断言）
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-239/spec.md`
  - `work/mailbox/tasks/TASK-239/handoff.md`

> **红线约束**：
> - 除上述 4 个文件外，其他微服务代码、网关、web 前端、数据库 SQL、根 pom、`package.json`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖（复用既有 Spring Data Redis、Redisson 连接体系）。

## 3. 核心设计与判别式要求

### 3.1 topFriends Redis Pipeline 批量点查重构（LeaderboardService.java）

- **实施逻辑**：
  1. 在 `topFriends(Long userId, int size)` 中，保留前置校验与 `fetchAllFriendIds(userId)`（一字不改）；
  2. 对 `friendIds` 进行去重（`List<Long> distinctFriendIds = new ArrayList<>(new LinkedHashSet<>(friendIds));`）；
  3. 采用 `stringRedisTemplate.executePipelined`（入参 `(RedisCallback<Object>) connection -> { ... }`）：
     ```java
     byte[] keyBytes = OVERALL_ZSET_KEY.getBytes(StandardCharsets.UTF_8);
     List<Object> rawScores = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
         for (Long fid : distinctFriendIds) {
             connection.zSetCommands().zScore(keyBytes, String.valueOf(fid).getBytes(StandardCharsets.UTF_8));
         }
         return null;
     });
     ```
  4. 遍历 `rawScores`，若结果非 null 且解析为 `Double score`（`score > 0`），则构造 `TypedTuple.of(String.valueOf(distinctFriendIds.get(i)), score)` 加入候选列表；
  5. 对候选列表按分数降序排序，分数相同按 member 降序排列以保证稳定排序；
  6. 若候选列表为空，返回 `List.of()`；截取前 `size` 条，调用 `assemble(tuples)` 完成组装；
  7. 移除已无调用的 `FRIEND_SCAN_BATCH` 常量或保留安全注释。

### 3.2 单元测试适配与强化（LeaderboardServiceTest.java）

- **适配既有单测**：
  - `topFriend_filtersNonFriends`：打桩 `redis.executePipelined(...)` 返回有效分数，断言返回好友上榜且本人/非好友不入榜；
  - `topFriend_readsAllFriendPages_whenFriendsExceedSinglePage`：打桩 `redis.executePipelined(...)`，断言分页好友均被纳入查询；
  - `topFriend_scansBoundedBatches_untilFriendAtLeaderboardEnd` 改写为 `topFriend_usesPipeline_batchRetrievesFriendScoresInSingleRoundTrip`：打桩并断言 `redis.executePipelined` 恰好被调用 1 次，且 `zSetOps.reverseRangeWithScores` 彻底为 0 次调用；
- **新增针对性单测**：
  - `topFriend_sortsFriendsByScoreDescending_andTiesByMemberDescending`：覆盖多个好友同分与不同分降序排序、未上榜好友（score 为 null）过滤、以及请求 `size` 截断。
- **缓存调用契约适配**（LeaderboardCacheInvocationTest.java）：
  - `friendQueriesBypassOverallCacheAndCallFriendServiceEachTime` 由断言 ZSet `reverseRangeWithScores` 改版为断言 `redis.executePipelined` 恰 2 次 + `zSet` 零交互，语义不变（好友榜每请求直查 Redis、不经总体榜缓存）。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl leaderboard-service -am`：全部用例全绿通过，0 错误 0 失败；
  2. `scripts/verify/mailbox-contract.sh --open=TASK-239 --baseline=57128e8`：退出码 0，两件套齐备且白名单一致；
  3. `git diff --name-only 57128e8` 严格全等于上述 5 个白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(leaderboard): TASK-239 好友榜查询引入 Redis Pipeline 批量获取积分（C-01 实施笔）`（恰 3 个代码文件）
  - C-02 台账笔：`docs(mailbox): TASK-239 好友榜 Pipeline 批量优化验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 5. 验收与门禁回填

- **Maven 门禁（绿）**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl leaderboard-service -am` → `Tests run: 87, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，`MVN_EXIT=0`（实施侧实测 2026-10-15）。
- **契约门禁（绿）**：`scripts/verify/mailbox-contract.sh --open=TASK-239 --baseline=57128e8` 退出码 0：判据 A 两件套齐 + 判据 B 只改清单与实际改动集一致（恰 5 文件）。
- **提交**：C-01 实施笔 `3cf0406`（3 代码文件）；C-02 台账笔为本 handoff 所在提交（`docs(mailbox)`，自指哈希，实际以 `git log` 为准）。
- **红线复核**：`git diff --name-only 57128e8` 严格全等于 5 个白名单文件；零新依赖；未 push 未建 PR。
