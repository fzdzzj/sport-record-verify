package com.sportverify.record.service;

import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跑团里程入账旁路单元测试（TASK-276 R2）。
 *
 * <p>覆盖：净额方向（PASSED/RE_PASSED 入账、RE_CONFIRMED 回退、其余终态不动）、recordId 级
 * 幂等（首次 trySet / 已入账跳过 / 回退后再入账 redis 状态迁移 / 未入账回退不产生负里程）、
 * 入团定位（未入团跳过 / user-service 降级跳过）、记录校验（缺失 / 里程非正跳过）、
 * 以及 best-effort 契约（leaderboard-service 抛异常时本方法<b>不得抛出</b>）。</p>
 *
 * <p>纯 JVM：Mapper / Redisson RBucket / UserApi / LeaderboardApi 全 Mockito 打桩，
 * 不依赖真实 Redis / MySQL / 服务间网络。</p>
 */
class ClubDistanceSettleServiceTest {

    private static final Long RECORD_ID = 9001L;
    private static final Long USER_ID = 100L;
    private static final Long CLUB_ID = 7L;

    private SportRecordMapper sportRecordMapper;
    private RedissonClient redissonClient;
    private RBucket<String> bucket;
    private UserApi userApi;
    private LeaderboardApi leaderboardApi;
    private ClubDistanceSettleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        userApi = mock(UserApi.class);
        leaderboardApi = mock(LeaderboardApi.class);
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        service = new ClubDistanceSettleService(sportRecordMapper, redissonClient, userApi, leaderboardApi);
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

    private void stubRecord(String distanceKm) {
        when(sportRecordMapper.selectById(RECORD_ID)).thenReturn(record(distanceKm));
    }

    private void stubClub() {
        when(userApi.getClubByUserId(USER_ID)).thenReturn(Result.success(club()));
    }

    private LeaderboardApi.ClubDistancePayload capturePayload() {
        ArgumentCaptor<LeaderboardApi.ClubDistancePayload> cap =
                ArgumentCaptor.forClass(LeaderboardApi.ClubDistancePayload.class);
        verify(leaderboardApi).recordClubDistance(cap.capture());
        return cap.getValue();
    }

    // ==================== 入账方向（+distance） ====================

    /** PASSED 且该 recordId 未入账：按公里→米换算上报 +distance，并携带成员明细 userId。 */
    @Test
    void passed_notYetCredited_creditsDistanceInMeters() {
        stubRecord("5.00");
        stubClub();
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        LeaderboardApi.ClubDistancePayload payload = capturePayload();
        assertEquals(CLUB_ID, payload.getClubId());
        assertEquals("黎明跑团", payload.getClubName());
        assertEquals(5000.0, payload.getDistanceMeters(), "公里→米换算");
        assertEquals(USER_ID, payload.getUserId(), "携带成员贡献 userId");
    }

    /** RE_PASSED（申诉终判通过）同属入账方向。 */
    @Test
    void rePassed_creditsDistance() {
        stubRecord("3.50");
        stubClub();
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_PASSED.getCode());

