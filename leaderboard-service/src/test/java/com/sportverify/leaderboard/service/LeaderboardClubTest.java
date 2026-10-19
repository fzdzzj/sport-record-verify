package com.sportverify.leaderboard.service;

import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.ClubLeaderboardDTO;
import com.sportverify.api.user.dto.ClubMemberContributionDTO;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sportverify.leaderboard.mapper.ClubDistanceContributionMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跑团团队里程榜单元测试（TASK-275）：里程上报（总榜/周榜 ZINCRBY + 名称 Hash 同步 /
 * 参数校验）与榜单查询（降序 rank / 公里 2 位小数与格式化 / 名称缓存补齐与占位兜底 /
 * weekly 周一键 / size 钳制 / 空榜兜底 / 非法 period）。
 *
 * <p>TASK-276 扩展：R3 成员贡献 ZSet 写入与贡献明细查询（降序 rank / 昵称缺失回退
 * 「跑者{userId}」/ size 钳制默认 20 上限 100 / 空榜与空参）、R2 带符号净额（负数为回退）、
 * R4 团队榜真实 memberCount 批查与失败降级 0。</p>
 *
 * <p>用 Mockito 打桩 StringRedisTemplate（ZSet/Hash 两视图）与 UserApi，不依赖真实 Redis
 * （与 {@code LeaderboardSegmentTest} 同款风格；构造器 7 参与主测试类同序）。</p>
 */
class LeaderboardClubTest {

    private static final String OVERALL_KEY = LeaderboardService.CLUB_OVERALL_ZSET_KEY;
    private static final String NAMES_KEY = LeaderboardService.CLUB_NAMES_HASH_KEY;
    private static final String MEMBER_KEY_PREFIX = LeaderboardService.CLUB_MEMBER_ZSET_KEY_PREFIX;

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private HashOperations<String, Object, Object> hashOps;
    private UserApi userApi;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        hashOps = mock(HashOperations.class);
        userApi = mock(UserApi.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new LeaderboardService(null, null, null, redis,
                mock(RedissonClient.class), userApi, mock(CacheManager.class),
                mock(ClubDistanceContributionMapper.class));
    }

    private static TypedTuple<String> tuple(String clubId, double meters) {
        return TypedTuple.of(clubId, meters);
    }

    private static UserDTO userDto(Long id, String nickname) {
        UserDTO dto = new UserDTO();
        dto.setId(id);
        dto.setNickname(nickname);
        return dto;
    }

    // ==================== 里程上报（ZINCRBY + 名称缓存） ====================

    /** 上报成功：总榜与周榜同时 ZINCRBY，名称同步进 Hash，成员贡献 ZSet 同步累加（TASK-276 R3）。 */
    @Test
    void recordClubDistance_incrementsOverallAndWeekly_andSyncsName() {
        LocalDate monday = LocalDate.of(2026, 10, 12);

        service.recordClubDistance(7L, "黎明跑团", 15000.0, monday, 100L);

        verify(zSetOps).incrementScore(OVERALL_KEY, "7", 15000.0);
        verify(zSetOps).incrementScore(
                LeaderboardService.CLUB_WEEKLY_ZSET_KEY_PREFIX + monday, "7", 15000.0);
        verify(hashOps).put(NAMES_KEY, "7", "黎明跑团");
        verify(zSetOps).incrementScore(MEMBER_KEY_PREFIX + 7L, "100", 15000.0);
    }

    /** 回退净额（TASK-276 R2）：负增量同号累加总榜/周榜/成员贡献三者，团队榜与贡献榜恒等对账。 */
    @Test
    void recordClubDistance_negativeDelta_reversesAllThreeZsets() {
        LocalDate monday = LocalDate.of(2026, 10, 12);

        service.recordClubDistance(7L, "黎明跑团", -4200.0, monday, 100L);

        verify(zSetOps).incrementScore(OVERALL_KEY, "7", -4200.0);
        verify(zSetOps).incrementScore(
                LeaderboardService.CLUB_WEEKLY_ZSET_KEY_PREFIX + monday, "7", -4200.0);
        verify(zSetOps).incrementScore(MEMBER_KEY_PREFIX + 7L, "100", -4200.0);
    }

    /** userId 缺失（历史调用/内部重放）：只累加团队榜，不写成员贡献 ZSet。 */
    @Test
    void recordClubDistance_nullUserId_skipsMemberZset() {
        service.recordClubDistance(7L, "黎明跑团", 1000.0, LocalDate.of(2026, 10, 12), null);

        verify(zSetOps).incrementScore(OVERALL_KEY, "7", 1000.0);
        verify(zSetOps, never()).incrementScore(eq(MEMBER_KEY_PREFIX + 7L), anyString(), anyDouble());
    }

