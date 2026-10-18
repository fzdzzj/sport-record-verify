package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.record.dto.BadgeDTO;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.UserBadge;
import com.sportverify.record.enums.BadgeType;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.UserBadgeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BadgeService 单测（TASK-269）：覆盖 9 大徽章判定、读时结算追溯解锁、
 * 并发解锁幂等（DuplicateKeyException 捕获）、佩戴/卸下约束与外显只读口径。
 */
@ExtendWith(MockitoExtension.class)
class BadgeServiceTest {

    private static final Long USER_ID = 7L;

    private static final LocalDateTime UNLOCKED_AT = LocalDateTime.of(2026, 10, 18, 8, 0, 0);

    @Mock
    private UserBadgeMapper userBadgeMapper;

    @Mock
    private SportRecordMapper sportRecordMapper;

    @Mock
    private SportRecordService sportRecordService;

    @InjectMocks
    private BadgeService badgeService;

    // ==================== 读时结算：9 大徽章判定 ====================

    /** FIRST_STEP：stats.passCount>=1 即解锁（首次有效运动记录） */
    @Test
    void listMyBadges_firstPassedRecord_unlocksFirstStep() {
        when(sportRecordService.myStats(USER_ID)).thenReturn(stats(1L, "3.20"));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        assertEquals(9, wall.size());
        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper).insert(captor.capture());
        assertEquals("FIRST_STEP", captor.getValue().getBadgeCode());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals(0, captor.getValue().getIsEquipped());
        assertNotNull(captor.getValue().getUnlockedAt());
        BadgeDTO firstStep = wall.get(0);
        assertTrue(firstStep.getUnlocked());
        assertEquals(100, firstStep.getProgressPct());
        assertFalse(wall.get(1).getUnlocked());
    }

    /** TEN_KM_RUN / HALF_MARATHON：单次跑步 21.0975 同时点亮两级，FULL_MARATHON 未达 */
    @Test
    void listMyBadges_singleRun21_0975_unlocksTenKmAndHalfNotFull() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.RUNNING, "21.0975", LocalDateTime.of(2026, 10, 18, 10, 0))));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper, times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().map(UserBadge::getBadgeCode)
                .anyMatch("TEN_KM_RUN"::equals));
        assertTrue(captor.getAllValues().stream().map(UserBadge::getBadgeCode)
                .anyMatch("HALF_MARATHON"::equals));
        BadgeDTO half = wall.get(2);
        assertTrue(half.getUnlocked());
        assertEquals(100, half.getProgressPct());
        assertNotNull(half.getUnlockedAt());
        BadgeDTO full = wall.get(3);
        assertFalse(full.getUnlocked());
        assertEquals(50, full.getProgressPct());
    }

    /** FULL_MARATHON：单次跑步 42.195 点亮全三级跑步里程碑 */
    @Test
    void listMyBadges_singleRun42_195_unlocksFullMarathon() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.RUNNING, "42.195", LocalDateTime.of(2026, 10, 18, 10, 0))));

        badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper, times(3)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().map(UserBadge::getBadgeCode)
                .anyMatch("FULL_MARATHON"::equals));
    }

    /** STREAK_7_DAYS / STREAK_30_DAYS：myStreak 历史最长连续天数维度（追溯点亮口径） */
    @Test
    void listMyBadges_longestStreak30_unlocksBothStreakBadges() {
        MyStreakDTO streak = new MyStreakDTO();
        streak.setCurrent(2);
        streak.setLongest(30);
        when(sportRecordService.myStreak(USER_ID)).thenReturn(streak);
        when(sportRecordService.myStats(USER_ID)).thenReturn(stats(0L, "0"));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper, times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().map(UserBadge::getBadgeCode)
                .anyMatch("STREAK_7_DAYS"::equals));
        assertTrue(captor.getAllValues().stream().map(UserBadge::getBadgeCode)
                .anyMatch("STREAK_30_DAYS"::equals));
        assertTrue(wall.get(4).getUnlocked());
        assertTrue(wall.get(5).getUnlocked());
        assertFalse(wall.get(0).getUnlocked());
    }

    /** HUNDRED_KM_CLUB：myStats 历史累计总里程维度，进度值超目标时百分比封顶 100 */
    @Test
    void listMyBadges_totalDistance150_unlocksHundredKmClubAndCapsProgress() {
        when(sportRecordService.myStats(USER_ID)).thenReturn(stats(0L, "150.00"));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper).insert(captor.capture());
        assertEquals("HUNDRED_KM_CLUB", captor.getValue().getBadgeCode());
        BadgeDTO hundred = wall.get(6);
        assertTrue(hundred.getUnlocked());
        assertEquals(100, hundred.getProgressPct());
        assertEquals(new BigDecimal("150.00"), hundred.getCurrentValue());
    }

    /** CYCLING_MASTER：单次骑行 30 公里维度（跑步里程碑不受骑行里程影响） */
    @Test
    void listMyBadges_singleCycling30_unlocksCyclingMasterOnly() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.CYCLING, "30.00", LocalDateTime.of(2026, 10, 18, 10, 0))));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper).insert(captor.capture());
        assertEquals("CYCLING_MASTER", captor.getValue().getBadgeCode());
        assertTrue(wall.get(7).getUnlocked());
        assertFalse(wall.get(1).getUnlocked());
    }

    /** NIGHT_RUNNER：22:30 夜间窗口内 5km 以上有效运动解锁 */
    @Test
    void listMyBadges_nightRunAt2230_unlocksNightRunner() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.RUNNING, "5.20", LocalDateTime.of(2026, 10, 17, 22, 30))));

        badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper).insert(captor.capture());
        assertEquals("NIGHT_RUNNER", captor.getValue().getBadgeCode());
    }

    /** NIGHT_RUNNER 窗口下界：21:00 含端点解锁；且距离恰达 5.00 满足「5km 以上」 */
    @Test
    void listMyBadges_nightRunAt2100Exactly5km_unlocksNightRunner() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.RUNNING, "5.00", LocalDateTime.of(2026, 10, 17, 21, 0))));

        badgeService.listMyBadges(USER_ID);

        verify(userBadgeMapper).insert(any(UserBadge.class));
    }

    /** NIGHT_RUNNER 窗口外：05:00（不含）与 20:59（不含）的记录均不满足夜间窗口 */
    @Test
    void listMyBadges_daytimeRunsAt0500And2059_doNotUnlockNightRunner() {
        when(sportRecordMapper.selectList(any())).thenReturn(List.of(
                record(SportType.RUNNING, "5.00", LocalDateTime.of(2026, 10, 17, 5, 0)),
                record(SportType.RUNNING, "5.00", LocalDateTime.of(2026, 10, 17, 20, 59))));

        badgeService.listMyBadges(USER_ID);

        verify(userBadgeMapper, never()).insert(any(UserBadge.class));
    }

    /** 夜间 04:59 仍属窗口（<5 小时），恰达 5.00 解锁 */
    @Test
    void listMyBadges_nightRunAt0459_unlocksNightRunner() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.WALKING, "5.00", LocalDateTime.of(2026, 10, 18, 4, 59))));

        badgeService.listMyBadges(USER_ID);

        verify(userBadgeMapper).insert(any(UserBadge.class));
    }

    /** 达成判定统一 >= 口径：单次跑步恰为 10.00 解锁 TEN_KM_RUN（不含 HALF） */
    @Test
    void listMyBadges_singleRunExactly10_unlocksTenKmRunOnly() {
        when(sportRecordMapper.selectList(any())).thenReturn(
                List.of(record(SportType.RUNNING, "10.00", LocalDateTime.of(2026, 10, 18, 10, 0))));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        ArgumentCaptor<UserBadge> captor = ArgumentCaptor.forClass(UserBadge.class);
        verify(userBadgeMapper).insert(captor.capture());
        assertEquals("TEN_KM_RUN", captor.getValue().getBadgeCode());
        assertTrue(wall.get(1).getUnlocked());
        assertFalse(wall.get(2).getUnlocked());
    }

    // ==================== 解锁幂等与佩戴态 ====================

    /** 已解锁勋章不重复插入（读时结算只补缺） */
    @Test
    void listMyBadges_alreadyUnlocked_noReInsertAndKeepsEquippedState() {
        UserBadge persisted = new UserBadge();
        persisted.setUserId(USER_ID);
        persisted.setBadgeCode("FIRST_STEP");
        persisted.setIsEquipped(1);
        persisted.setUnlockedAt(UNLOCKED_AT);
        when(userBadgeMapper.selectList(any())).thenReturn(List.of(persisted));
        when(sportRecordService.myStats(USER_ID)).thenReturn(stats(1L, "3.20"));

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        verify(userBadgeMapper, never()).insert(any(UserBadge.class));
        BadgeDTO firstStep = wall.get(0);
        assertTrue(firstStep.getUnlocked());
        assertTrue(firstStep.getIsEquipped());
        assertEquals(UNLOCKED_AT, firstStep.getUnlockedAt());
    }

    /** 并发解锁命中唯一键：捕获 DuplicateKeyException 后以库内行为准，幂等不外抛 */
    @Test
    void listMyBadges_duplicateKeyOnSettle_isIdempotent() {
        UserBadge winner = new UserBadge();
        winner.setUserId(USER_ID);
        winner.setBadgeCode("FIRST_STEP");
        winner.setIsEquipped(0);
        winner.setUnlockedAt(UNLOCKED_AT);
        when(sportRecordService.myStats(USER_ID)).thenReturn(stats(1L, "3.20"));
        when(userBadgeMapper.insert(any(UserBadge.class))).thenThrow(new DuplicateKeyException("uk_user_badge"));
        when(userBadgeMapper.selectOne(any())).thenReturn(winner);

        List<BadgeDTO> wall = badgeService.listMyBadges(USER_ID);

        BadgeDTO firstStep = wall.get(0);
        assertTrue(firstStep.getUnlocked());
        assertEquals(UNLOCKED_AT, firstStep.getUnlockedAt());
    }

    // ==================== 佩戴与卸下 ====================

    /** 未解锁禁止佩戴：抛业务校验异常，消息携带 BADGE_NOT_UNLOCKED 标识，且无任何 UPDATE */
    @Test
    void wearBadge_notUnlocked_throwsWithBadgeNotUnlockedMarker() {
        when(userBadgeMapper.selectOne(any())).thenReturn(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> badgeService.wearBadge(USER_ID, "FULL_MARATHON"));
        assertTrue(e.getMessage().contains("BADGE_NOT_UNLOCKED"));
        verify(userBadgeMapper, never()).update(isNull(), any());
    }

    /** 勋章编码不在域内：拒绝并拒绝任何 UPDATE */
    @Test
    void wearBadge_unknownCode_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> badgeService.wearBadge(USER_ID, "NOT_A_BADGE"));
        assertTrue(e.getMessage().contains("勋章编码非法"));
        verify(userBadgeMapper, never()).update(isNull(), any());
    }

    /** 佩戴成功：单事务先全量卸下（不带 badge_code 条件）再置目标（带 badge_code 条件） */
    @Test
    @SuppressWarnings("unchecked")
    void wearBadge_success_resetsAllThenEquipsTarget() {
        UserBadge unlocked = new UserBadge();
        unlocked.setUserId(USER_ID);
        unlocked.setBadgeCode("TEN_KM_RUN");
        unlocked.setIsEquipped(0);
        when(userBadgeMapper.selectOne(any())).thenReturn(unlocked);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBadge.class);

        badgeService.wearBadge(USER_ID, "TEN_KM_RUN");

        ArgumentCaptor<LambdaUpdateWrapper<UserBadge>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userBadgeMapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<UserBadge> resetAll = captor.getAllValues().get(0);
        LambdaUpdateWrapper<UserBadge> equipTarget = captor.getAllValues().get(1);
        assertTrue(resetAll.getSqlSet().contains("is_equipped"));
        assertFalse(resetAll.getSqlSegment().contains("badge_code"));
        assertTrue(equipTarget.getSqlSet().contains("is_equipped"));
        assertTrue(equipTarget.getSqlSegment().contains("badge_code"));
    }

    /** 卸下勋章：单条全量置 0 UPDATE，幂等（重复卸下重复生效） */
    @Test
    @SuppressWarnings("unchecked")
    void unwearBadge_resetsAllEquippedFlags() {
        badgeService.unwearBadge(USER_ID);
        badgeService.unwearBadge(USER_ID);

        ArgumentCaptor<LambdaUpdateWrapper<UserBadge>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userBadgeMapper, times(2)).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("is_equipped"));
        assertFalse(captor.getValue().getSqlSegment().contains("badge_code"));
    }

    // ==================== 外显只读查询 ====================

    /** 未佩戴返回 null（纯只读，不触发度量与结算） */
    @Test
    void getEquippedBadge_none_returnsNull() {
        when(userBadgeMapper.selectOne(any())).thenReturn(null);

        assertNull(badgeService.getEquippedBadge(USER_ID));
        verifyNoInteractions(sportRecordMapper, sportRecordService);
    }

    /** 佩戴中返回完整元数据视图；进度按已解锁语义满格回填 */
    @Test
    void getEquippedBadge_equipped_returnsDtoWithFullMetadata() {
        UserBadge equipped = new UserBadge();
        equipped.setUserId(USER_ID);
        equipped.setBadgeCode("TEN_KM_RUN");
        equipped.setIsEquipped(1);
        equipped.setUnlockedAt(UNLOCKED_AT);
        when(userBadgeMapper.selectOne(any())).thenReturn(equipped);

        BadgeDTO dto = badgeService.getEquippedBadge(USER_ID);

        assertNotNull(dto);
        assertEquals("TEN_KM_RUN", dto.getCode());
        assertEquals("十公里突破", dto.getName());
        assertEquals("🥈", dto.getIcon());
        assertEquals("SILVER", dto.getTier());
        assertTrue(dto.getUnlocked());
        assertTrue(dto.getIsEquipped());
        assertEquals(UNLOCKED_AT, dto.getUnlockedAt());
        assertEquals(BadgeType.TEN_KM_RUN.getTargetValue(), dto.getCurrentValue());
        assertEquals(100, dto.getProgressPct());
    }

    /** 佩戴行编码为脏数据（不在 9 枚域内）：防御性返回 null */
    @Test
    void getEquippedBadge_dirtyCode_returnsNull() {
        UserBadge dirty = new UserBadge();
        dirty.setUserId(USER_ID);
        dirty.setBadgeCode("LEGACY_BADGE");
        dirty.setIsEquipped(1);
        when(userBadgeMapper.selectOne(any())).thenReturn(dirty);

        assertNull(badgeService.getEquippedBadge(USER_ID));
    }

    // ==================== 夹具 ====================

    private SportRecord record(SportType sportType, String distance, LocalDateTime startTime) {
        SportRecord record = new SportRecord();
        record.setUserId(USER_ID);
        record.setSportType(sportType.getCode());
        record.setDistance(new BigDecimal(distance));
        record.setStartTime(startTime);
        record.setStatus(2);
        return record;
    }

    private MyStatsDTO stats(long passCount, String totalDistance) {
        MyStatsDTO stats = new MyStatsDTO();
        stats.setPassCount(passCount);
        stats.setTotalDistance(new BigDecimal(totalDistance));
        return stats;
    }
}
