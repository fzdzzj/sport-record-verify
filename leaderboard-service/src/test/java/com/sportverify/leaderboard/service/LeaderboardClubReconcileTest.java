package com.sportverify.leaderboard.service;

import com.sportverify.api.user.UserApi;
import com.sportverify.leaderboard.entity.ClubDistanceContribution;
import com.sportverify.leaderboard.mapper.ClubDistanceContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper.UserMileage;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跑团三组 ZSet 定时对账单元测试（TASK-278 R3）。
 *
 * <p>覆盖：以锚点 {@code club_distance_contribution} 的 CREDITED 汇总为权威源，
 * 覆盖纠偏 {@code club:overall}（按 club_id）/ {@code club:weekly:{weekStart}}（按
 * (club_id, week_start)）/ {@code club:member:{clubId}}（按 (club_id, user_id)）三组键；
 * 残留清理（无汇总之团 ZREM + 成员键整键 DEL、当周键残留清理、历史周键不主动遍历）；
 * 汇总异常 best-effort 不拖垮个人榜结算；以及只读 Mapper 的孤儿过滤 SQL 契约
 * （{@code INNER JOIN sport_record} 存活过滤 + 零写方法）。</p>
 *
 * <p>纯 JVM：Mapper / Redis 模板 / Redisson / 缓存全部 Mockito 打桩，经真实
 * {@code settleAndReconcile} 入口驱动（同锁同周期），不依赖真实 Redis / MySQL。</p>
 */
class LeaderboardClubReconcileTest {

    private static final int CREDITED = ClubDistanceContribution.STATUS_CREDITED;
    private static final String OVERALL_KEY = LeaderboardService.CLUB_OVERALL_ZSET_KEY;
    private static final String WEEKLY_PREFIX = LeaderboardService.CLUB_WEEKLY_ZSET_KEY_PREFIX;
    private static final String MEMBER_PREFIX = LeaderboardService.CLUB_MEMBER_ZSET_KEY_PREFIX;

    private SportRecordMapper sportRecordMapper;
    private LeaderboardContributionMapper contributionMapper;
    private LeaderboardDailySummaryMapper dailySummaryMapper;
    private ClubDistanceContributionMapper clubAnchorMapper;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private CacheManager cacheManager;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        contributionMapper = mock(LeaderboardContributionMapper.class);
        dailySummaryMapper = mock(LeaderboardDailySummaryMapper.class);
        clubAnchorMapper = mock(ClubDistanceContributionMapper.class);
        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        cacheManager = mock(CacheManager.class);
        RLock lock = mock(RLock.class);
        RedissonClient redissonClient = mock(RedissonClient.class);

        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(cacheManager.getCache(LeaderboardService.OVERALL_CACHE_NAME)).thenReturn(mock(Cache.class));
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 个人总榜截断返回 long：未打桩时 Mockito 返回 null 会在拆箱处 NPE，故显式给 0
        when(zSetOps.removeRange(anyString(), anyLong(), anyLong())).thenReturn(0L);