        assertEquals(3500.0, capturePayload().getDistanceMeters());
    }

    /** 重复投递/重放：trySet 失败且现值仍为 CREDITED → 幂等跳过，不重复入账。 */
    @Test
    void passed_alreadyCredited_skipsIdempotently() {
        stubRecord("5.00");
        stubClub();
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(bucket.compareAndSet(ClubDistanceSettleService.STATE_REVERSED,
                ClubDistanceSettleService.STATE_CREDITED)).thenReturn(false);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(leaderboardApi);
    }

    /** 改判链路 RE_CONFIRMED → RE_PASSED：幂等态从 REVERSED 迁回 CREDITED 并补账，TTL 续期。 */
    @Test
    void rePassed_afterReversal_reCreditsAndRefreshesTtl() {
        stubRecord("5.00");
        stubClub();
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(bucket.compareAndSet(ClubDistanceSettleService.STATE_REVERSED,
                ClubDistanceSettleService.STATE_CREDITED)).thenReturn(true);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_PASSED.getCode());

        assertEquals(5000.0, capturePayload().getDistanceMeters());
        verify(bucket).expire(Duration.ofHours(ClubDistanceSettleService.SETTLE_TTL_HOURS));
    }

    // ==================== 回退方向（−distance） ====================

    /** RE_CONFIRMED 且此前已入账：上报 -distance（负净额），并落 REVERSED 态。 */
    @Test
    void reConfirmed_whenCredited_reversesDistance() {
        stubRecord("5.00");
        stubClub();
        when(bucket.compareAndSet(ClubDistanceSettleService.STATE_CREDITED,
                ClubDistanceSettleService.STATE_REVERSED)).thenReturn(true);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode());

        assertEquals(-5000.0, capturePayload().getDistanceMeters(), "回退为负净额");
        verify(bucket).expire(Duration.ofHours(ClubDistanceSettleService.SETTLE_TTL_HOURS));
        verify(bucket, never()).trySet(anyString(), anyLong(), any(TimeUnit.class));
    }

    /** RE_CONFIRMED 但从未入账（首次判定即 REJECTED 后改判维持拒绝）：不产生负里程。 */
    @Test
    void reConfirmed_whenNeverCredited_skips() {
        stubRecord("5.00");
        stubClub();
        when(bucket.compareAndSet(ClubDistanceSettleService.STATE_CREDITED,
                ClubDistanceSettleService.STATE_REVERSED)).thenReturn(false);

        service.settleOnMigration(RECORD_ID, RecordStatus.RE_CONFIRMED.getCode());

        verifyNoInteractions(leaderboardApi);
    }

    // ==================== 与跑团域无关的分支 ====================

    /** 非本域终态（APPEALING / REJECTED / VERIFYING 等）→ 既不查记录也不入账。 */
    @Test
    void nonTerminalStatus_skipsWithoutTouchingDependencies() {
        service.settleOnMigration(RECORD_ID, RecordStatus.APPEALING.getCode());
        service.settleOnMigration(RECORD_ID, RecordStatus.REJECTED.getCode());
        service.settleOnMigration(RECORD_ID, RecordStatus.VERIFYING.getCode());

        verifyNoInteractions(sportRecordMapper);
        verifyNoInteractions(leaderboardApi);
        verifyNoInteractions(userApi);
    }

    /** 记录缺失：跳过（脏事件不重试），不查跑团。 */
    @Test
    void missingRecord_skips() {
        when(sportRecordMapper.selectById(RECORD_ID)).thenReturn(null);

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(userApi);
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
        verifyNoInteractions(leaderboardApi);
    }

    /** 用户未入团（data=null）：跳过，不写榜单。 */
    @Test
    void userWithoutClub_skips() {
        stubRecord("5.00");
        when(userApi.getClubByUserId(USER_ID)).thenReturn(Result.success(null));

        service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode());

        verifyNoInteractions(leaderboardApi);
        verify(bucket, never()).trySet(anyString(), anyLong(), any(TimeUnit.class));
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

    /** 入账端点降级（4008）：本方法吞并异常不外抛，状态回调主链路零阻断。 */
    @Test
    void leaderboardFailure_isSwallowed() {
        stubRecord("5.00");
        stubClub();
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(leaderboardApi.recordClubDistance(any())).thenThrow(
                new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode()));
        verify(leaderboardApi).recordClubDistance(any());
    }

    /** 记录查询本身异常（DB 抖动）：同样只告警不抛出。 */
    @Test
    void recordLookupFails_isSwallowed() {
        when(sportRecordMapper.selectById(RECORD_ID)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.settleOnMigration(RECORD_ID, RecordStatus.PASSED.getCode()));
        verifyNoInteractions(leaderboardApi);
    }

    /** 入参缺失（null recordId / null toStatus）：静默返回，不触碰任何依赖。 */
    @Test
    void nullArgs_returnSilently() {
        service.settleOnMigration(null, RecordStatus.PASSED.getCode());
        service.settleOnMigration(RECORD_ID, null);

        verifyNoInteractions(sportRecordMapper);
        verifyNoInteractions(redissonClient);
        verifyNoInteractions(leaderboardApi);
    }
}
