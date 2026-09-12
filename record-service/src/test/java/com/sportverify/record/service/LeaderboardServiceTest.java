package com.sportverify.record.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.api.common.PageResult;
import com.sportverify.common.result.Result;
import com.sportverify.record.entity.LeaderboardContribution;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.enums.ContributionStatus;
import com.sportverify.record.mapper.LeaderboardContributionMapper;
import com.sportverify.record.mapper.LeaderboardContributionMapper.UserMileage;
import com.sportverify.record.mapper.SportRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 排行榜服务单元测试（规范差异：仅 pass 入榜 / 事件幂等 / 改判回滚 /
 * 回滚幂等 / 无贡献不回滚 / 总榜 / 好友榜过滤与降级 / 快照结算防重与纠偏）。
 *
 * <p>用 Mockito 打桩 Mapper / StringRedisTemplate / RedissonClient（RLock 恒可获取）/
 * UserApi，覆盖各业务分支；不依赖真实 Redis/MySQL/MQ（与点赞模块单测同款风格）。</p>
 */
class LeaderboardServiceTest {

    private SportRecordMapper sportRecordMapper;
    private LeaderboardContributionMapper contributionMapper;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private RedissonClient redissonClient;
    private RLock lock;
    private UserApi userApi;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        contributionMapper = mock(LeaderboardContributionMapper.class);
        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        userApi = mock(UserApi.class);

        when(redis.opsForZSet()).thenReturn(zSetOps);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        service = new LeaderboardService(sportRecordMapper, contributionMapper,
                redis, redissonClient, userApi);
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
        when(contributionMapper.updateStatus(1L,
                ContributionStatus.ROLLED_BACK.getCode(),
                ContributionStatus.ACTIVE.getCode())).thenReturn(0);

        service.applyVerified(1L);

        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    /** 驳回回滚后再改判通过：锚点 ROLLED_BACK → 重激活补加分（T8 前半段） */
    @Test
    void applyVerified_reactivateAfterRollback_recredits() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.RE_PASSED, "42.50"));
        when(contributionMapper.insertIgnore(eq(1L), eq(100L), any(), anyInt())).thenReturn(0);
        when(contributionMapper.updateStatus(1L,
                ContributionStatus.ROLLED_BACK.getCode(),
                ContributionStatus.ACTIVE.getCode())).thenReturn(1);

        service.applyVerified(1L);

        verify(zSetOps).incrementScore(LeaderboardService.OVERALL_ZSET_KEY, "100", 42.5d);
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

    /** 规范差异「只显示好友」：ZSet 中非好友（300）被过滤，仅好友（100）上榜；本人不在榜 */
    @Test
    void topFriend_filtersNonFriends() {
        when(userApi.listFriends(200L, 1L, 1000L)).thenReturn(Result.success(
                new PageResult<>(1, 1000, 1, List.of(friend(100L)))));
        when(zSetOps.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, -1))
                .thenReturn(tuples("300", 88.0, "100", 42.5, "200", 30.0));

        List<LeaderboardDTO> board = service.top("friend", 200L, 10);

        assertEquals(1, board.size());
        assertEquals(100L, board.get(0).getUserId());
        assertEquals(1, board.get(0).getRank());
    }

    /** 规范差异「无好友或未上榜」：无好友 / 好友均无里程 / Feign 失败 → 空榜降级 */
    @Test
    void topFriend_emptyOrDegraded_returnsEmpty() {
        // 无好友
        when(userApi.listFriends(200L, 1L, 1000L)).thenReturn(Result.success(
                new PageResult<>(1, 1000, 0, List.of())));
        assertTrue(service.top("friend", 200L, 10).isEmpty());
        // Feign 失败 → 降级空榜
        when(userApi.listFriends(200L, 1L, 1000L)).thenThrow(new RuntimeException("down"));
        assertTrue(service.top("friend", 200L, 10).isEmpty());
        // ZSet 空（好友均无 pass 里程）
        // 注意：对已 thenThrow 的桩重打桩必须用 doReturn（when 写法会先触发旧桩抛异常）
        doReturn(Result.success(new PageResult<>(1, 1000, 1, List.of(friend(100L)))))
                .when(userApi).listFriends(200L, 1L, 1000L);
        when(zSetOps.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, -1))
                .thenReturn(null);
        assertTrue(service.top("friend", 200L, 10).isEmpty());
    }

    /** 非法入参：未知 type / friend 缺 userId → 400（IllegalArgumentException） */
    @Test
    void top_invalidParams_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.top("week", null, 10));
        assertThrows(IllegalArgumentException.class, () -> service.top("friend", null, 10));
    }

    // ==================== 快照结算（防重 + 对账纠偏） ====================

    /** 规范差异「对账纠偏」：以 ACTIVE 汇总覆盖 ZSet + 清理残留成员 + 标记 settled_at */
    @Test
    void settleAndReconcile_rebuildsZsetFromContributions() {
        UserMileage m = new UserMileage();
        m.setUserId(100L);
        m.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode()))
                .thenReturn(List.of(m));
        when(zSetOps.range(LeaderboardService.OVERALL_ZSET_KEY, 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of("100", "999")));

        service.settleAndReconcile();

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
        verifyNoInteractions(zSetOps);
        verify(contributionMapper, never()).markSettled(anyInt(), any());
    }

    // ==================== 工具 ====================

    private SportRecord record(RecordStatus status, String distance) {
        SportRecord r = new SportRecord();
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

    /** 构造 ZSet 元组（按分数降序传入，模拟 ZREVRANGE 返回序） */
    @SafeVarargs
    private Set<TypedTuple<String>> tuples(Object... valueScorePairs) {
        Set<TypedTuple<String>> set = new LinkedHashSet<>();
        for (int i = 0; i < valueScorePairs.length; i += 2) {
            set.add(TypedTuple.of((String) valueScorePairs[i], ((Number) valueScorePairs[i + 1]).doubleValue()));
        }
        return set;
    }
}
