package com.sportverify.record.service;

import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.ClubDistanceContribution;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.ClubDistanceContributionMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跑团里程入账旁路单元测试（TASK-276 R2 立项，TASK-278 R2 锚点化重构）。
 *
 * <p>覆盖：净额方向（PASSED/RE_PASSED 入账、RE_CONFIRMED 回退、其余终态不动）、
 * <b>DB 锚点幂等</b>（首次 INSERT IGNORE 命中 / 已 CREDITED 幂等跳过 / 回退后 re-credit 补账与
 * 快照刷新 / 已回退或从未入账不产生负里程）、<b>回退取锚点快照</b>（clubId / weekStart /
 * distanceMeters 均不重查记录、不 resolveClub）、入团定位（未入团跳过 / user-service 降级跳过）、
 * 记录校验（缺失 / 里程非正跳过）、以及 best-effort 契约（锚点写库异常、Feign 降级、
 * 记录查询异常均<b>不得抛出</b>；Feign 失败<b>不回滚锚点占位</b>——裁决④）。</p>
 *
 * <p>纯 JVM：Mapper / UserApi / LeaderboardApi 全 Mockito 打桩，不依赖真实 Redis / MySQL /
 * 服务间网络（Redisson 依赖已随幂等键退役从本服务收敛移除）。</p>
 */
class ClubDistanceSettleServiceTest {

