package com.sportverify.leaderboard.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.api.common.PageResult;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.entity.LeaderboardContribution;
import com.sportverify.leaderboard.entity.SportRecordSnapshot;
import com.sportverify.leaderboard.enums.ContributionStatus;
import com.sportverify.leaderboard.mapper.ClubDistanceContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.entity.LeaderboardDailySummary;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper.UserMileage;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 排行榜服务单元测试（规范差异：仅 pass 入榜 / 事件幂等 / 改判回滚 /
 * 回滚幂等 / 无贡献不回滚 / 总榜 / 好友榜过滤与降级 / 快照结算防重与纠偏）。
 *
 * <p>用 Mockito 打桩 Mapper / StringRedisTemplate / RedissonClient（RLock 恒可获取）/
 * UserApi，覆盖各业务分支；不依赖真实 Redis/MySQL/MQ（与点赞模块单测同款风格）。
 * 随榜单职责整体迁自 record-service（服务数 4→5）；对记录域的打桩对象同步换成
 * 只读快照 Mapper（SportRecordMapper.selectById → SportRecordSnapshot）。</p>
 */
class LeaderboardServiceTest {

    private SportRecordMapper sportRecordMapper;
    private LeaderboardContributionMapper contributionMapper;
    private ClubDistanceContributionMapper clubDistanceContributionMapper;
    private LeaderboardDailySummaryMapper dailySummaryMapper;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private RedissonClient redissonClient;
    private RLock lock;
    private UserApi userApi;
    private CacheManager cacheManager;
    private Cache overallCache;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        contributionMapper = mock(LeaderboardContributionMapper.class);
        clubDistanceContributionMapper = mock(ClubDistanceContributionMapper.class);
        dailySummaryMapper = mock(LeaderboardDailySummaryMapper.class);
        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        userApi = mock(UserApi.class);
        cacheManager = mock(CacheManager.class);
        overallCache = mock(Cache.class);

        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(cacheManager.getCache(LeaderboardService.OVERALL_CACHE_NAME)).thenReturn(overallCache);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        service = new LeaderboardService(sportRecordMapper, contributionMapper, dailySummaryMapper,
                redis, redissonClient, userApi, cacheManager, clubDistanceContributionMapper);
    }

    // ==================== 入榜（VERIFIED：仅 pass + 幂等） ====================

    /** 规范差异「通过记录入榜」：PASSED 记录 → 新锚点 → ZINCRBY +distance */
    @Test
    void applyVerified_passed_creditsScore() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(1);

        service.applyVerified(1L);

        verify(zSetOps).incrementScore(LeaderboardService.OVERALL_ZSET_KEY, "100", 42.5d);
        verify(contributionMapper).insertIgnore(1L, 100L, new BigDecimal("42.50"),
                ContributionStatus.ACTIVE.getCode());
    }

    /** 规范差异「未通过不入榜」：RE_PASSED 可入榜；REJECTED/VERIFYING/SUBMITTED/RE_CONFIRMED 一律跳过 */
    @Test
    void applyVerified_rePassed_allowed_othersSkipped() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.RE_PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(1);
        service.applyVerified(1L);
        verify(zSetOps).incrementScore(anyString(), eq("100"), eq(42.5d));

        for (RecordStatus status : new RecordStatus[]{RecordStatus.REJECTED, RecordStatus.VERIFYING,
                RecordStatus.SUBMITTED, RecordStatus.RE_CONFIRMED}) {
            when(sportRecordMapper.selectById(2L)).thenReturn(record(status, "10.00"));
            service.applyVerified(2L);
        }
        verify(zSetOps, never()).incrementScore(anyString(), eq("100"), eq(10.0d));
        verify(zSetOps, never()).incrementScore(anyString(), anyString(), eq(10.0d));
    }

    /** 规范差异「事件幂等」：锚点已 ACTIVE（重复事件/重放）→ 不重复加分 */
    @Test
    void applyVerified_duplicate_idempotent() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(0);
        when(contributionMapper.reactivate(1L,
                ContributionStatus.ROLLED_BACK.getCode(),
                ContributionStatus.ACTIVE.getCode())).thenReturn(0);

        service.applyVerified(1L);

        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    /**
     * E24 R6：驳回回滚后再改判通过——applyVerified 对 ROLLED_BACK 锚点（insertIgnore=0 mock）
     * 走 {@code reactivate}（清 settled_at）而非 {@code updateStatus}：verify reactivate 被调、
     * updateStatus 零调用；credit=true 走 ZINCRBY 补加分（既有断言复用）。
     */
    @Test
    void applyVerified_reactivateAfterRollback_recredits() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.RE_PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(0);
        when(contributionMapper.reactivate(1L,
                ContributionStatus.ROLLED_BACK.getCode(),
                ContributionStatus.ACTIVE.getCode())).thenReturn(1);

        service.applyVerified(1L);

        // 重激活补加分走 ZINCRBY
        verify(zSetOps).incrementScore(LeaderboardService.OVERALL_ZSET_KEY, "100", 42.5d);
        // 判别式：重激活方向必须调 reactivate（清结算戳）而 updateStatus 零调用（保留戳只用于回滚方向）
        verify(contributionMapper).reactivate(1L,
                ContributionStatus.ROLLED_BACK.getCode(),
                ContributionStatus.ACTIVE.getCode());
        verify(contributionMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    /** 里程缺失（null/0）不入榜；记录不存在（脏事件）直接丢弃 */
    @Test
    void applyVerified_noDistance_orMissingRecord_skip() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, null));
        service.applyVerified(1L);
        when(sportRecordMapper.selectById(2L)).thenReturn(record(RecordStatus.PASSED, "0"));
        service.applyVerified(2L);
        when(sportRecordMapper.selectById(3L)).thenReturn(null);
        service.applyVerified(3L);

        verify(contributionMapper, never()).insertIgnore(anyLong(), anyLong(), any(), anyInt());
        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    /** 规范差异「回滚与入榜并发安全」：拿不到互斥锁 → 抛出让 MQ 重投（不跳过、不交错） */
    @Test
    void applyVerified_lockNotAcquired_throwsForRetry() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, "42.50"));

        assertThrows(IllegalStateException.class, () -> service.applyVerified(1L));
        verify(contributionMapper, never()).insertIgnore(anyLong(), anyLong(), any(), anyInt());
    }

    // ==================== 回滚（REJECTED/REVERSED：幂等 + 无贡献不回滚） ====================

    /** 规范差异「回滚里程」：ACTIVE 锚点 → ROLLED_BACK → ZINCRBY -distance（按锚点值精确扣回） */
    @Test
    void rollbackOnRejected_activeAnchor_rollsBack() {
        when(contributionMapper.selectById(1L)).thenReturn(anchor(100L, "42.50"));
        when(contributionMapper.updateStatus(1L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode())).thenReturn(1);

        service.rollbackOnRejected(1L);

        verify(zSetOps).incrementScore(LeaderboardService.OVERALL_ZSET_KEY, "100", -42.5d);
    }

    /** 规范差异「回滚幂等」：已 ROLLED_BACK（重复事件）→ 不重复扣分 */
    @Test
    void rollbackOnRejected_alreadyRolledBack_idempotent() {
        when(contributionMapper.selectById(1L)).thenReturn(anchor(100L, "42.50"));
        when(contributionMapper.updateStatus(1L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode())).thenReturn(0);

        service.rollbackOnRejected(1L);

        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    /** 规范差异「无贡献不回滚」：从未入榜（无锚点行）→ 跳过，不产生负里程 */
    @Test
    void rollbackOnRejected_noAnchor_skip() {
        when(contributionMapper.selectById(1L)).thenReturn(null);

        service.rollbackOnRejected(1L);

        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
        verify(contributionMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    // ==================== 总榜 / 好友榜 ====================

    /** 规范差异「总榜查询」：ZREVRANGE 前 N + 昵称补齐 + rank 从 1 起 */
    @Test
    void topOverall_assemblesRankAndNickname() {
        when(zSetOps.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 1L))
                .thenReturn(tuples("100", 42.5, "200", 20.0));
        when(userApi.listUsersByIds(List.of(100L, 200L)))
                .thenReturn(Result.success(List.of(user(100L, "阿强"), user(200L, "小美"))));

        List<LeaderboardDTO> board = service.top("overall", null, 2);

        assertEquals(2, board.size());
        assertEquals(1, board.get(0).getRank());
        assertEquals(100L, board.get(0).getUserId());
        assertEquals("阿强", board.get(0).getNickname());
        assertEquals(0, board.get(0).getDistance().compareTo(new BigDecimal("42.5")));
        assertEquals(2, board.get(1).getRank());
        assertEquals("小美", board.get(1).getNickname());
    }

    /** 昵称接口失败 → 以「用户{id}」占位降级，榜单仍可用 */
    @Test
    void topOverall_nicknameFailure_fallback() {
        when(zSetOps.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 0L))
                .thenReturn(tuples("100", 42.5));
        when(userApi.listUsersByIds(any())).thenThrow(new RuntimeException("user-service down"));

        List<LeaderboardDTO> board = service.top("OVERALL", null, 1);

        assertEquals("用户100", board.get(0).getNickname());
    }

    /** 规范差异「只显示好友」：Pipeline 只点查好友积分，非好友（300）不入榜；本人不在榜 */
    @Test
    void topFriend_filtersNonFriends() {
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(
                List.of(100L)));
        // Pipeline 单次往返：distinctFriendIds=[100]，点查 1 条 → 返回 [42.5]（非好友 300/200 根本不查询）
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of((Object) 42.5));

        List<LeaderboardDTO> board = service.top("friend", 200L, 10);

        assertEquals(1, board.size());
        assertEquals(100L, board.get(0).getUserId());
        assertEquals(1, board.get(0).getRank());
    }

    /** 好友榜拉取契约：好友总数达到 1001 时，单次拉齐全部好友 ID，正确完成 Pipeline 点查与排序。 */
    @Test
    void topFriend_handlesLargeFriendList_whenFriendsExceedOneThousand() {
        List<Long> allFriendIds = LongStream.rangeClosed(1, 1001).boxed().toList();
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(allFriendIds));
        // Pipeline 返回 1001 个点查结果：仅末位好友 1001 上榜（42.5），其余好友未上榜（null 被过滤）
        List<Object> scores = IntStream.rangeClosed(1, 1001)
                .mapToObj(i -> i == 1001 ? (Object) 42.5 : null)
                .toList();
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(scores);

        List<LeaderboardDTO> board = service.top("friend", 200L, 10);

        assertEquals(List.of(1001L), board.stream().map(LeaderboardDTO::getUserId).toList());
        assertEquals(1, board.get(0).getRank());
        verify(userApi).listFriendIds(200L);
    }

    /** UserApiFallback 返回 null 结果时，不得报错，优雅降级为空榜。 */
    @Test
    void topFriend_nullResultFallback_returnsEmptyInsteadOfError() {
        when(userApi.listFriendIds(200L)).thenReturn(null);
        // 好友未拉到 → 在进入 Pipeline 前已返回空榜
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of((Object) 42.5));

        List<LeaderboardDTO> board = service.top("friend", 200L, 10);

        assertTrue(board.isEmpty());
    }

    /** UserApiFallback 返回 null data 时，同样优雅降级为空榜。 */
    @Test
    void topFriend_nullDataFallback_returnsEmptyInsteadOfError() {
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(null));
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of((Object) 42.5));

        List<LeaderboardDTO> board = service.top("friend", 200L, 10);

        assertTrue(board.isEmpty());
    }

    /** Pipeline 单次往返：好友积分一次点查拉齐，不再整榜分批扫描——executePipelined 恰 1 次，ZREVRANGE 0 次。 */
    @Test
    void topFriend_usesPipeline_batchRetrievesFriendScoresInSingleRoundTrip() {
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(List.of(9999L)));
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of((Object) 1.0));

        List<LeaderboardDTO> board = service.top("friend", 200L, 10);

        assertEquals(1, board.size());
        assertEquals(9999L, board.get(0).getUserId());
        assertEquals(1, board.get(0).getRank());
        verify(redis).executePipelined(any(RedisCallback.class));
        verify(zSetOps, never()).reverseRangeWithScores(anyString(), anyLong(), anyLong());
    }

    /** Pipeline 排序契约：分数降序；同分按 member 字符串降序（对齐 ZREVRANGE 词典序回排）；
     *  未上榜好友（null 分数）被过滤；请求 size 截断。 */
    @Test
    void topFriend_sortsFriendsByScoreDescending_andTiesByMemberDescending() {
        // 好友 100/200/300 上榜：200→88.0，100/300 同分 42.5（member 降序 300 先于 100）；400 未上榜（null）
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(
                List.of(100L, 200L, 300L, 400L)));
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList((Object) 42.5, (Object) 88.0, (Object) 42.5, null));

        // size=2：截断后取分数最高两席 200（88.0）、300（42.5，同分 member 降序压过 100）
        List<LeaderboardDTO> top2 = service.top("friend", 200L, 2);
        assertEquals(List.of(200L, 300L), top2.stream().map(LeaderboardDTO::getUserId).toList());
        assertEquals(List.of(1, 2), top2.stream().map(LeaderboardDTO::getRank).toList());

        // size=4：完整排序 200 > 300 > 100；400 因 null 分数被过滤
        List<LeaderboardDTO> top4 = service.top("friend", 200L, 4);
        assertEquals(List.of(200L, 300L, 100L), top4.stream().map(LeaderboardDTO::getUserId).toList());
        assertEquals(List.of(1, 2, 3), top4.stream().map(LeaderboardDTO::getRank).toList());
    }

    /** 规范差异「无好友或未上榜」：无好友 / 好友均无里程 / Feign 失败 → 空榜降级 */
    @Test
    void topFriend_emptyOrDegraded_returnsEmpty() {
        // 无好友
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(List.of()));
        assertTrue(service.top("friend", 200L, 10).isEmpty());
        // Feign 失败 → 降级空榜
        when(userApi.listFriendIds(200L)).thenThrow(new RuntimeException("down"));
        assertTrue(service.top("friend", 200L, 10).isEmpty());
        // Pipeline 点查返回 null 分数（好友均无 pass 里程/未上榜）→ 候选为空 → 空榜
        // 注意：对已 thenThrow 的桩重打桩必须用 doReturn（when 写法会先触发旧桩抛异常）
        doReturn(Result.success(List.of(100L)))
                .when(userApi).listFriendIds(200L);
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(Collections.singletonList(null));
        assertTrue(service.top("friend", 200L, 10).isEmpty());
    }

    /** B23 防穿透：连续调用 2 次 topFriends 命中本地缓存，第二次 Feign 依赖次数不增 */
    @Test
    void topFriend_secondCallHitsLocalCache_friendFeignNotRepeated() {
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(
                List.of(100L, 200L)));
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList((Object) 42.5, (Object) 88.0));

        List<LeaderboardDTO> first = service.topFriends(200L, 10);
        List<LeaderboardDTO> second = service.topFriends(200L, 10);

        // 两次都返回完整好友榜
        assertEquals(List.of(200L, 100L), first.stream().map(LeaderboardDTO::getUserId).toList());
        assertEquals(List.of(200L, 100L), second.stream().map(LeaderboardDTO::getUserId).toList());
        // 关键：Feign（listFriendIds）与 Redis Pipeline 都仅被调用一次——第二次从本地缓存命中
        verify(userApi).listFriendIds(200L);
        verify(redis, org.mockito.Mockito.times(1)).executePipelined(any(RedisCallback.class));
    }

    /** 非法入参：未知 type / friend 缺 userId → 400（IllegalArgumentException） */
    @Test
    void top_invalidParams_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.top("week", null, 10));
        assertThrows(IllegalArgumentException.class, () -> service.top("friend", null, 10));
    }

    // ==================== 快照结算（防重 + 对账纠偏 + 孤儿自愈 + 防爆截断） ====================

    /** 规范差异「对账纠偏」：以 ACTIVE 汇总覆盖 ZSet + scan 游标清理残留成员 + 标记 settled_at + 容量截断 */
    @Test
    void settleAndReconcile_rebuildsZsetFromContributions() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5, "999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        // 孤儿对账默认无孤儿行（mock 空列表）
        verify(contributionMapper).selectOrphanContributions(ContributionStatus.ACTIVE.getCode());
        // 汇总覆盖（批量 ZADD 幂等纠偏）：成员=100，分数=42.5
        verify(zSetOps).add(eq(LeaderboardService.OVERALL_ZSET_KEY),
                argThat((Set<TypedTuple<String>> set) -> set.size() == 1
                        && set.iterator().next().getValue().equals("100")
                        && Double.compare(set.iterator().next().getScore(), 42.5d) == 0));
        // 无 ACTIVE 贡献的残留成员被清理
        verify(zSetOps).remove(LeaderboardService.OVERALL_ZSET_KEY, "999");
        // 标记 settled_at
        verify(contributionMapper).markSettled(eq(ContributionStatus.ACTIVE.getCode()),
                any(LocalDateTime.class));
    }

    /** 规范差异「多实例仅一个执行」：锁未获取 → 本轮跳过，不碰 DB/ZSet */
    @Test
    void settleAndReconcile_lockNotAcquired_skip() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        service.settleAndReconcile();

        verify(contributionMapper, never()).selectActiveSummaries(anyInt());
        verify(contributionMapper, never()).selectOrphanContributions(anyInt());
        verifyNoInteractions(zSetOps);
        verify(contributionMapper, never()).markSettled(anyInt(), any());
    }

    // ==================== 每日报表快照（TASK-108） ====================

    /** 结算成功后必须写一次当日快照，并清掉"今日已无 ACTIVE 贡献"的残留行 */
    @Test
    void settleAndReconcile_writesDailyReportSnapshotOnce() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        int active = ContributionStatus.ACTIVE.getCode();
        verify(dailySummaryMapper).upsertFromActiveContributions(active);
        verify(dailySummaryMapper).deleteStaleToday(active);
    }

    /** 锁没抢到＝整轮什么都不写，报表也不例外（多实例只有一份快照写入方） */
    @Test
    void settleAndReconcile_lockNotAcquired_doesNotTouchReport() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        service.settleAndReconcile();

        verify(dailySummaryMapper, never()).upsertFromActiveContributions(anyInt());
        verify(dailySummaryMapper, never()).deleteStaleToday(anyInt());
    }

    // ==================== B16 孤儿对账补偿自愈 ====================

    /**
     * B16 判别式：孤儿贡献（ACTIVE 但 sport_record 已删除）被识别并流转 ROLLED_BACK，
     * 且不再进入本轮汇总 target（ZADD 不含孤儿）。模拟 record-service Feign 回滚失败
     * 遗留的脏里程被定时结算离线自愈。
     */
    @Test
    void settleAndReconcile_rollsBackOrphanContributions() {
        // 孤儿：recordId=500 的 ACTIVE 贡献，但对应 sport_record 已不存在
        UserMileage orphan = new UserMileage();
        orphan.setUserId(200L);
        orphan.setRecordId(500L);
        orphan.setTotalDistance(new BigDecimal("18.00"));
        when(contributionMapper.selectOrphanContributions(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(orphan));
        // 只有非孤儿的 ACTIVE 汇总（100 的 42.50）
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        // 孤儿被识别并流转 ACTIVE → ROLLED_BACK
        verify(contributionMapper).updateStatus(500L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode());
        // 孤儿里程（200）未进入 ZADD 汇总 target：仅 100 被覆盖
        verify(zSetOps).add(eq(LeaderboardService.OVERALL_ZSET_KEY),
                argThat((Set<TypedTuple<String>> set) -> set.size() == 1
                        && set.iterator().next().getValue().equals("100")));
    }

    /**
     * B16 判别式：孤儿行若已在并发中被他进程回滚（updateStatus 影响 0 行），
     * 不重复处理、不中断结算，静默跳过（自愈幂等）。
     */
    @Test
    void settleAndReconcile_orphanAlreadyRolledBack_isIdempotent() {
        UserMileage orphan = new UserMileage();
        orphan.setUserId(200L);
        orphan.setRecordId(500L);
        orphan.setTotalDistance(new BigDecimal("18.00"));
        when(contributionMapper.selectOrphanContributions(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(orphan));
        when(contributionMapper.updateStatus(500L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode())).thenReturn(0);
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>());
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        // 影响 0 行即已回滚：不重复处理、不抛异常、结算正常走完（汇总/报表照常执行）
        verify(contributionMapper).updateStatus(500L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode());
        // 报告写入仍按惯例执行（孤儿自愈失败不阻断结算主链路）
        verify(dailySummaryMapper).upsertFromActiveContributions(ContributionStatus.ACTIVE.getCode());
    }

    // ==================== B18 防爆：scan 分批 + 容量截断 ====================

    /**
     * B18 判别式一：不再整榜 range(0,-1) 倾倒进 JVM 堆——改用 scan 游标分批遍历，
     * 未调用已废弃的 range 全量读取。
     */
    @Test
    void settleAndReconcile_usesScanNotFullDumpRange() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        verify(zSetOps, never()).range(anyString(), anyLong(), anyLong());
        verify(zSetOps).scan(anyString(), any());
        verify(zSetOps).remove(LeaderboardService.OVERALL_ZSET_KEY, "999");
    }

    /**
     * B18 判别式二：结算末尾对总榜执行容量截断保护（removeRange 保留前 OVERALL_ZSET_MAX 名），
     * 且仅在成员超过上限时实际截断。
     */
    @Test
    void settleAndReconcile_trimsZsetCapacityWhenOverLimit() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        // 构造一批（含 ACTIVE 100 + 幽灵 999）触发清理，仍保留截断调用
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5, "999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        // 容量截断：removeRange(key, 0, -(OVERALL_ZSET_MAX + 1))。OVERALL_ZSET_MAX 未公开，
        // 以 removeRange 被调用且起点 0 为契约锚点（保留前 N 名的语义由服务实现保证）。
        verify(zSetOps).removeRange(eq(LeaderboardService.OVERALL_ZSET_KEY), eq(0L), anyLong());
    }

    // ==================== 每日报表快照（TASK-108） ====================

    /** 报表读取：按 Mapper 返回序编 rank，昵称走总榜同一降级口径；size 有上限 */
    @Test
    void dailyReport_ranksRowsAndClampsSize() {
        LocalDate day = LocalDate.of(2026, 9, 20);
        when(dailySummaryMapper.selectTopByDate(any(), anyInt())).thenReturn(List.of(
                dailyRow(100L, "88.05"), dailyRow(101L, "12.50")));

        List<LeaderboardDTO> top = service.dailyReport(day, 20);

        verify(dailySummaryMapper).selectTopByDate(day, 20);
        assertEquals(2, top.size());
        assertEquals(1, top.get(0).getRank());
        assertEquals(100L, top.get(0).getUserId());
        assertEquals(new BigDecimal("88.05"), top.get(0).getDistance());
        // userApi 未打桩 → 降级占位昵称，与总榜同口径（报表不能因 user-service 挂了就没数据）
        assertEquals("用户100", top.get(0).getNickname());
        assertEquals(2, top.get(1).getRank());

        service.dailyReport(day, 9999);
        verify(dailySummaryMapper).selectTopByDate(day, 500);
    }

    private LeaderboardDailySummary dailyRow(long userId, String distance) {
        LeaderboardDailySummary row = new LeaderboardDailySummary();
        row.setUserId(userId);
        row.setTotalDistance(new BigDecimal(distance));
        return row;
    }

    // ==================== 工具 ====================

    private SportRecordSnapshot record(RecordStatus status, String distance) {
        SportRecordSnapshot r = new SportRecordSnapshot();
        r.setId(1L);
        r.setUserId(100L);
        r.setStatus(status.getCode());
        r.setDistance(distance == null ? null : new BigDecimal(distance));
        return r;
    }

    private LeaderboardContribution anchor(Long userId, String distance) {
        LeaderboardContribution c = new LeaderboardContribution();
        c.setRecordId(1L);
        c.setUserId(userId);
        c.setDistance(new BigDecimal(distance));
        c.setStatus(ContributionStatus.ACTIVE.getCode());
        return c;
    }

    private UserDTO user(Long id, String nickname) {
        UserDTO u = new UserDTO();
        u.setId(id);
        u.setNickname(nickname);
        return u;
    }

    private FriendDTO friend(Long userId) {
        FriendDTO f = new FriendDTO();
        f.setUserId(userId);
        f.setNickname("好友" + userId);
        return f;
    }

    // ==================== 新增边界场景测试 ====================

    /** 规范差异「通过校验」：null 状态不被视为通过 */
    @Test
    void applyVerified_nullStatus_notPassed() {
        SportRecordSnapshot r = new SportRecordSnapshot();
        r.setId(1L);
        r.setUserId(100L);
        r.setStatus(null);
        r.setDistance(new BigDecimal("42.50"));
        when(sportRecordMapper.selectById(1L)).thenReturn(r);
        service.applyVerified(1L);
        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    /** 残留成员清理：ZSet 有幽灵分数（scan 遍历识别并分批 ZREM） */
    @Test
    void settleAndReconcile_clearStaleMembers() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        // ZSet 中有 ACTIVE 用户和无贡献的幽灵用户
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5, "999", 30.0, "888", 20.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        // 只清理非 ACTIVE 成员
        verify(zSetOps).remove(LeaderboardService.OVERALL_ZSET_KEY, "999", "888");
    }

    /** 无汇总数据：仍执行清理残留 */
    @Test
    void settleAndReconcile_noSummaries_onlyCleanup() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("999", 30.0, "888", 20.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        verify(zSetOps).remove(LeaderboardService.OVERALL_ZSET_KEY, "999", "888");
        verify(contributionMapper, never()).markSettled(anyInt(), any());
    }

    /** topOverall 空榜：直接返回空列表 */
    @Test
    void topOverall_emptyZset_returnsEmpty() {
        when(zSetOps.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 49L))
                .thenReturn(null);

        List<LeaderboardDTO> board = service.topOverall(50);

        assertTrue(board.isEmpty());
    }

    /** top 非法 type：抛出 IllegalArgumentException */
    @Test
    void top_unknownType_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> service.top("invalid", null, 10));
        assertThrows(IllegalArgumentException.class, () -> service.top("", null, 10));
    }

    /** 构造 ZSet 元组（按分数降序传入，模拟 ZREVRANGE 返回序） */
    @SafeVarargs
    private Set<TypedTuple<String>> tuples(Object... valueScorePairs) {
        Set<TypedTuple<String>> set = new LinkedHashSet<>();
        for (int i = 0; i < valueScorePairs.length; i += 2) {
            set.add(TypedTuple.of((String) valueScorePairs[i], ((Number) valueScorePairs[i + 1]).doubleValue()));
        }
        return set;
    }

    /**
     * B18：把批次成员集合包装成 {@link Cursor}，模拟 {@code opsForZSet().scan(...)} 的返回。
     * 用 CALLS_REAL_METHODS 使 {@code Iterator.forEachRemaining} 默认方法真实委托被 mock 的
     * hasNext/next；结算任务只消费 ≤批次(500) 的一页即终止，故 mock 一次迭代足够。
     */
    @SuppressWarnings("unchecked")
    private Cursor<TypedTuple<String>> cursor(Set<TypedTuple<String>> members) {
        Cursor<TypedTuple<String>> c = mock(Cursor.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        int[] i = {0};
        TypedTuple<String>[] arr = members.toArray(new TypedTuple[0]);
        when(c.hasNext()).thenAnswer(inv -> i[0] < arr.length);
        when(c.next()).thenAnswer(inv -> arr[i[0]++]);
        return c;
    }

    // ==================== 日期范围榜单（TASK-209） ====================

    /** R1a：多用户多行 ACTIVE 贡献入窗 → 按窗口内 SUM 降序、rank 从 1、昵称补齐 */
    @Test
    void rangeReport_multiUserWindow_sumsDescartesRankAndNickname() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 7);
        when(contributionMapper.selectRangeSummaries(ContributionStatus.ACTIVE.getCode(),
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), 20))
                .thenReturn(List.of(rangeRow(100L, "88.05"), rangeRow(200L, "12.50")));
        when(userApi.listUsersByIds(List.of(100L, 200L)))
                .thenReturn(Result.success(List.of(user(100L, "阿强"), user(200L, "小美"))));

        List<LeaderboardDTO> top = service.rangeReport(start, end, 20);

        assertEquals(2, top.size());
        assertEquals(1, top.get(0).getRank());
        assertEquals(100L, top.get(0).getUserId());
        assertEquals("阿强", top.get(0).getNickname());
        assertEquals(0, top.get(0).getDistance().compareTo(new BigDecimal("88.05")));
        assertEquals(2, top.get(1).getRank());
        assertEquals(200L, top.get(1).getUserId());
        assertEquals("小美", top.get(1).getNickname());
    }

    /** R1b：半开边界——start 当日 0 点计入；end+1 日 0 点为排他上界（半开窗口） */
    @Test
    void rangeReport_halfOpenWindowBounds_usesEndExclusive() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 7);
        when(contributionMapper.selectRangeSummaries(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of(rangeRow(100L, "42.50")));

        service.rangeReport(start, end, 10);

        verify(contributionMapper).selectRangeSummaries(ContributionStatus.ACTIVE.getCode(),
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 8, 0, 0), 10);
    }

    /** R1c：非 ACTIVE（ROLLED_BACK）不入窗——状态过滤全交给 Mapper，服务端聚合只含 ACTIVE 汇总 */
    @Test
    void rangeReport_activeOnly_rowsFromMapper() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 7);
        when(contributionMapper.selectRangeSummaries(ContributionStatus.ACTIVE.getCode(),
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), 10))
                .thenReturn(List.of(rangeRow(100L, "30.00")));

        List<LeaderboardDTO> top = service.rangeReport(start, end, 10);

        assertEquals(1, top.size());
        assertEquals(100L, top.get(0).getUserId());
        verify(contributionMapper).selectRangeSummaries(ContributionStatus.ACTIVE.getCode(),
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), 10);
    }

    /** R1e：size 默认与上限——size<=0 退回默认 50；size>500 收敛为 500（与 dailyReport 同口径） */
    @Test
    void rangeReport_clampsSizeDefaultAndMax() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 7);
        when(contributionMapper.selectRangeSummaries(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.rangeReport(start, end, 0);
        verify(contributionMapper).selectRangeSummaries(ContributionStatus.ACTIVE.getCode(),
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), 50);

        service.rangeReport(start, end, 9999);
        verify(contributionMapper).selectRangeSummaries(ContributionStatus.ACTIVE.getCode(),
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), 500);
    }

    /** 空窗：返回空列表 */
    @Test
    void rangeReport_emptyWindow_returnsEmpty() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 7);
        when(contributionMapper.selectRangeSummaries(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());

        assertTrue(service.rangeReport(start, end, 10).isEmpty());
    }

    private UserMileage rangeRow(long userId, String distance) {
        UserMileage row = new UserMileage();
        row.setUserId(userId);
        row.setTotalDistance(new BigDecimal(distance));
        return row;
    }

    // ==================== 结算标记 SQL 契约（TASK-235 时间窗口修复） ====================

    /**
     * R1 判别式：markSettled 的 @Update SQL 必须带 {@code settled_at IS NULL}——
     * 只对尚未结算的 ACTIVE 贡献行盖戳，绝不覆盖既有历史结算时间。若重复结算会覆盖
     * 历史行 settled_at，则 selectRangeSummaries 的今日/本周/本月窗口全部失真
     * （历史行被卷进错误周期）；本测试守卫注解 SQL 文本，真实 SQL 行为由库表约束兜底。
     */
    @Test
    void markSettled_sqlOnlyStampsUnsettledRows() throws Exception {
        Method m = LeaderboardContributionMapper.class.getMethod("markSettled",
                Integer.class, LocalDateTime.class);

        Update update = m.getAnnotation(Update.class);
        assertNotNull(update, "markSettled 应为 @Update 注解 SQL");
        String sql = String.join(" ", update.value());
        assertTrue(sql.contains("settled_at IS NULL"),
                "仅对尚未结算的行盖戳（不得覆盖历史结算时间），实测 SQL=" + sql);
        assertTrue(sql.contains("SET settled_at"), "结算时间戳写入仍在 SET 子句，实测 SQL=" + sql);
        assertTrue(sql.contains("WHERE status"), "ACTIVE 状态过滤保留，实测 SQL=" + sql);
    }

    // ==================== D1 总榜缓存失效联动（TASK-249） ====================

    /** D1：新锚点入榜（credit=true）→ ZINCRBY 后恰一次失效总榜缓存 */
    @Test
    void applyVerified_creditTrue_evictsOverallCacheOnce() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(1);

        service.applyVerified(1L);

        verify(overallCache, times(1)).clear();
    }

    /** D1：重复 VERIFIED（幂等跳过）→ 不触发缓存失效，避免无效缓存抖动 */
    @Test
    void applyVerified_idempotentSkip_doesNotEvictCache() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(0);
        when(contributionMapper.reactivate(1L,
                ContributionStatus.ROLLED_BACK.getCode(),
                ContributionStatus.ACTIVE.getCode())).thenReturn(0);

        service.applyVerified(1L);

        verify(overallCache, never()).clear();
    }

    /** D1：回滚命中 ACTIVE 锚点（rows==1）→ ZINCRBY 扣分后恰一次失效总榜缓存 */
    @Test
    void rollbackOnRejected_rowsOne_evictsOverallCacheOnce() {
        when(contributionMapper.selectById(1L)).thenReturn(anchor(100L, "42.50"));
        when(contributionMapper.updateStatus(1L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode())).thenReturn(1);

        service.rollbackOnRejected(1L);

        verify(overallCache, times(1)).clear();
    }

    /** D1：回滚幂等（rows==0，重复事件）→ 不触发缓存失效 */
    @Test
    void rollbackOnRejected_rowsZero_doesNotEvictCache() {
        when(contributionMapper.selectById(1L)).thenReturn(anchor(100L, "42.50"));
        when(contributionMapper.updateStatus(1L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode())).thenReturn(0);

        service.rollbackOnRejected(1L);

        verify(overallCache, never()).clear();
    }

    /** D1：缓存失效抛异常（Redis 抖动）→ best-effort 不抛出，入榜加分主链路照常完成 */
    @Test
    void applyVerified_evictThrows_doesNotBlockCrediting() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(1);
        when(cacheManager.getCache(anyString())).thenThrow(new IllegalStateException("cache down"));

        service.applyVerified(1L);

        verify(zSetOps).incrementScore(LeaderboardService.OVERALL_ZSET_KEY, "100", 42.5d);
    }

    /** D1：结算 ZSet 纠偏完成后恰一次失效总榜缓存 */
    @Test
    void settleAndReconcile_evictsOverallCacheOnce() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        verify(overallCache, times(1)).clear();
    }

    // ==================== D3 结算 scan 游标单遍重构（TASK-249） ====================

    /** D3：清理段单次 scan（times(1)），不再每轮循环重开游标 */
    @Test
    void settleAndReconcile_scanInvokedExactlyOnce() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);

        service.settleAndReconcile();

        verify(zSetOps, times(1)).scan(anyString(), any(ScanOptions.class));
    }

    /** D3：成员数超批次（600 &gt; 500）单遍迭代自然终止，stale 全量分批 ZREM（不再有终止判据死循环） */
    @Test
    void settleAndReconcile_overBatchSize_singlePassRemovesAllStale() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Set<TypedTuple<String>> members = new LinkedHashSet<>();
        for (int i = 0; i < 600; i++) {
            members.add(TypedTuple.of(String.valueOf(1000 + i), 1.0 + i));
        }
        Cursor<TypedTuple<String>> cur = cursor(members);
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);
        List<String> removed = recordRemovedMembers();

        service.settleAndReconcile();

        // 单遍迭代：scan 恰一次（旧实现该判据在成员 ≥ 批次时恒真 → 结算不可终止）
        verify(zSetOps, times(1)).scan(anyString(), any(ScanOptions.class));
        // stale 全量分批 ZREM（600 = 500 + 100 两批），无遗漏无重复
        assertEquals(600, removed.size());
        assertEquals(600, removed.stream().distinct().count());
    }

    /** D3：target 全含当前成员 → 零 ZREM（残留清理不误删 ACTIVE 成员） */
    @Test
    void settleAndReconcile_allMembersActive_noStaleRemoval() {
        UserMileage m1 = new UserMileage();
        m1.setUserId(100L);
        m1.setTotalDistance(new BigDecimal("42.50"));
        UserMileage m2 = new UserMileage();
        m2.setUserId(200L);
        m2.setTotalDistance(new BigDecimal("18.00"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m1, m2));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5, "200", 18.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);
        List<String> removed = recordRemovedMembers();

        service.settleAndReconcile();

        assertTrue(removed.isEmpty());
    }

    /** D3：scan 返回 null（尽力而为采集）→ 清理段跳过，其余结算步骤照常执行 */
    @Test
    void settleAndReconcile_scanNull_otherStepsProceed() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(null);

        service.settleAndReconcile();

        // 汇总覆盖 / 容量截断 / settled_at 标记 / 报表写入均照常
        verify(zSetOps).add(eq(LeaderboardService.OVERALL_ZSET_KEY),
                argThat((Set<TypedTuple<String>> set) -> set.size() == 1
                        && set.iterator().next().getValue().equals("100")));
        verify(zSetOps).removeRange(eq(LeaderboardService.OVERALL_ZSET_KEY), eq(0L), anyLong());
        verify(contributionMapper).markSettled(eq(ContributionStatus.ACTIVE.getCode()),
                any(LocalDateTime.class));
        verify(dailySummaryMapper).upsertFromActiveContributions(ContributionStatus.ACTIVE.getCode());
    }

    /** 记录 ZREM 出榜的成员（按 flush 次数展开 varargs），用于断言 stale 清理口径 */
    private List<String> recordRemovedMembers() {
        List<String> removed = new ArrayList<>();
        doAnswer(inv -> {
            // ZSetOperations.remove(K, Object...) 为 varargs：Mockito 可能把末位数组展开为
            // 逐个参数，也可能原样保留为单个 Object[]，两种形态都要兼容采集
            Object[] args = inv.getArguments();
            for (int k = 1; k < args.length; k++) {
                Object arg = args[k];
                if (arg instanceof Object[] vals) {
                    for (Object v : vals) {
                        removed.add((String) v);
                    }
                } else if (arg != null) {
                    removed.add((String) arg);
                }
            }
            return 0L;
        }).when(zSetOps).remove(anyString(), any(Object[].class));
        return removed;
    }

    // ==================== R3/E7 结算 ZREM 二次复核（TASK-253） ====================

    /** ① 候选复核时已在 DB（结算 SELECT 后、scan 期间新入榜）→ 保留不 ZREM */
    @Test
    void settleAndReconcile_staleCandidateFoundInDb_keptNotZrem() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5, "999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);
        // 复核命中：999 的 ACTIVE 贡献行已在 DB 落定（applyVerified 先写 DB 后 ZINCRBY）
        when(contributionMapper.selectActiveUserIds(eq(ContributionStatus.ACTIVE.getCode()), anyList()))
                .thenReturn(List.of(999L));
        List<String> removed = recordRemovedMembers();

        service.settleAndReconcile();

        // 复核命中 → 保留：零 ZREM（不误删竞态窗口内新入榜成员）
        assertTrue(removed.isEmpty());
        verify(contributionMapper).selectActiveUserIds(eq(ContributionStatus.ACTIVE.getCode()),
                eq(List.of(999L)));
    }

    /** ② 复核仍缺（真残留）→ ZREM 执行，清理计数正确 */
    @Test
    void settleAndReconcile_staleStillMissing_zremWithCorrectCount() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("999", 30.0, "888", 20.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);
        // 复核仍缺：DB 无任何 ACTIVE 行 → 全部是真残留
        when(contributionMapper.selectActiveUserIds(eq(ContributionStatus.ACTIVE.getCode()), anyList()))
                .thenReturn(List.of());
        List<String> removed = recordRemovedMembers();

        service.settleAndReconcile();

        assertEquals(2, removed.size());
        assertEquals(2, removed.stream().distinct().count());
        verify(zSetOps).remove(LeaderboardService.OVERALL_ZSET_KEY, "999", "888");
    }

    /** ③ 复核查询异常 → 保守跳过本批 ZREM（宁漏删不误删），其余结算步骤照常 */
    @Test
    void settleAndReconcile_recheckException_skipsBatchZrem() {
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of());
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);
        when(contributionMapper.selectActiveUserIds(eq(ContributionStatus.ACTIVE.getCode()), anyList()))
                .thenThrow(new RuntimeException("DB 抖动"));

        service.settleAndReconcile();

        // 零 ZREM（漏删者下轮收敛）
        verify(zSetOps, never()).remove(anyString(), any(Object[].class));
        // 其余结算步骤照常：容量截断 / 报表写入
        verify(zSetOps).removeRange(eq(LeaderboardService.OVERALL_ZSET_KEY), eq(0L), anyLong());
        verify(dailySummaryMapper).upsertFromActiveContributions(ContributionStatus.ACTIVE.getCode());
    }

    /** ④ 二次复核启用下，孤儿回滚 / 汇总覆盖 / 容量截断 / settled 标记 / 报表断言全保持 */
    @Test
    void settleAndReconcile_recheckKeepsOrphanAndReportChain() {
        UserMileage orphan = new UserMileage();
        orphan.setUserId(200L);
        orphan.setRecordId(500L);
        orphan.setTotalDistance(new BigDecimal("18.00"));
        when(contributionMapper.selectOrphanContributions(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(orphan));
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        Cursor<TypedTuple<String>> cur = cursor(new LinkedHashSet<>(tuples("100", 42.5, "999", 30.0)));
        when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cur);
        // 999 复核仍缺 → 出榜；100 在 target 不入 stale
        when(contributionMapper.selectActiveUserIds(eq(ContributionStatus.ACTIVE.getCode()), anyList()))
                .thenReturn(List.of());

        service.settleAndReconcile();

        // 孤儿回滚 + 汇总覆盖（仅 100，不含孤儿 200）+ 残留清理（999）+ 截断 + settled + 报表
        verify(contributionMapper).updateStatus(500L,
                ContributionStatus.ACTIVE.getCode(),
                ContributionStatus.ROLLED_BACK.getCode());
        verify(zSetOps).add(eq(LeaderboardService.OVERALL_ZSET_KEY),
                argThat((Set<TypedTuple<String>> set) -> set.size() == 1
                        && set.iterator().next().getValue().equals("100")));
        verify(zSetOps).remove(LeaderboardService.OVERALL_ZSET_KEY, "999");
        verify(zSetOps).removeRange(eq(LeaderboardService.OVERALL_ZSET_KEY), eq(0L), anyLong());
        verify(contributionMapper).markSettled(eq(ContributionStatus.ACTIVE.getCode()),
                any(LocalDateTime.class));
        verify(dailySummaryMapper).upsertFromActiveContributions(ContributionStatus.ACTIVE.getCode());
    }
}