        service = new LeaderboardService(sportRecordMapper, contributionMapper, dailySummaryMapper,
                redis, redissonClient, mock(UserApi.class), cacheManager, clubAnchorMapper);
    }

    private static ClubDistanceContribution row(Long clubId, LocalDate weekStart, Long userId, String meters) {
        ClubDistanceContribution row = new ClubDistanceContribution();
        row.setClubId(clubId);
        row.setWeekStart(weekStart);
        row.setUserId(userId);
        row.setTotalDistance(new BigDecimal(meters));
        return row;
    }

    private static LocalDate currentWeekStart() {
        return LocalDate.now().with(DayOfWeek.MONDAY);
    }

    /** 以 (key → (member → score)) 形态解析捕获到的 ZADD 参数，便于逐位断言 */
    private Map<String, Map<String, Double>> captureZadds() {
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<TypedTuple<String>>> tupleCap = ArgumentCaptor.forClass(Set.class);
        verify(zSetOps, org.mockito.Mockito.atLeastOnce()).add(keyCap.capture(), tupleCap.capture());
        Map<String, Map<String, Double>> added = new HashMap<>();
        List<String> keys = keyCap.getAllValues();
        List<Set<TypedTuple<String>>> tuples = tupleCap.getAllValues();
        for (int i = 0; i < keys.size(); i++) {
            Map<String, Double> members = added.computeIfAbsent(keys.get(i), k -> new HashMap<>());
            for (TypedTuple<String> tuple : tuples.get(i)) {
                members.put(tuple.getValue(), tuple.getScore());
            }
        }
        return added;
    }

    // ==================== 覆盖纠偏（三组键） ====================

    /** 三组键均以锚点汇总裁剪：overall 按团、weekly 按 (团, 周)、member 按 (团, 成员)。 */
    @Test
    void settle_reconcilesClubOverallWeeklyAndMemberFromAnchorSummaries() {
        LocalDate monday = currentWeekStart();
        when(clubAnchorMapper.selectCreditedClubSummaries(CREDITED))
                .thenReturn(List.of(row(7L, null, null, "15000"), row(9L, null, null, "3000")));
        when(clubAnchorMapper.selectCreditedWeeklySummaries(CREDITED))
                .thenReturn(List.of(row(7L, monday, null, "15000"), row(9L, monday, null, "3000")));
        when(clubAnchorMapper.selectCreditedMemberSummaries(CREDITED))
                .thenReturn(List.of(row(7L, null, 100L, "12000"), row(7L, null, 101L, "3000"),
                        row(9L, null, 200L, "3000")));

        service.settleAndReconcile();

        Map<String, Map<String, Double>> added = captureZadds();
        assertEquals(15000.0, added.get(OVERALL_KEY).get("7"), "跑团总榜按 club_id 汇总");
        assertEquals(3000.0, added.get(OVERALL_KEY).get("9"));
        assertEquals(15000.0, added.get(WEEKLY_PREFIX + monday).get("7"), "周榜按 (club_id, week_start) 汇总");
        assertEquals(3000.0, added.get(WEEKLY_PREFIX + monday).get("9"));
        assertEquals(12000.0, added.get(MEMBER_PREFIX + 7L).get("100"), "成员贡献按 (club_id, user_id) 汇总");
        assertEquals(3000.0, added.get(MEMBER_PREFIX + 7L).get("101"));
        assertEquals(3000.0, added.get(MEMBER_PREFIX + 9L).get("200"));
        // 全部成员均在权威汇总内 → 无残留可清
        verify(zSetOps, never()).remove(anyString(), any(Object[].class));
        verify(redis, never()).delete(anyString());
    }

    /** 残留清理：无 CREDITED 汇总之团从 overall/weekly ZREM，成员贡献键整键 DEL（解散即清榜）。 */
    @Test
    void settle_prunesStaleClubAndDeletesItsMemberKey() {
        LocalDate monday = currentWeekStart();
        when(clubAnchorMapper.selectCreditedClubSummaries(CREDITED))
                .thenReturn(List.of(row(7L, null, null, "15000")));
        when(clubAnchorMapper.selectCreditedWeeklySummaries(CREDITED))
                .thenReturn(List.of(row(7L, monday, null, "15000")));
        when(clubAnchorMapper.selectCreditedMemberSummaries(CREDITED))
                .thenReturn(List.of(row(7L, null, 100L, "15000")));
        when(zSetOps.reverseRange(OVERALL_KEY, 0, -1)).thenReturn(Set.of("7", "9"));
        when(zSetOps.reverseRange(WEEKLY_PREFIX + monday, 0, -1)).thenReturn(Set.of("7", "9"));
        when(zSetOps.reverseRange(MEMBER_PREFIX + 7L, 0, -1)).thenReturn(Set.of("100"));

        service.settleAndReconcile();

        verify(zSetOps).remove(OVERALL_KEY, "9");
        verify(zSetOps).remove(WEEKLY_PREFIX + monday, "9");
        verify(redis).delete(MEMBER_PREFIX + 9L);
        verify(zSetOps).add(eq(MEMBER_PREFIX + 7L), anySet());
    }

    /** 无任何 CREDITED 锚点时：当周键残留仍被清理（裁决⑤「锚点分组可达周键与当周键」覆盖；历史键不遍历）。 */
    @Test
    void settle_noCreditedAnchors_clearsCurrentWeekResidualOnly() {
        LocalDate monday = currentWeekStart();
        when(zSetOps.reverseRange(WEEKLY_PREFIX + monday, 0, -1)).thenReturn(Set.of("7"));

        service.settleAndReconcile();

        verify(zSetOps).remove(WEEKLY_PREFIX + monday, "7");
        verify(zSetOps, never()).add(anyString(), anySet());
    }

    /** 历史周键仅被锚点分组覆盖纠偏，不主动遍历（不可达的旧周键零读写）。 */
    @Test
    void settle_oldWeekAnchor_onlyTouchesReachableWeekKey() {
        LocalDate oldMonday = LocalDate.of(2026, 1, 5);
        when(clubAnchorMapper.selectCreditedClubSummaries(CREDITED))
                .thenReturn(List.of(row(7L, null, null, "4000")));
        when(clubAnchorMapper.selectCreditedWeeklySummaries(CREDITED))
                .thenReturn(List.of(row(7L, oldMonday, null, "4000")));
        when(clubAnchorMapper.selectCreditedMemberSummaries(CREDITED))
                .thenReturn(List.of(row(7L, null, 100L, "4000")));

        service.settleAndReconcile();

        Map<String, Map<String, Double>> added = captureZadds();
        assertEquals(4000.0, added.get(WEEKLY_PREFIX + oldMonday).get("7"), "锚点可达的历史周键被覆盖纠偏");
        assertFalse(added.containsKey(WEEKLY_PREFIX + currentWeekStart()), "当周无锚点则不下发写");
    }

    /** 汇总查询异常：跑团对账 best-effort 跳过，个人榜结算与报表快照照常完成。 */
    @Test
    void settle_clubReconcileFailure_doesNotBreakPersonalSettle() {
        UserMileage summary = new UserMileage();
        summary.setUserId(100L);
        summary.setTotalDistance(new BigDecimal("42.50"));
        when(contributionMapper.selectActiveSummaries(anyInt())).thenReturn(List.of(summary));
        when(clubAnchorMapper.selectCreditedClubSummaries(CREDITED))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.settleAndReconcile());

        Map<String, Map<String, Double>> added = captureZadds();
        assertEquals(42.5, added.get(LeaderboardService.OVERALL_ZSET_KEY).get("100"), "个人总榜照常纠偏");
        verify(dailySummaryMapper).upsertFromActiveContributions(anyInt());
    }

    // ==================== 只读 Mapper SQL 契约 ====================

    /** 三条汇总 SQL 均以 INNER JOIN sport_record 过滤孤儿，且只读 CREDITED；Mapper 无任何写方法。 */
    @Test
    void mapperQueries_filterOrphansByJoin_andExposeNoWriteMethods() throws Exception {
        for (Method method : ClubDistanceContributionMapper.class.getDeclaredMethods()) {
            assertNotNull(method.getAnnotation(Select.class), method.getName() + " 应为 @Select 只读查询");
            assertTrue(method.getAnnotation(Insert.class) == null
                    && method.getAnnotation(Update.class) == null
                    && method.getAnnotation(Delete.class) == null,
                    method.getName() + " 不得提供写注解：leaderboard 对锚点表零写入");
            String sql = String.join(" ", method.getAnnotation(Select.class).value());
            assertTrue(sql.contains("INNER JOIN sport_record"), method.getName()
                    + " 必须以 sport_record 存活连接过滤孤儿锚点，实测 SQL=" + sql);
            assertTrue(sql.contains("c.status = #{status}"), method.getName()
                    + " 只计 CREDITED 锚点，实测 SQL=" + sql);
            assertTrue(sql.contains("SUM(c.distance_meters) AS totalDistance"), method.getName()
                    + " 必须以 SUM(distance_meters) 为权威汇总，实测 SQL=" + sql);
        }
        assertEquals(3, ClubDistanceContributionMapper.class.getDeclaredMethods().length,
                "只读 Mapper 应恰有 3 个汇总查询（club / week / member）");
    }
}
