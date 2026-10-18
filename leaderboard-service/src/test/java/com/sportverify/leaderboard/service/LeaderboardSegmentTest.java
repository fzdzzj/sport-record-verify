package com.sportverify.leaderboard.service;

import com.sportverify.api.mapmatch.dto.SegmentEffortDTO;
import com.sportverify.api.mapmatch.dto.SegmentLeaderboardDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.entity.SportRecordSnapshot;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 赛段 KOM 榜单元测试（TASK-272）：成绩上报（PR 维护 / KOM 判定 / 持平不重写 / 劣于自身不写）
 * 与榜单查询（升序 rank / 昵称批量补齐 / 附属 Hash 回指 recordId / 配速派生 / 空榜与缺档降级）。
 *
 * <p>用 Mockito 打桩 StringRedisTemplate（ZSet/Hash 两视图）/ UserApi / SportRecordMapper /
 * CacheManager，不依赖真实 Redis/MySQL（与 {@code LeaderboardServiceTest} 同款风格）；
 * 构造器 7 参与主测试类同序，LeaderboardService 无新增依赖。</p>
 */
class LeaderboardSegmentTest {

    private static final String KEY = LeaderboardService.SEGMENT_ZSET_KEY_PREFIX + 1;
    private static final String RECORD_KEY = KEY + LeaderboardService.SEGMENT_RECORD_HASH_SUFFIX;

    private SportRecordMapper sportRecordMapper;
    private LeaderboardContributionMapper contributionMapper;
    private LeaderboardDailySummaryMapper dailySummaryMapper;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private HashOperations<String, Object, Object> hashOps;
    private UserApi userApi;
    private CacheManager cacheManager;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        contributionMapper = mock(LeaderboardContributionMapper.class);
        dailySummaryMapper = mock(LeaderboardDailySummaryMapper.class);
        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        hashOps = mock(HashOperations.class);
        userApi = mock(UserApi.class);
        cacheManager = mock(CacheManager.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new LeaderboardService(sportRecordMapper, contributionMapper, dailySummaryMapper,
                redis, mock(RedissonClient.class), userApi, cacheManager);
    }

    // ==================== 成绩上报（PR 维护 / KOM 判定） ====================

    @Test
    void recordSegmentEffort_firstEffort_writesZSetAndHash_reportsPrAndKom() {
        when(zSetOps.score(KEY, "100")).thenReturn(null);
        when(zSetOps.rank(KEY, "100")).thenReturn(0L);

        SegmentEffortDTO effort = service.recordSegmentEffort(1L, 100L, 55L, 480L);

        verify(zSetOps).add(KEY, "100", 480.0);
        verify(hashOps).put(RECORD_KEY, "100", "55");
        assertEquals(1L, effort.getSegmentId());
        assertEquals(100L, effort.getUserId());
        assertEquals(55L, effort.getRecordId());
        assertEquals(480L, effort.getElapsedSeconds());
        assertTrue(effort.getIsPr(), "首报即个人最好成绩");
        assertTrue(effort.getIsKom(), "升序榜 rank=0 即全场第一（KOM）");
    }

    @Test
    void recordSegmentEffort_betterTime_overwritesPrAndJudgesKom() {
        when(zSetOps.score(KEY, "100")).thenReturn(600.0);
        when(zSetOps.rank(KEY, "100")).thenReturn(0L);

        SegmentEffortDTO effort = service.recordSegmentEffort(1L, 100L, 55L, 480L);

        verify(zSetOps).add(KEY, "100", 480.0);
        verify(hashOps).put(RECORD_KEY, "100", "55");
        assertTrue(effort.getIsPr());
        assertTrue(effort.getIsKom());
    }

    @Test
    void recordSegmentEffort_worseTime_keepsPrWithoutAnyWrite() {
        when(zSetOps.score(KEY, "100")).thenReturn(450.0);

        SegmentEffortDTO effort = service.recordSegmentEffort(1L, 100L, 55L, 480L);

        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
        assertFalse(effort.getIsPr(), "劣于自身最好成绩不报 PR");
        assertFalse(effort.getIsKom(), "未刷新 PR 恒非 KOM（当前用时未进榜首语义）");
    }

    @Test
    void recordSegmentEffort_tieWithOwnPr_reportsPrWithoutRewrite() {
        when(zSetOps.score(KEY, "100")).thenReturn(480.0);
        when(zSetOps.rank(KEY, "100")).thenReturn(0L);

        SegmentEffortDTO effort = service.recordSegmentEffort(1L, 100L, 55L, 480L);

        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
        assertTrue(effort.getIsPr(), "与自身最好成绩持平（重放/重访）仍报 PR");
        assertTrue(effort.getIsKom(), "持平者仍持有升序榜首（rank=0）");
    }

    @Test
    void recordSegmentEffort_updatedButNotKom_reportsPrOnly() {
        when(zSetOps.score(KEY, "100")).thenReturn(null);
        when(zSetOps.rank(KEY, "100")).thenReturn(3L);

        SegmentEffortDTO effort = service.recordSegmentEffort(1L, 100L, 55L, 480L);

        verify(zSetOps).add(KEY, "100", 480.0);
        assertTrue(effort.getIsPr());
        assertFalse(effort.getIsKom(), "rank=3 非全场第一");
    }

