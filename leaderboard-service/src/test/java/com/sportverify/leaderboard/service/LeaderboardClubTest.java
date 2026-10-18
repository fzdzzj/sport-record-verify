package com.sportverify.leaderboard.service;

import com.sportverify.api.user.dto.ClubLeaderboardDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跑团团队里程榜单元测试（TASK-275）：里程上报（总榜/周榜 ZINCRBY + 名称 Hash 同步 /
 * 参数校验）与榜单查询（降序 rank / 公里 2 位小数与格式化 / 名称缓存补齐与占位兜底 /
 * weekly 周一键 / size 钳制 / 空榜兜底 / 非法 period）。
 *
 * <p>用 Mockito 打桩 StringRedisTemplate（ZSet/Hash 两视图），不依赖真实 Redis
 * （与 {@code LeaderboardSegmentTest} 同款风格；构造器 7 参与主测试类同序）。</p>
 */
class LeaderboardClubTest {

    private static final String OVERALL_KEY = LeaderboardService.CLUB_OVERALL_ZSET_KEY;
    private static final String NAMES_KEY = LeaderboardService.CLUB_NAMES_HASH_KEY;

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private HashOperations<String, Object, Object> hashOps;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new LeaderboardService(null, null, null, redis,
                mock(RedissonClient.class), null, mock(CacheManager.class));
    }

    private static TypedTuple<String> tuple(String clubId, double meters) {
        return TypedTuple.of(clubId, meters);
    }

    // ==================== 里程上报（ZINCRBY + 名称缓存） ====================

    /** 上报成功：总榜与周榜同时 ZINCRBY，名称同步进 Hash。 */
    @Test
    void recordClubDistance_incrementsOverallAndWeekly_andSyncsName() {
        LocalDate monday = LocalDate.of(2026, 10, 12);

        service.recordClubDistance(7L, "黎明跑团", 15000.0, monday);

        verify(zSetOps).incrementScore(OVERALL_KEY, "7", 15000.0);
        verify(zSetOps).incrementScore(
                LeaderboardService.CLUB_WEEKLY_ZSET_KEY_PREFIX + monday, "7", 15000.0);
        verify(hashOps).put(NAMES_KEY, "7", "黎明跑团");
    }

    /** clubName 缺失：只累加里程，不覆盖名称缓存。 */
    @Test
    void recordClubDistance_blankName_skipsNameHash() {
        service.recordClubDistance(7L, "  ", 500.0, LocalDate.of(2026, 10, 12));

        verify(zSetOps, never()).incrementScore(eq(NAMES_KEY), anyString(), anyDouble());
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    /** 参数非法：clubId 缺失 / 里程非正 → 400 非法参数，零写操作。 */
    @Test
    void recordClubDistance_invalidArgs_throwAndSkipWrites() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordClubDistance(null, "黎明跑团", 100.0, LocalDate.now()));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordClubDistance(7L, "黎明跑团", 0.0, LocalDate.now()));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordClubDistance(7L, "黎明跑团", -50.0, LocalDate.now()));
        verifyNoInteractions(zSetOps);
        verifyNoInteractions(hashOps);
    }

    // ==================== 榜单查询（降序 + 组装） ====================

    /** 总榜降序：rank 自 1 起，公里保留 2 位小数并产出格式化串，名称自 Hash 补齐。 */
    @Test
    void getClubLeaderboard_overall_descendingWithNames() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                tuple("7", 15345.6), tuple("9", 8000.0)));
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(tuples);
        Map<Object, Object> names = new LinkedHashMap<>();
        names.put("7", "黎明跑团");
        when(hashOps.entries(NAMES_KEY)).thenReturn(names);

        List<ClubLeaderboardDTO> board = service.getClubLeaderboard("overall", null);

        assertEquals(2, board.size());
        assertEquals(1, board.get(0).getRank());
        assertEquals(7L, board.get(0).getClubId());
        assertEquals("黎明跑团", board.get(0).getClubName());
        assertEquals(15.35, board.get(0).getTotalDistanceKm());
        assertEquals("15.35 km", board.get(0).getFormattedDistance());
        assertEquals(2, board.get(1).getRank());
        assertEquals("8.00 km", board.get(1).getFormattedDistance());
    }

    /** 名称缓存缺失 → 「跑团{id}」占位兜底；memberCount 为预留字段（ZSet/Hash 不携带）。 */
    @Test
    void getClubLeaderboard_missingName_fallsBackToPlaceholder() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(tuple("9", 8000.0)));
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(tuples);
        when(hashOps.entries(NAMES_KEY)).thenReturn(Map.of());

        List<ClubLeaderboardDTO> board = service.getClubLeaderboard("overall", null);

        assertEquals("跑团9", board.get(0).getClubName());
        assertEquals(0, board.get(0).getMemberCount());
    }

    /** weekly 读当周周一键（与 record-service 周报周一锚点同口径）。 */
    @Test
    void getClubLeaderboard_weekly_usesCurrentMondayKey() {
        String mondayKey = LeaderboardService.CLUB_WEEKLY_ZSET_KEY_PREFIX
                + LocalDate.now().with(DayOfWeek.MONDAY);
        when(zSetOps.reverseRangeWithScores(mondayKey, 0, 19L)).thenReturn(new LinkedHashSet<>());

        List<ClubLeaderboardDTO> board = service.getClubLeaderboard("weekly", null);

        assertTrue(board.isEmpty());
        verify(zSetOps).reverseRangeWithScores(mondayKey, 0, 19L);
    }

    /** size 钳制：默认 20、超限截到 100。 */
    @Test
    void getClubLeaderboard_sizeClampedToMax100() {
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 99L)).thenReturn(new LinkedHashSet<>());

        service.getClubLeaderboard("overall", 500);

        verify(zSetOps).reverseRangeWithScores(OVERALL_KEY, 0, 99L);
    }

    /** 空榜兜底：ZSet 无成员返回空列表（Redis 返回 null 同样兜底）。 */
    @Test
    void getClubLeaderboard_emptyBoard_returnsEmptyList() {
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(new LinkedHashSet<>());
        assertTrue(service.getClubLeaderboard("overall", null).isEmpty());

        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(null);
        assertTrue(service.getClubLeaderboard("overall", null).isEmpty());
    }

    /** 非法 period → 400 非法参数。 */
    @Test
    void getClubLeaderboard_unknownPeriod_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getClubLeaderboard("hourly", null));
    }
}
