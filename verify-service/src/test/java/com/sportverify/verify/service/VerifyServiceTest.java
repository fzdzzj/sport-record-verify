package com.sportverify.verify.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.AppealStatus;
import com.sportverify.api.verify.Verdict;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.verify.algorithm.VerifyEngine;
import com.sportverify.verify.algorithm.model.VerdictResult;
import com.sportverify.verify.config.VerifyProperties;
import com.sportverify.verify.entity.Appeal;
import com.sportverify.verify.entity.VerificationResult;
import com.sportverify.verify.mapper.AppealMapper;
import com.sportverify.verify.mapper.VerificationResultMapper;
import com.sportverify.verify.mq.VerifyEventProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验服务单元测试（判定主流程 / 校验幂等 / 终判维持拒绝与改判 / 申诉幂等）。
 *
 * <p>纯 Mockito：VerifyEngine / 两个 Mapper / RecordApi / VerifyEventProducer /
 * RuleVersionService / 本地缓存全部 mock；VerifyProperties 与 ObjectMapper 用真实小对象。
 * R5 熔断降级「不命中」分支已在 R5OffRoadRuleTest 覆盖，此处聚焦服务编排分支。</p>
 */
class VerifyServiceTest {

    private VerifyEngine verifyEngine;
    private VerificationResultMapper verificationResultMapper;
    private AppealMapper appealMapper;
    private com.sportverify.api.record.RecordApi recordApi;
    private VerifyEventProducer verifyEventProducer;
    private RuleVersionService ruleVersionService;
    private Cache<String, Object> caffeineCache;
    private VerifyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        verifyEngine = mock(VerifyEngine.class);
        verificationResultMapper = mock(VerificationResultMapper.class);
        appealMapper = mock(AppealMapper.class);
        recordApi = mock(com.sportverify.api.record.RecordApi.class);
        verifyEventProducer = mock(VerifyEventProducer.class);
        ruleVersionService = mock(RuleVersionService.class);
        caffeineCache = mock(Cache.class);
        service = new VerifyService(verifyEngine, verificationResultMapper, appealMapper,
                recordApi, verifyEventProducer, new VerifyProperties(),
                ruleVersionService, caffeineCache, new ObjectMapper());
    }

    private SportRecordDTO record(Long id, Integer status, Integer sportType, Integer version) {
        SportRecordDTO r = new SportRecordDTO();
        r.setId(id);
        r.setUserId(100L);
        r.setStatus(status);
        r.setSportType(sportType);
        r.setVersion(version);
        return r;
    }

    private TrackPointDTO point() {
        TrackPointDTO p = new TrackPointDTO();
        p.setLat(new BigDecimal("31.2300"));
        p.setLng(new BigDecimal("121.4737"));
        p.setTs(1_700_000_000_000L);
        return p;
    }

    private void stubHappyVerify() {
        when(verificationResultMapper.initVerifying(1L)).thenReturn(1);
        when(recordApi.getRecord(1L)).thenReturn(Result.success(
                record(1L, RecordStatus.VERIFYING.getCode(), SportType.RUNNING.getCode(), 0)));
        when(recordApi.listPoints(1L)).thenReturn(Result.success(List.of(point())));
        when(ruleVersionService.getActiveRulesForUser(100L)).thenReturn(new VerifyProperties());
        when(verifyEngine.verify(any(), any(), any()))
                .thenReturn(VerdictResult.builder().verdict(Verdict.PASSED).score(70).build());
        when(recordApi.statusCallback(anyLong(), any(StatusCallbackDTO.class)))
                .thenReturn(Result.success());
    }

    // ==================== 判定主流程 ====================

    /** 判定主路径：占位 → 拉轨迹 → 引擎判定 → 落库 → 发事件 → 回调迁移 → 缓存 */
    @Test
    void verify_mainPath_passesAndPublishes() {
        stubHappyVerify();

        VerdictResult result = service.verify(1L);

        assertEquals(1L, result.getRecordId());
        assertEquals(Verdict.PASSED, result.getVerdict());
        verify(verificationResultMapper).initVerifying(1L);
        verify(verificationResultMapper).upsert(any());
        verify(verifyEventProducer).publish(Verdict.PASSED, 1L, 100L);
        ArgumentCaptor<StatusCallbackDTO> cb = ArgumentCaptor.forClass(StatusCallbackDTO.class);
        verify(recordApi).statusCallback(eq(1L), cb.capture());
        assertEquals(RecordStatus.VERIFYING.getCode(), cb.getValue().getFromStatus());
        assertEquals(RecordStatus.PASSED.getCode(), cb.getValue().getToStatus());
        verify(caffeineCache).put(anyString(), any());
    }

    /** 结果已终判且 record 已同步 → 命中本地缓存直接返回，不重算 */
    @Test
    void verify_cachedFinal_returnsWithoutRecompute() {
        VerdictResult cached = VerdictResult.builder().verdict(Verdict.PASSED).score(70)
                .recordId(1L).build();
        when(caffeineCache.getIfPresent("verify:result:1")).thenReturn(cached);
        when(recordApi.getRecord(1L)).thenReturn(Result.success(
                record(1L, RecordStatus.PASSED.getCode(), SportType.RUNNING.getCode(), 1)));

        VerdictResult result = service.verify(1L);

        assertSame(cached, result);
        verify(verificationResultMapper, never()).initVerifying(anyLong());
        verify(verifyEventProducer, never()).publish(any(), anyLong(), anyLong());
    }

    /** 结果已终判但 record 仍 VERIFYING（历史回调失败）→ 仅补偿回调迁到终态，不再重算 */
    @Test
    void verify_dbFinalRecordStillVerifying_reconcilesCallbackOnly() {
        VerificationResult row = new VerificationResult();
        row.setRecordId(1L);
        row.setVerdict(Verdict.PASSED.getCode());
        row.setScore(70);
        when(verificationResultMapper.selectById(1L)).thenReturn(row);
        when(recordApi.getRecord(1L)).thenReturn(Result.success(
                record(1L, RecordStatus.VERIFYING.getCode(), SportType.RUNNING.getCode(), 0)));
        when(recordApi.statusCallback(anyLong(), any(StatusCallbackDTO.class)))
                .thenReturn(Result.success());

        VerdictResult result = service.verify(1L);

        assertEquals(Verdict.PASSED, result.getVerdict());
        verify(recordApi).statusCallback(eq(1L), any(StatusCallbackDTO.class));
        verify(verificationResultMapper, never()).initVerifying(anyLong());
        verify(verifyEventProducer, never()).publish(any(), anyLong(), anyLong());
    }

    // ==================== 终判（维持拒绝 / 改判通过） ====================

    private void stubAppeal(int targetStatus) {
        Appeal appeal = new Appeal();
        appeal.setId(9L);
        appeal.setRecordId(5L);
        appeal.setUserId(100L);
        appeal.setStatus(AppealStatus.PENDING.getCode());
        when(appealMapper.selectById(9L)).thenReturn(appeal);
        when(appealMapper.updateStatus(eq(9L), eq(AppealStatus.PENDING.getCode()),
                eq(targetStatus), eq("admin"), anyString())).thenReturn(1);
        when(recordApi.getRecord(5L)).thenReturn(Result.success(
                record(5L, RecordStatus.APPEALING.getCode(), SportType.RUNNING.getCode(), 1)));
        when(recordApi.statusCallback(anyLong(), any(StatusCallbackDTO.class)))
                .thenReturn(Result.success());
    }

    /** 终判维持拒绝（pass=false）：appeal → RE_CONFIRMED，回调 record → RE_CONFIRMED，发 REJECTED 事件 */
    @Test
    void reviewAppeal_reConfirmed_staysRejected() {
        stubAppeal(AppealStatus.RE_CONFIRMED.getCode());
        AppealReviewDTO dto = new AppealReviewDTO();
        dto.setPass(false);
        dto.setOperator("admin");
        dto.setRecheckResult("复核驳回");

        AppealDTO result = service.reviewAppeal(9L, dto);

        assertEquals(AppealStatus.RE_CONFIRMED.getCode(), result.getStatus());
        assertEquals("admin", result.getOperator());
        verify(verifyEventProducer).publish(Verdict.REJECTED, 5L, 100L);
        ArgumentCaptor<StatusCallbackDTO> cb = ArgumentCaptor.forClass(StatusCallbackDTO.class);
        verify(recordApi).statusCallback(eq(5L), cb.capture());
        assertEquals(RecordStatus.APPEALING.getCode(), cb.getValue().getFromStatus());
        assertEquals(RecordStatus.RE_CONFIRMED.getCode(), cb.getValue().getToStatus());
    }

    /** 终判改判通过（pass=true）：appeal → RE_PASSED，回调 record → RE_PASSED，发 VERIFIED 事件 */
    @Test
    void reviewAppeal_rePassed_allowsThrough() {
        stubAppeal(AppealStatus.RE_PASSED.getCode());
        AppealReviewDTO dto = new AppealReviewDTO();
        dto.setPass(true);
        dto.setOperator("admin");
        dto.setRecheckResult("复核通过");

        AppealDTO result = service.reviewAppeal(9L, dto);

        assertEquals(AppealStatus.RE_PASSED.getCode(), result.getStatus());
        verify(verifyEventProducer).publish(Verdict.PASSED, 5L, 100L);
    }

    /** 非 PENDING 申诉单不可终判 */
    @Test
    void reviewAppeal_notPending_rejected() {
        Appeal appeal = new Appeal();
        appeal.setId(9L);
        appeal.setStatus(AppealStatus.RE_CONFIRMED.getCode());
        when(appealMapper.selectById(9L)).thenReturn(appeal);

        BizException e = assertThrows(BizException.class,
                () -> service.reviewAppeal(9L, dto(true)));
        assertEquals(ResultCode.APPEAL_STATUS_INVALID.getCode(), e.getCode());
    }

    /** 终判乐观锁冲突（影响 0 行）→ 3006 */
    @Test
    void reviewAppeal_conflict_zeroRows_throws() {
        Appeal appeal = new Appeal();
        appeal.setId(9L);
        appeal.setRecordId(5L);
        appeal.setStatus(AppealStatus.PENDING.getCode());
        when(appealMapper.selectById(9L)).thenReturn(appeal);
        when(appealMapper.updateStatus(anyLong(), any(), any(), anyString(), anyString())).thenReturn(0);

        BizException e = assertThrows(BizException.class,
                () -> service.reviewAppeal(9L, dto(true)));
        assertEquals(ResultCode.APPEAL_STATUS_INVALID.getCode(), e.getCode());
    }

    private AppealReviewDTO dto(boolean pass) {
        AppealReviewDTO d = new AppealReviewDTO();
        d.setPass(pass);
        d.setOperator("admin");
        return d;
    }

    // ==================== 申诉幂等 ====================

    /** 建申诉单唯一键冲突 → 幂等返回既有申诉单（record_id 唯一） */
    @Test
    void createAppeal_duplicateKey_returnsExisting() {
        Appeal existing = new Appeal();
        existing.setId(3L);
        existing.setRecordId(5L);
        existing.setStatus(AppealStatus.PENDING.getCode());
        when(appealMapper.insert(any(Appeal.class))).thenThrow(new DuplicateKeyException("uk_record"));
        when(appealMapper.selectOne(any())).thenReturn(existing);

        AppealDTO result = service.createAppeal(new AppealCreateDTO(5L, 100L, "轨迹异常"));

        assertEquals(3L, result.getId());
        assertEquals(AppealStatus.PENDING.getCode(), result.getStatus());
    }

    /** 查询缺省：无判定结果 → 返回 VERIFYING 占位 */
    @Test
    void getVerificationResult_noRow_returnsVerifyingPlaceholder() {
        when(verificationResultMapper.selectById(1L)).thenReturn(null);

        com.sportverify.api.verify.dto.VerificationResultDTO dto =
                service.getVerificationResult(1L);

        assertEquals(1L, dto.getRecordId());
        assertEquals(Verdict.VERIFYING.getCode(), dto.getVerdict());
    }
}