    @Test
    void recordSegmentEffort_illegalArgs_throwsWithoutRedisTouch() {
        assertThrows(IllegalArgumentException.class, () -> service.recordSegmentEffort(null, 100L, 55L, 480L));
        assertThrows(IllegalArgumentException.class, () -> service.recordSegmentEffort(1L, null, 55L, 480L));
        assertThrows(IllegalArgumentException.class, () -> service.recordSegmentEffort(1L, 100L, 55L, 0L));
        assertThrows(IllegalArgumentException.class, () -> service.recordSegmentEffort(1L, 100L, 55L, null));
        verifyNoInteractions(redis);
    }

    // ==================== 榜单查询（升序 rank / 回指 / 配速派生） ====================

    @Test
    void getSegmentLeaderboard_ascendingRanksWithNicknameRecordAndPace() {
        when(zSetOps.rangeWithScores(KEY, 0, 49L)).thenReturn(ordered(
                tuple("2", 300), tuple("1", 360), tuple("3", 420)));
        Map<Object, Object> recordRows = new LinkedHashMap<>();
        recordRows.put("2", "21");
        recordRows.put("1", "20");
        recordRows.put("3", "22");
        when(hashOps.entries(RECORD_KEY)).thenReturn(recordRows);
        when(sportRecordMapper.selectById(21L)).thenReturn(snapshot("1.0"));
        when(sportRecordMapper.selectById(20L)).thenReturn(snapshot("2.0"));
        when(sportRecordMapper.selectById(22L)).thenReturn(snapshot(null));
        when(userApi.listUsersByIds(List.of(2L, 1L, 3L))).thenReturn(Result.success(List.of(
                user(2L, "跑者乙"), user(1L, "跑者甲"), user(3L, "跑者丙"))));

        List<SegmentLeaderboardDTO> board = service.getSegmentLeaderboard(1L, 50);

        assertEquals(3, board.size());
        SegmentLeaderboardDTO kom = board.get(0);
        assertEquals(1, kom.getRank());
        assertEquals(2L, kom.getUserId());
        assertEquals("跑者乙", kom.getNickname());
        assertEquals(300L, kom.getElapsedSeconds());
        assertEquals(21L, kom.getRecordId());
        assertEquals("5'00\"", kom.getPaceFormatted(), "配速 = 300s / 1.0km");
        SegmentLeaderboardDTO second = board.get(1);
        assertEquals(2, second.getRank());
        assertEquals(360L, second.getElapsedSeconds());
        assertEquals("3'00\"", second.getPaceFormatted(), "配速 = 360s / 2.0km");
        SegmentLeaderboardDTO third = board.get(2);
        assertEquals(3, third.getRank());
        assertEquals("-", third.getPaceFormatted(), "记录里程缺失 → 配速占位符");
    }

    @Test
    void getSegmentLeaderboard_emptyBoard_returnsEmptyList() {
        when(zSetOps.rangeWithScores(KEY, 0, 49L)).thenReturn(Set.of());
        assertTrue(service.getSegmentLeaderboard(1L, 50).isEmpty());
    }

    @Test
    void getSegmentLeaderboard_sizeNormalizedToDefaultAndCap() {
        when(zSetOps.rangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        service.getSegmentLeaderboard(1L, 0);
        verify(zSetOps).rangeWithScores(KEY, 0, 49L);
        service.getSegmentLeaderboard(1L, 2000);
        verify(zSetOps).rangeWithScores(KEY, 0, 999L);
    }

    @Test
    void getSegmentLeaderboard_missingRecordHash_degradesWithoutSnapshotLookup() {
        when(zSetOps.rangeWithScores(KEY, 0, 49L)).thenReturn(ordered(tuple("9", 420)));
        when(hashOps.entries(RECORD_KEY)).thenReturn(Map.of());
        when(userApi.listUsersByIds(List.of(9L))).thenReturn(Result.success(List.of()));

        List<SegmentLeaderboardDTO> board = service.getSegmentLeaderboard(1L, 50);

        assertEquals(1, board.size());
        SegmentLeaderboardDTO entry = board.get(0);
        assertEquals(1, entry.getRank());
        assertEquals(0L, entry.getRecordId(), "附属 Hash 缺档 → recordId 回退 0");
        assertEquals("-", entry.getPaceFormatted(), "无记录 → 不查快照、配速占位符");
        assertEquals("用户9", entry.getNickname(), "user-service 无此用户 → 占位昵称降级");
        verify(sportRecordMapper, never()).selectById(anyLong());
    }

    // ==================== 测试数据工具 ====================

    @SuppressWarnings("unchecked")
    private static Set<TypedTuple<String>> ordered(TypedTuple<String>... tuples) {
        return new LinkedHashSet<>(List.of(tuples));
    }

    private static TypedTuple<String> tuple(String member, double score) {
        return TypedTuple.of(member, score);
    }

    private static SportRecordSnapshot snapshot(String distance) {
        SportRecordSnapshot s = new SportRecordSnapshot();
        s.setId(1L);
        s.setUserId(100L);
        s.setDistance(distance == null ? null : new BigDecimal(distance));
        return s;
    }

    private static UserDTO user(Long id, String nickname) {
        UserDTO u = new UserDTO();
        u.setId(id);
        u.setNickname(nickname);
        return u;
    }
}