    private static final Long RECORD_ID = 9001L;
    private static final Long USER_ID = 100L;
    private static final Long CLUB_ID = 7L;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private SportRecordMapper sportRecordMapper;
    private ClubDistanceContributionMapper clubDistanceContributionMapper;
    private UserApi userApi;
    private LeaderboardApi leaderboardApi;
    private ClubDistanceSettleService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        clubDistanceContributionMapper = mock(ClubDistanceContributionMapper.class);
        userApi = mock(UserApi.class);
        leaderboardApi = mock(LeaderboardApi.class);
        service = new ClubDistanceSettleService(
                sportRecordMapper, clubDistanceContributionMapper, userApi, leaderboardApi);
    }

    private static SportRecord record(String distanceKm) {
        SportRecord record = new SportRecord();
        record.setId(RECORD_ID);
        record.setUserId(USER_ID);
        record.setDistance(distanceKm == null ? null : new BigDecimal(distanceKm));
        record.setStatus(RecordStatus.PASSED.getCode());
        return record;
    }

    private static ClubDTO club() {
        ClubDTO club = new ClubDTO();
        club.setId(CLUB_ID);
        club.setName("黎明跑团");
        club.setMemberCount(3);
        return club;
    }

    /** 回退锚点行：入账时点快照（团 42 / 2026-01-05 周 / 3400m） */
    private static ClubDistanceContribution anchorRow() {
        ClubDistanceContribution anchor = new ClubDistanceContribution();
        anchor.setRecordId(RECORD_ID);
        anchor.setClubId(42L);
        anchor.setUserId(USER_ID);
        anchor.setDistanceMeters(new BigDecimal("3400.00"));
        anchor.setWeekStart(LocalDate.of(2026, 1, 5));
        anchor.setStatus(ClubDistanceContribution.STATUS_CREDITED);
        return anchor;
    }

    private void stubRecord(String distanceKm) {
        when(sportRecordMapper.selectById(RECORD_ID)).thenReturn(record(distanceKm));
    }

    private void stubClub() {
        when(userApi.getClubByUserId(USER_ID)).thenReturn(Result.success(club()));
    }

    private static LocalDate currentWeekStart() {
        return LocalDate.now(BUSINESS_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LeaderboardApi.ClubDistancePayload capturePayload() {
        ArgumentCaptor<LeaderboardApi.ClubDistancePayload> cap =
                ArgumentCaptor.forClass(LeaderboardApi.ClubDistancePayload.class);
        verify(leaderboardApi).recordClubDistance(cap.capture());
        return cap.getValue();
    }

    private ClubDistanceContribution captureAnchor() {
        ArgumentCaptor<ClubDistanceContribution> cap =
                ArgumentCaptor.forClass(ClubDistanceContribution.class);
        verify(clubDistanceContributionMapper).insertIgnore(cap.capture());
        return cap.getValue();
    }

    // ==================== 入账方向（+distance，锚点 INSERT IGNORE） ====================

    /** PASSED 且该 recordId 无锚点：INSERT IGNORE 命中 1 行，落 CREDITED 锚点并上报 +distance。 */
    @Test
    void passed_firstTime_landsCreditedAnchorAndCreditsMeters() {
        stubRecord("5.00");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any())).thenReturn(1);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        ClubDistanceContribution anchor = captureAnchor();
        assertEquals(RECORD_ID, anchor.getRecordId());
        assertEquals(CLUB_ID, anchor.getClubId(), "入账时点团");
        assertEquals(USER_ID, anchor.getUserId());
        assertEquals(0, new BigDecimal("5000").compareTo(anchor.getDistanceMeters()), "公里→米快照");
        assertEquals(currentWeekStart(), anchor.getWeekStart(), "东八口径当周周一");
        assertEquals(ClubDistanceContribution.STATUS_CREDITED, anchor.getStatus());
        assertNotNull(anchor.getCreatedAt());

        LeaderboardApi.ClubDistancePayload payload = capturePayload();
        assertEquals(CLUB_ID, payload.getClubId());
        assertEquals("黎明跑团", payload.getClubName());
        assertEquals(5000.0, payload.getDistanceMeters(), "正净额入账");
        assertEquals(USER_ID, payload.getUserId(), "携带成员贡献 userId");
        assertEquals(currentWeekStart(), payload.getWeekStart(), "入账时点周锚点随 payload 上报");
        verify(clubDistanceContributionMapper, never()).reactivate(any(), anyInt());
    }

    /** RE_PASSED（申诉终判通过）同属入账方向，锚点首次落库。 */
    @Test
    void rePassed_firstTime_creditsDistance() {
        stubRecord("3.50");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any())).thenReturn(1);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_PASSED.getCode());

        assertEquals(3500.0, capturePayload().getDistanceMeters());
        verify(clubDistanceContributionMapper, never()).updateStatus(any(), anyInt(), anyInt(), any());
    }

    /** 重复投递/重放：INSERT IGNORE 命中 0 且现态仍 CREDITED（re-credit 迁移 0 行）→ 幂等跳过。 */
    @Test
    void passed_alreadyCredited_skipsIdempotently() {
        stubRecord("5.00");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any())).thenReturn(0);
        when(clubDistanceContributionMapper.reactivate(any(), anyInt())).thenReturn(0);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(leaderboardApi);
        verify(clubDistanceContributionMapper).reactivate(any(), eq(ClubDistanceContribution.STATUS_REVERSED));
    }

    /** 改判链路 RE_CONFIRMED → RE_PASSED：锚点 REVERSED → CREDITED 迁移成功并补账（快照同步刷新）。 */
    @Test
    void rePassed_afterReversal_reCreditsWithRefreshedSnapshot() {
        stubRecord("5.00");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any())).thenReturn(0);
        when(clubDistanceContributionMapper.reactivate(any(), anyInt())).thenReturn(1);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_PASSED.getCode());

        ArgumentCaptor<ClubDistanceContribution> cap =
                ArgumentCaptor.forClass(ClubDistanceContribution.class);
        verify(clubDistanceContributionMapper).reactivate(cap.capture(), eq(ClubDistanceContribution.STATUS_REVERSED));
        assertEquals(currentWeekStart(), cap.getValue().getWeekStart(), "re-credit 刷新为本次入账周锚点");
        assertEquals(0, new BigDecimal("5000").compareTo(cap.getValue().getDistanceMeters()));
        assertEquals(5000.0, capturePayload().getDistanceMeters(), "补账正净额");
    }

    // ==================== 回退方向（−distance，锚点快照） ====================

    /** RE_CONFIRMED 且锚点为 CREDITED：按锚点快照（旧团 / 旧周 / 快照里程）上报 −distance。 */
    @Test
    void reConfirmed_whenCredited_reversesByAnchorSnapshot() {
        when(clubDistanceContributionMapper.selectById(RECORD_ID)).thenReturn(anchorRow());
        when(clubDistanceContributionMapper.updateStatus(any(), anyInt(), anyInt(), any())).thenReturn(1);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode());

        LeaderboardApi.ClubDistancePayload payload = capturePayload();
        assertEquals(-3400.0, payload.getDistanceMeters(), "回退为负净额，取锚点快照值");
        assertEquals(42L, payload.getClubId(), "扣回入账时点所在团，而非当前所在团（N5）");
        assertEquals(LocalDate.of(2026, 1, 5), payload.getWeekStart(), "回退入账周锚点（跨周错扣）");
        assertEquals(USER_ID, payload.getUserId());
        verify(clubDistanceContributionMapper).updateStatus(eq(RECORD_ID),
                eq(ClubDistanceContribution.STATUS_CREDITED),
                eq(ClubDistanceContribution.STATUS_REVERSED), any());
        verifyNoInteractions(userApi);
        verify(sportRecordMapper, never()).selectById(anyLong());
    }

    /** RE_CONFIRMED 但从未入账（首次判定即 REJECTED 后改判维持拒绝）：不产生负里程。 */
    @Test
    void reConfirmed_whenNeverCredited_skips() {
        when(clubDistanceContributionMapper.selectById(RECORD_ID)).thenReturn(null);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode());

        verifyNoInteractions(leaderboardApi);
        verify(clubDistanceContributionMapper, never()).updateStatus(any(), anyInt(), anyInt(), any());
    }

    /** RE_CONFIRMED 但锚点已 REVERSED（重复事件）：乐观迁移 0 行 → 幂等跳过，不重复扣减。 */
    @Test
    void reConfirmed_alreadyReversed_skips() {
        when(clubDistanceContributionMapper.selectById(RECORD_ID)).thenReturn(anchorRow());
        when(clubDistanceContributionMapper.updateStatus(any(), anyInt(), anyInt(), any())).thenReturn(0);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode());

        verifyNoInteractions(leaderboardApi);
    }

    /** 锚点快照里程异常（脏数据）：不翻转状态、不上报（避免把负/零净额写进榜）。 */
    @Test
    void reConfirmed_anchorDistanceInvalid_skipsWithoutFlipping() {
        ClubDistanceContribution broken = anchorRow();
        broken.setDistanceMeters(BigDecimal.ZERO);
        when(clubDistanceContributionMapper.selectById(RECORD_ID)).thenReturn(broken);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode());

        verifyNoInteractions(leaderboardApi);
        verify(clubDistanceContributionMapper, never()).updateStatus(any(), anyInt(), anyInt(), any());
    }

    // ==================== 与跑团域无关的分支 ====================

    /** 非本域终态（APPEALING / REJECTED / VERIFYING 等）→ 既不查记录也不入账。 */
    @Test
    void nonTerminalStatus_skipsWithoutTouchingDependencies() {
        service.settleOnMigration(RECORD_ID, RecordStatus.APPEALING.getCode());
        service.settleOnMigration(RECORD_ID, RecordStatus.REJECTED.getCode());
        service.settleOnMigration(RECORD_ID, RecordStatus.VERIFYING.getCode());

        verifyNoInteractions(sportRecordMapper);
        verifyNoInteractions(clubDistanceContributionMapper);
        verifyNoInteractions(leaderboardApi);
        verifyNoInteractions(userApi);
    }

    /** 记录缺失：入账跳过（脏事件不重试），不查跑团、不落锚点。 */
    @Test
    void missingRecord_skips() {
        when(sportRecordMapper.selectById(RECORD_ID)).thenReturn(null);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(userApi);
        verifyNoInteractions(clubDistanceContributionMapper);
        verifyNoInteractions(leaderboardApi);
    }

    /** 里程缺失 / 非正：跳过（不产生 0 或负的假入账）。 */
    @Test
    void nonPositiveDistance_skips() {
        when(sportRecordMapper.selectById(RECORD_ID)).thenReturn(record(null));
        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        when(sportRecordMapper.selectById(RECORD_ID)).thenReturn(record("0.00"));
        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(userApi);
        verifyNoInteractions(clubDistanceContributionMapper);
        verifyNoInteractions(leaderboardApi);
    }

    /** 用户未入团（data=null）：跳过，不落锚点、不写榜单。 */
    @Test
    void userWithoutClub_skips() {
        stubRecord("5.00");
        when(userApi.getClubByUserId(USER_ID)).thenReturn(Result.success(null));

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(clubDistanceContributionMapper);
        verifyNoInteractions(leaderboardApi);
    }

    /** user-service 降级（抛异常）：跳过且不抛出。 */
    @Test
    void clubLookupFails_skipsWithoutThrowing() {
        stubRecord("5.00");
        when(userApi.getClubByUserId(USER_ID)).thenThrow(new BizException(ResultCode.USER_SERVICE_UNAVAILABLE));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode()));
        verifyNoInteractions(leaderboardApi);
    }

    // ==================== best-effort 契约 ====================

    /** 上报端点降级（4008）：锚点占位保留不回滚（裁决④），异常不上抛，状态回调零阻断。 */
    @Test
    void leaderboardFailure_isSwallowed_andAnchorNotRolledBack() {
        stubRecord("5.00");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any())).thenReturn(1);
        when(leaderboardApi.recordClubDistance(any())).thenThrow(
                new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode()));
        verify(leaderboardApi).recordClubDistance(any());
        verify(clubDistanceContributionMapper, never()).updateStatus(any(), anyInt(), anyInt(), any());
    }

    /** 锚点写库异常（DB 抖动）：只告警不抛出，不上报（无锚点即无权威依据）。 */
    @Test
    void anchorWriteFails_isSwallowed() {
        stubRecord("5.00");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any()))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode()));
        verifyNoInteractions(leaderboardApi);
    }

    /** 记录查询本身异常（DB 抖动）：同样只告警不抛出。 */
    @Test
    void recordLookupFails_isSwallowed() {
        when(sportRecordMapper.selectById(RECORD_ID)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode()));
        verifyNoInteractions(leaderboardApi);
    }

    /** 回退路径锚点查询异常：只告警不抛出（不阻断状态回调主链路）。 */
    @Test
    void anchorLookupFailsOnReverse_isSwallowed() {
        when(clubDistanceContributionMapper.selectById(RECORD_ID))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode()));
        verifyNoInteractions(leaderboardApi);
    }

    /** 入参缺失（null recordId / null toStatus）：静默返回，不触碰任何依赖。 */
    @Test
    void nullArgs_returnSilently() {
        service.settleOnMigration(null, RecordStatus.PASSED.getCode());
        service.settleOnMigration(RECORD_ID, null);

        verifyNoInteractions(sportRecordMapper);
        verifyNoInteractions(clubDistanceContributionMapper);
        verifyNoInteractions(leaderboardApi);
        verifyNoInteractions(userApi);
    }

    /** 东八周锚点形态断言（Clock 无关）：恒为 ISO 周一，且不晚于东八当日。 */
    @Test
    void creditedWeekStart_isAlwaysMonday() {
        stubRecord("1.00");
        stubClub();
        when(clubDistanceContributionMapper.insertIgnore(any())).thenReturn(1);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        LocalDate weekStart = capturePayload().getWeekStart();
        assertNotNull(weekStart);
        assertEquals(DayOfWeek.MONDAY, weekStart.getDayOfWeek());
        assertFalse(weekStart.isAfter(LocalDate.now(BUSINESS_ZONE)), "周锚点不得晚于东八当日");
    }
}