    /** clubName 缺失：只累加里程，不覆盖名称缓存。 */
    @Test
    void recordClubDistance_blankName_skipsNameHash() {
        service.recordClubDistance(7L, "  ", 500.0, LocalDate.of(2026, 10, 12), null);

        verify(zSetOps, never()).incrementScore(eq(NAMES_KEY), anyString(), anyDouble());
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    /** 参数非法：clubId 缺失 / 里程为 0（无变化不应上报）→ 400 非法参数，零写操作。 */
    @Test
    void recordClubDistance_invalidArgs_throwAndSkipWrites() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordClubDistance(null, "黎明跑团", 100.0, LocalDate.now(), 100L));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordClubDistance(7L, "黎明跑团", 0.0, LocalDate.now(), 100L));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordClubDistance(7L, "黎明跑团", null, LocalDate.now(), 100L));
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

    // ==================== 成员数真实计数（TASK-276 R4） ====================

    /** 批查命中：榜单 TOP N 的 memberCount 取 user-service 批查真实值（一次调用拉齐）。 */
    @Test
    void getClubLeaderboard_fillsRealMemberCountsFromUserApi() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                tuple("7", 15345.6), tuple("9", 8000.0)));
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(tuples);
        when(hashOps.entries(NAMES_KEY)).thenReturn(Map.of());
        when(userApi.listClubMemberCounts(any())).thenReturn(
                Result.success(Map.of(7L, 12, 9L, 5)));

        List<ClubLeaderboardDTO> board = service.getClubLeaderboard("overall", null);

        assertEquals(12, board.get(0).getMemberCount());
        assertEquals(5, board.get(1).getMemberCount());
    }

    /** 批查失败降级：user-service 抛异常 → memberCount 降级 0，榜单主体（名次/里程）照常返回。 */
    @Test
    void getClubLeaderboard_memberCountQueryFails_degradesToZero() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(tuple("7", 15345.6)));
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(tuples);
        when(hashOps.entries(NAMES_KEY)).thenReturn(Map.of());
        when(userApi.listClubMemberCounts(any())).thenThrow(new RuntimeException("user-service down"));

        List<ClubLeaderboardDTO> board = service.getClubLeaderboard("overall", null);

        assertEquals(1, board.size());
        assertEquals(0, board.get(0).getMemberCount());
        assertEquals(15.35, board.get(0).getTotalDistanceKm());
    }

    /** 批查返回空表（降级/缺项）→ 全部降级 0。 */
    @Test
    void getClubLeaderboard_memberCountQueryEmpty_degradesToZero() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(tuple("7", 15345.6)));
        when(zSetOps.reverseRangeWithScores(OVERALL_KEY, 0, 19L)).thenReturn(tuples);
        when(hashOps.entries(NAMES_KEY)).thenReturn(Map.of());
        when(userApi.listClubMemberCounts(any())).thenReturn(Result.success(Map.of()));

        assertEquals(0, service.getClubLeaderboard("overall", null).get(0).getMemberCount());
    }

    // ==================== 成员贡献明细（TASK-276 R3） ====================

    /** 贡献榜降序：rank 自 1 起，昵称批量补齐，缺失回退「跑者{userId}」。 */
    @Test
    void getClubContributions_descendingWithNicknamesAndFallback() {
        String key = MEMBER_KEY_PREFIX + 7L;
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                tuple("100", 15345.6), tuple("101", 8000.0)));
        when(zSetOps.reverseRangeWithScores(key, 0, 19L)).thenReturn(tuples);
        when(userApi.listUsersByIds(any())).thenReturn(
                Result.success(List.of(userDto(100L, "演示用户"))));

        List<ClubMemberContributionDTO> board = service.getClubContributions(7L, null);

        assertEquals(2, board.size());
        assertEquals(1, board.get(0).getRank());
        assertEquals(100L, board.get(0).getUserId());
        assertEquals("演示用户", board.get(0).getNickname());
        assertEquals(15345.6, board.get(0).getDistanceMeters());
        assertEquals(2, board.get(1).getRank());
        assertEquals("跑者101", board.get(1).getNickname(), "昵称缺失回退占位");
    }

    /** 空昵称同样回退占位（用户存在但未设置昵称）。 */
    @Test
    void getClubContributions_blankNickname_fallsBackToPlaceholder() {
        String key = MEMBER_KEY_PREFIX + 7L;
        when(zSetOps.reverseRangeWithScores(key, 0, 19L)).thenReturn(
                new LinkedHashSet<>(List.of(tuple("100", 500.0))));
        when(userApi.listUsersByIds(any())).thenReturn(
                Result.success(List.of(userDto(100L, "  "))));

        assertEquals("跑者100", service.getClubContributions(7L, null).get(0).getNickname());
    }

    /** size 钳制：缺省 20、超限截到 100，与团队榜同口径。 */
    @Test
    void getClubContributions_sizeClamped() {
        String key = MEMBER_KEY_PREFIX + 7L;
        when(zSetOps.reverseRangeWithScores(key, 0, 19L)).thenReturn(new LinkedHashSet<>());
        when(zSetOps.reverseRangeWithScores(key, 0, 99L)).thenReturn(new LinkedHashSet<>());
        when(zSetOps.reverseRangeWithScores(key, 0, 0L)).thenReturn(new LinkedHashSet<>());

        service.getClubContributions(7L, null);
        verify(zSetOps).reverseRangeWithScores(key, 0, 19L);

        service.getClubContributions(7L, 500);
        verify(zSetOps).reverseRangeWithScores(key, 0, 99L);

        service.getClubContributions(7L, 1);
        verify(zSetOps).reverseRangeWithScores(key, 0, 0L);
    }

    /** 空榜兜底：ZSet 无成员（或 Redis 返回 null）→ 空列表，不查昵称。 */
    @Test
    void getClubContributions_emptyBoard_returnsEmptyList() {
        String key = MEMBER_KEY_PREFIX + 7L;
        when(zSetOps.reverseRangeWithScores(key, 0, 19L)).thenReturn(new LinkedHashSet<>());
        assertTrue(service.getClubContributions(7L, null).isEmpty());

        when(zSetOps.reverseRangeWithScores(key, 0, 19L)).thenReturn(null);
        assertTrue(service.getClubContributions(7L, null).isEmpty());
        verifyNoMoreInteractions(userApi);
    }

    /** clubId 缺失 → 400 非法参数。 */
    @Test
    void getClubContributions_nullClubId_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.getClubContributions(null, null));
        verifyNoInteractions(zSetOps);
    }
}
