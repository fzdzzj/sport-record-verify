package com.sportverify.record.service;

import com.sportverify.api.record.dto.WeeklyDigestDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.RecordEventProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 每周运动智能周报单元测试（TASK-274：自然周聚合 / 环比 / 击败比例推算 /
 * 恢复建议分档 / 越权拦截 / 周一归一化 / 昵称软降级）。
 *
 * <p>纯 Mockito：Mapper 全 mock，不依赖 MySQL。聚合算法经包可见
 * {@code buildWeeklyDigest} 直测（零 Mapper 参与）；服务入口用例校验
 * 权限门、周期窗口（周一归一化）与两次窗口查询的装配协同。</p>
 */
class WeeklyDigestServiceTest {

    private SportRecordMapper sportRecordMapper;
    private UserApi userApi;
    private SportRecordService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        userApi = mock(UserApi.class);
        service = new SportRecordService(sportRecordMapper,
                mock(TrackPointMapper.class),
                mock(TrackPointArchiveMapper.class),
                mock(RecordEventProducer.class),
                mock(VerifyApi.class),
                mock(VerifyDegradeService.class),
                userApi);
    }

    /** 2026-10-12（周一）为固定锚周，Clock 无关。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private static SportRecord row(String startTime, BigDecimal distanceKm,
                                   Integer durationSec, Integer sportType) {
        SportRecord r = new SportRecord();
        r.setUserId(200L);
        r.setDistance(distanceKm);
        r.setDuration(durationSec);
        r.setSportType(sportType);
        r.setStatus(RecordStatus.PASSED.getCode());
        if (startTime != null) {
            r.setStartTime(LocalDateTime.parse(startTime));
        }
        r.setCreatedAt(startTime == null
                ? LocalDateTime.of(2026, 10, 13, 8, 0) : null);
        return r;
    }

    // ==================== 权限门（getWeeklyDigest） ====================

    /** userId 缺失 → 400 非法参数（控制器降级态同款文案）。 */
    @Test
    void getWeeklyDigest_userNull_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.getWeeklyDigest(null, null, null));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    /** auth 开、caller 非本人 → 1002 FORBIDDEN（越权拦截）。 */
    @Test
    void getWeeklyDigest_notOwner_throws1002() {
        BizException e = assertThrows(BizException.class,
                () -> service.getWeeklyDigest(100L, 200L, null));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    /** auth 关闭降级态（caller=null）→ 放行。 */
    @Test
    void getWeeklyDigest_callerNull_passes() {
        when(sportRecordMapper.selectList(any())).thenReturn(List.of());
        WeeklyDigestDTO out = service.getWeeklyDigest(null, 200L, MONDAY);
        assertEquals(200L, out.getUserId());
        assertEquals("2026-10-12", out.getWeekStartDate());
        assertEquals("2026-10-18", out.getWeekEndDate());
    }

    /** auth 开、caller 为本人 → 放行并聚合（昵称经 user-service 补齐）。 */
    @Test
    void getWeeklyDigest_owner_passesAndAggregates() {
        UserDTO u = new UserDTO();
        u.setId(200L);
        u.setNickname("跑者甲");
        when(userApi.listUsersByIds(any())).thenReturn(Result.success(List.of(u)));
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(
                        row("2026-10-13T07:30:00", BigDecimal.valueOf(10.00), 2754,
                                SportType.RUNNING.getCode()),
                        row("2026-10-15T18:00:00", BigDecimal.valueOf(5.00), 1500,
                                SportType.RUNNING.getCode())),
                List.of());

        WeeklyDigestDTO out = service.getWeeklyDigest(200L, 200L, MONDAY);

        assertEquals(200L, out.getUserId());
        assertEquals("跑者甲", out.getNickname());
        assertEquals(15000.0, out.getTotalDistanceMeters());
        assertEquals(4254, out.getTotalElapsedSeconds());
        assertEquals(2, out.getWorkoutCount());
        assertEquals(2, out.getActiveDays());
        assertEquals("RUNNING", out.getPrimarySportType());
        assertEquals("4'35\"", out.getFastestPaceFormatted());
    }

    /** 显式 weekStart 为周三 → 归一化到所在周周一（窗口仍为周一至周日）。 */
    @Test
    void getWeeklyDigest_weekStartNormalizedToMonday() {
        when(sportRecordMapper.selectList(any())).thenReturn(List.of());
        WeeklyDigestDTO out = service.getWeeklyDigest(200L, 200L,
                LocalDate.of(2026, 10, 14));
        assertEquals(LocalDate.of(2026, 10, 12), LocalDate.parse(out.getWeekStartDate()));
        assertEquals(DayOfWeek.MONDAY, LocalDate.parse(out.getWeekStartDate()).getDayOfWeek());
        assertEquals(LocalDate.of(2026, 10, 18), LocalDate.parse(out.getWeekEndDate()));
    }

    /** 缺省 weekStart（上一完整自然周）：周窗口恒为周一至周日 7 天（Clock 无关形态断言）。 */
    @Test
    void getWeeklyDigest_defaultWeek_isMondayToSunday() {
        when(sportRecordMapper.selectList(any())).thenReturn(List.of());
        WeeklyDigestDTO out = service.getWeeklyDigest(200L, 200L, null);
        LocalDate start = LocalDate.parse(out.getWeekStartDate());
        LocalDate end = LocalDate.parse(out.getWeekEndDate());
        assertEquals(DayOfWeek.MONDAY, start.getDayOfWeek());
        assertEquals(DayOfWeek.SUNDAY, end.getDayOfWeek());
        assertEquals(6, (int) start.until(end).getDays());
    }

    // ==================== 聚合与环比（buildWeeklyDigest 直测） ====================

    /** 0 跑量兜底：空周 → 全零指标、主修类型 null、配速 --、击败比例 0、恢复建议引导开跑。 */
    @Test
    void buildWeeklyDigest_emptyWeek_zeroFallback() {
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, List.of(), List.of());

        assertEquals(0.0, out.getTotalDistanceMeters());
        assertEquals(0, out.getTotalElapsedSeconds());
        assertEquals(0.0, out.getTotalCalories());
        assertEquals(0, out.getWorkoutCount());
        assertEquals(0, out.getActiveDays());
        assertNull(out.getPrimarySportType());
        assertEquals("--", out.getFastestPaceFormatted());
        assertEquals(0.0, out.getDistanceGrowthRate());
        assertEquals(0.0, out.getBeatRatio());
        assertTrue(out.getRecoveryAdvice().contains("本周还没有通过校验的运动记录"));
    }

    /** 环比 +15.2%：本周 11520m / 前周 10000m → distanceGrowthRate = 0.152。 */
    @Test
    void buildWeeklyDigest_growthRate_positive() {
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(6.52), 2000, 1),
                row("2026-10-15T18:00:00", BigDecimal.valueOf(5.00), 1500, 1));
        List<SportRecord> prev = List.of(
                row("2026-10-06T07:30:00", BigDecimal.valueOf(10.00), 2500, 1));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, prev);
        assertEquals(0.152, out.getDistanceGrowthRate());
    }

    /** 环比负增长：本周 8000m / 前周 10000m → −0.2。 */
    @Test
    void buildWeeklyDigest_growthRate_negative() {
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(8.00), 2400, 1));
        List<SportRecord> prev = List.of(
                row("2026-10-06T07:30:00", BigDecimal.valueOf(10.00), 2500, 1));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, prev);
        assertEquals(-0.2, out.getDistanceGrowthRate());
    }

    /** 前一周无数据、本周有数据 → 1.0（+100%，spec 口径）；distance null 按 0 计。 */
    @Test
    void buildWeeklyDigest_prevEmpty_growthIsOne() {
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", null, 1800, 1),
                row("2026-10-16T18:00:00", BigDecimal.valueOf(3.00), 1200, 1));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, List.of());
        assertEquals(1.0, out.getDistanceGrowthRate());
        assertEquals(3000.0, out.getTotalDistanceMeters());
        assertEquals(3000, out.getTotalElapsedSeconds());
    }

    /** 前一周有数据、本封周归零 → −1.0（全撤）。 */
    @Test
    void buildWeeklyDigest_currentEmpty_prevHasData_negativeOne() {
        List<SportRecord> prev = List.of(
                row("2026-10-06T07:30:00", BigDecimal.valueOf(10.00), 2500, 1));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, List.of(), prev);
        assertEquals(-1.0, out.getDistanceGrowthRate());
        assertTrue(out.getRecoveryAdvice().contains("本周还没有通过校验的运动记录"));
    }

    /** 出勤天数按锚点时间归日去重：同日多条算一天（startTime 缺失回退 createdAt）。 */
    @Test
    void buildWeeklyDigest_activeDays_dedupSameDay() {
        SportRecord third = row(null, BigDecimal.valueOf(4.00), 1500, 1);
        third.setCreatedAt(LocalDateTime.of(2026, 10, 14, 9, 0));
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(3.00), 1200, 1),
                row("2026-10-13T19:00:00", BigDecimal.valueOf(2.00), 900, 1),
                third);
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, List.of());
        assertEquals(2, out.getActiveDays());
        assertEquals(3, out.getWorkoutCount());
    }

    /** 主修类型：完成次数最多者胜出；types 并列取先出现者。 */
    @Test
    void buildWeeklyDigest_primarySportType_dominantWins() {
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(3.00), 1200, 1),
                row("2026-10-14T07:30:00", BigDecimal.valueOf(3.00), 1200, 1),
                row("2026-10-15T07:30:00", BigDecimal.valueOf(8.00), 1600, 2),
                row("2026-10-16T07:30:00", BigDecimal.valueOf(4.00), 4000, 1));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, List.of());
        assertEquals("RUNNING", out.getPrimarySportType());
    }

    /** 最佳满公里配速：按记录时长/公里取最快（10km/2754s=4'35" 优于 5km/1500s=5'00"）。 */
    @Test
    void buildWeeklyDigest_fastestPace_bestRecordWins() {
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(5.00), 1500, 1),
                row("2026-10-15T18:00:00", BigDecimal.valueOf(10.00), 2754, 1));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, List.of());
        assertEquals("4'35\"", out.getFastestPaceFormatted());
    }

    /** 不足 1 公里的记录不参与最快配速评选；热量按运动类型系数估算（骑行 25 kcal/km）。 */
    @Test
    void buildWeeklyDigest_paceThreshold_andCalories() {
        List<SportRecord> week = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(0.50), 240, 1),
                row("2026-10-15T18:00:00", BigDecimal.valueOf(0.80), 200, 2),
                row("2026-10-16T18:00:00", BigDecimal.valueOf(0.60), 300, 2));
        WeeklyDigestDTO out = service.buildWeeklyDigest(200L, MONDAY, week, List.of());
        assertEquals("--", out.getFastestPaceFormatted());
        // 0.5×65 + 0.8×25 + 0.6×25 = 67.5 kcal
        assertEquals(67.5, out.getTotalCalories());
        assertEquals("CYCLING", out.getPrimarySportType());
    }

    /** 击败比例推算：零跑量 0.0、20km≈0.63、曲线饱和封顶 0.99。 */
    @Test
    void buildWeeklyDigest_beatRatio_heuristicCurve() {
        WeeklyDigestDTO zero = service.buildWeeklyDigest(200L, MONDAY, List.of(), List.of());
        assertEquals(0.0, zero.getBeatRatio());

        List<SportRecord> week20 = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(20.00), 6000, 1));
        WeeklyDigestDTO km20 =
                service.buildWeeklyDigest(200L, MONDAY, week20, List.of());
        assertEquals(0.63, km20.getBeatRatio());

        List<SportRecord> week200 = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(200.00), 40000, 1));
        WeeklyDigestDTO km200 =
                service.buildWeeklyDigest(200L, MONDAY, week200, List.of());
        assertEquals(0.99, km200.getBeatRatio());
    }

    /** 恢复建议分档：高出勤（≥5 天）→ 休息恢复文案；低出勤低跑量 → 循序渐进文案。 */
    @Test
    void buildWeeklyDigest_recoveryAdvice_tiering() {
        List<SportRecord> high = List.of(
                row("2026-10-12T07:30:00", BigDecimal.valueOf(5.00), 1500, 1),
                row("2026-10-13T07:30:00", BigDecimal.valueOf(5.00), 1500, 1),
                row("2026-10-14T07:30:00", BigDecimal.valueOf(5.00), 1500, 1),
                row("2026-10-15T07:30:00", BigDecimal.valueOf(5.00), 1500, 1),
                row("2026-10-16T07:30:00", BigDecimal.valueOf(5.00), 1500, 1));
        WeeklyDigestDTO highOut =
                service.buildWeeklyDigest(200L, MONDAY, high, List.of());
        assertTrue(highOut.getRecoveryAdvice().contains("训练量较大"));

        List<SportRecord> low = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(3.00), 1200, 1));
        WeeklyDigestDTO lowOut =
                service.buildWeeklyDigest(200L, MONDAY, low, List.of());
        assertTrue(lowOut.getRecoveryAdvice().contains("出勤较少"));

        List<SportRecord> mid = List.of(
                row("2026-10-13T07:30:00", BigDecimal.valueOf(12.00), 3600, 1),
                row("2026-10-14T07:30:00", BigDecimal.valueOf(12.00), 3600, 1),
                row("2026-10-15T07:30:00", BigDecimal.valueOf(12.00), 3600, 1));
        WeeklyDigestDTO midOut =
                service.buildWeeklyDigest(200L, MONDAY, mid, List.of());
        assertTrue(midOut.getRecoveryAdvice().contains("节奏不错"));
    }

    // ==================== 昵称软降级 ====================

    /** user-service 异常 → 昵称 null（fail-safe，不阻断周报主链路）。 */
    @Test
    void getWeeklyDigest_userApiThrows_nicknameNull() {
        when(sportRecordMapper.selectList(any())).thenReturn(List.of());
        when(userApi.listUsersByIds(any())).thenThrow(new RuntimeException("feign down"));
        WeeklyDigestDTO out = service.getWeeklyDigest(200L, 200L, MONDAY);
        assertNull(out.getNickname());
        assertEquals(200L, out.getUserId());
    }
}
