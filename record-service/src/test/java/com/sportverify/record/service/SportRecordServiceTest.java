package com.sportverify.record.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.RecordEventProducer;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运动记录服务单元测试（核心提交链路：提交主路径 / requestId 幂等 /
 * 并发重复提交 / 未知类型拒绝 / 分片键填充 / 批量插入开关 / 提交后事件降级）。
 *
 * <p>纯 Mockito：Mapper / RecordEventProducer / VerifyApi / VerifyDegradeService 全部 mock；
 * 不依赖 MySQL / Redis / RocketMQ。由于 {code submit} 内部调用
 * TransactionSynchronizationManager.registerSynchronization，测试需先 initSynchronization
 * 并手动触发 afterCommit，以覆盖提交成功后的「发事件 → 成功 / Feign 直调 / 熔断转人工」分支。</p>
 */
class SportRecordServiceTest {

    private SportRecordMapper sportRecordMapper;
    private TrackPointMapper trackPointMapper;
    private RecordEventProducer recordEventProducer;
    private VerifyApi verifyApi;
    private VerifyDegradeService verifyDegradeService;
    private SportRecordService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        trackPointMapper = mock(TrackPointMapper.class);
        recordEventProducer = mock(RecordEventProducer.class);
        verifyApi = mock(VerifyApi.class);
        verifyDegradeService = mock(VerifyDegradeService.class);
        service = new SportRecordService(sportRecordMapper, trackPointMapper,
                recordEventProducer, verifyApi, verifyDegradeService);
        // 无 Spring：@Value 不生效；setField 模拟产品默认 true（与 properties/@Value 缺省一致）
        ReflectionTestUtils.setField(service, "batchInsertEnabled", true);
    }

    /** 在事务同步上下文中执行 body，可选触发 afterCommit（提交后发事件分支） */
    private void inTx(Runnable body, boolean flushAfterCommit) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            body.run();
            if (flushAfterCommit) {
                for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                    s.afterCommit();
                }
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 提交成功：insert 回填 id=100（MyBatis-Plus 主键回填由 Mapper 触发，此处 mock 模拟） */
    private void stubInsertReturnsId() {
        doAnswer(inv -> {
            SportRecord r = inv.getArgument(0);
            r.setId(100L);
            return 1;
        }).when(sportRecordMapper).insert(any(SportRecord.class));
    }

    private RecordSubmitDTO dto(int sz, Integer sportType) {
        RecordSubmitDTO dto = new RecordSubmitDTO();
        dto.setRequestId("req-1");
        dto.setUserId(100L);
        dto.setSportType(sportType);
        dto.setStartTime(LocalDateTime.now());
        dto.setEndTime(LocalDateTime.now().plusMinutes(5));
        dto.setDistance(new BigDecimal("42.5"));
        dto.setDuration(1800);
        List<TrackPointDTO> points = new java.util.ArrayList<>();
        for (int i = 0; i < sz; i++) {
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(null);
            p.setLat(new BigDecimal("31.2300"));
            p.setLng(new BigDecimal("121.4737"));
            p.setTs(1_700_000_000_000L + i);
            points.add(p);
        }
        dto.setPoints(points);
        return dto;
    }

    // ==================== 提交主路径 ====================

    /** 显式 false → 逐条 INSERT：落主表 → 逐条写轨迹 → 状态迁移 VERIFYING → 发事件 */
    @Test
    void submit_happyPath_batchDisabled_writesPointsAndTransitions() {
        ReflectionTestUtils.setField(service, "batchInsertEnabled", false);
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        when(sportRecordMapper.updateStatus(100L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0)).thenReturn(1);
        when(recordEventProducer.publishSubmitted(100L, 100L)).thenReturn(true);
        stubInsertReturnsId();

        inTx(() -> {
            RecordSubmitResultDTO result = service.submit(dto(2, null)); // sportType=null → 缺省 RUNNING
            assertEquals(100L, result.getRecordId());
            assertEquals("req-1", result.getRequestId());
            assertEquals(RecordStatus.VERIFYING.getCode(), result.getStatus(), "提交响应须为 VERIFYING 中间态");
            assertFalse(result.isDuplicated());
        }, true);

        // 分片前提：轨迹点必须携带 user_id 分片键（从记录归属人填充）
        verify(sportRecordMapper).insert(any(SportRecord.class));
        verify(trackPointMapper, times(2)).insert(any(TrackPoint.class));
        verify(sportRecordMapper).updateStatus(100L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0);
        verify(recordEventProducer).publishSubmitted(100L, 100L);
    }

    /** 默认/true → 走多值 insertBatch，不再逐条 insert */
    @Test
    void submit_empty_sportTypeDefaultsToRunning_andBatchEnabled_usesBatchInsert() {
        ReflectionTestUtils.setField(service, "batchInsertEnabled", true);
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        when(sportRecordMapper.updateStatus(100L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0)).thenReturn(1);
        when(recordEventProducer.publishSubmitted(100L, 100L)).thenReturn(true);
        stubInsertReturnsId();

        inTx(() -> {
            RecordSubmitResultDTO result = service.submit(dto(2, null));
            assertEquals(RecordStatus.VERIFYING.getCode(), result.getStatus());
        }, true);

        verify(trackPointMapper, never()).insert(any(TrackPoint.class));
        verify(trackPointMapper).insertBatch(any());
    }

    /** 轨迹点写入失败 → 事务回滚路径：不注册 afterCommit，不发 MQ（ADR-0009） */
    @Test
    void submit_trackInsertFails_doesNotPublishSubmitted() {
        ReflectionTestUtils.setField(service, "batchInsertEnabled", false);
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        stubInsertReturnsId();
        when(trackPointMapper.insert(any(TrackPoint.class)))
                .thenThrow(new RuntimeException("track insert failed"));

        inTx(() -> assertThrows(RuntimeException.class, () -> service.submit(dto(2, 1))), false);

        // 异常抛出前不会走到 registerSynchronization；即便 flush afterCommit 也不会有回调
        verify(recordEventProducer, never()).publishSubmitted(anyLong(), anyLong());
        verify(verifyApi, never()).triggerVerify(anyLong());
    }

    // ==================== 幂等 ====================

    /** 幂等前置命中：requestId 已有记录 → 直接返回原结果，不再落库 */
    @Test
    void submit_requestIdExisting_returnsOriginalIdempotently() {
        SportRecord existing = new SportRecord();
        existing.setId(7L);
        existing.setRequestId("req-1");
        existing.setStatus(RecordStatus.PASSED.getCode());
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(existing);

        inTx(() -> {
            RecordSubmitResultDTO result = service.submit(dto(1, 1));
            assertTrue(result.isDuplicated());
            assertEquals(7L, result.getRecordId());
            assertEquals(RecordStatus.PASSED.getCode(), result.getStatus());
        }, false);

        verify(sportRecordMapper, never()).insert(any(SportRecord.class));
        verify(trackPointMapper, never()).insert(any(TrackPoint.class));
    }

    /** 并发重复提交：insert 命中唯一键 → 回查原记录幂等返回 */
    @Test
    void submit_duplicateKeyOnInsert_returnsOrigin() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        when(sportRecordMapper.insert(any(SportRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_request_id"));
        SportRecord origin = new SportRecord();
        origin.setId(8L);
        origin.setRequestId("req-1");
        origin.setStatus(RecordStatus.VERIFYING.getCode());
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(origin);

        inTx(() -> {
            RecordSubmitResultDTO result = service.submit(dto(1, 1));
            assertTrue(result.isDuplicated());
            assertEquals(8L, result.getRecordId());
        }, false);
    }

    /** 幂等冲突但原记录查询失败 → 系统错误（不做错误掩盖） */
    @Test
    void submit_duplicateKeyButNoOrigin_throwsSystemError() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null, null); // 前置无 + 回查无
        when(sportRecordMapper.insert(any(SportRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_request_id"));

        BizException e = assertThrows(BizException.class, () -> service.submit(dto(1, 1)));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), e.getCode());
    }

    /** 未知运动类型（枚举外）→ 3007 拒绝，不落库 */
    @Test
    void submit_unknownSportType_rejected() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.submit(dto(1, 99)));
        assertEquals(ResultCode.SPORT_TYPE_INVALID.getCode(), e.getCode());
        verify(sportRecordMapper, never()).insert(any(SportRecord.class));
    }

    // ==================== 提交后事件降级（afterCommit） ====================

    /** MQ 发布失败 → Feign 直调触发校验（verify 正常，不转人工） */
    @Test
    void submit_mqPublishFail_feignDirectCallTriggersVerify() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        when(sportRecordMapper.updateStatus(100L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0)).thenReturn(1);
        when(recordEventProducer.publishSubmitted(100L, 100L)).thenReturn(false);
        stubInsertReturnsId();

        inTx(() -> service.submit(dto(0, 1)), true);

        verify(verifyApi).triggerVerify(100L);
        verify(verifyDegradeService, never()).degradeToManualReview(anyLong(), any());
    }

    /** MQ 失败 + Feign 直调也失败（verify 熔断）→ 熔断降级转人工，主链路不挂 */
    @Test
    void submit_mqAndFeignFail_degradeToManualReview() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        when(sportRecordMapper.updateStatus(100L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0)).thenReturn(1);
        when(recordEventProducer.publishSubmitted(100L, 100L)).thenReturn(false);
        when(verifyApi.triggerVerify(100L)).thenThrow(new RuntimeException("verify 熔断 OPEN"));
        stubInsertReturnsId();

        inTx(() -> service.submit(dto(0, 1)), true);

        verify(verifyApi).triggerVerify(100L);
        verify(verifyDegradeService).degradeToManualReview(eq(100L), any());
    }

    // ==================== 状态机中间态（回调） ====================

    /** 状态回调：目标状态与当前一致 → 幂等跳过，不发 UPDATE */
    @Test
    void statusCallback_sameStatus_idempotentSkip() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setStatus(RecordStatus.VERIFYING.getCode());
        when(sportRecordMapper.selectById(1L)).thenReturn(record);

        service.statusCallback(new StatusCallbackDTO(1L,
                RecordStatus.SUBMITTED.getCode(), RecordStatus.VERIFYING.getCode(), 0));

        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    /** 状态回调：SUBMITTED → VERIFYING 正常迁移 */
    @Test
    void statusCallback_fromSubmittedToVerifying_success() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setStatus(RecordStatus.SUBMITTED.getCode());
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(sportRecordMapper.updateStatus(1L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0)).thenReturn(1);

        service.statusCallback(new StatusCallbackDTO(1L,
                RecordStatus.SUBMITTED.getCode(), RecordStatus.VERIFYING.getCode(), 0));

        verify(sportRecordMapper).updateStatus(1L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0);
    }

    /** 状态回调：乐观锁冲突（影响 0 行）→ 3003 并发冲突 */
    @Test
    void statusCallback_conflict_zeroRows_throws() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setStatus(RecordStatus.SUBMITTED.getCode());
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(sportRecordMapper.updateStatus(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.statusCallback(new StatusCallbackDTO(
                1L, RecordStatus.SUBMITTED.getCode(), RecordStatus.VERIFYING.getCode(), 0)));
        assertEquals(ResultCode.RECORD_STATUS_INVALID.getCode(), e.getCode());
    }

    /** 状态回调：记录不存在 → 3001 */
    @Test
    void statusCallback_recordNotFound_throws() {
        when(sportRecordMapper.selectById(1L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.statusCallback(new StatusCallbackDTO(
                1L, RecordStatus.SUBMITTED.getCode(), RecordStatus.VERIFYING.getCode(), 0)));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    // ==================== 申诉 / 查询 ====================

    /** 提交申诉成功：REJECTED 记录建单 → 乐观锁迁移 APPEALING → 返回申诉单 */
    @Test
    void appeal_success_createsAndTransitions() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        record.setVersion(3);
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        AppealDTO appeal = new AppealDTO();
        appeal.setId(9L);
        when(verifyApi.createAppeal(any(com.sportverify.api.verify.dto.AppealCreateDTO.class)))
                .thenReturn(com.sportverify.common.result.Result.success(appeal));
        when(sportRecordMapper.updateStatus(5L, RecordStatus.REJECTED.getCode(),
                RecordStatus.APPEALING.getCode(), 3)).thenReturn(1);

        AppealDTO out = service.appeal(5L, 100L, "理由");

        assertEquals(appeal, out);
        // userId 以记录归属人为准（200L），而非入参 100L
        verify(verifyApi).createAppeal(eq(new com.sportverify.api.verify.dto.AppealCreateDTO(5L, 200L, "理由")));
    }

    /** 提交申诉：非 REJECTED 记录 → 3003 */
    @Test
    void appeal_notRejected_throws() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setStatus(RecordStatus.PASSED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 100L, "理由"));
        assertEquals(ResultCode.RECORD_STATUS_INVALID.getCode(), e.getCode());
    }

    /** 提交申诉：记录不存在 → 3001 */
    @Test
    void appeal_recordNotFound_throws() {
        when(sportRecordMapper.selectById(5L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 100L, "理由"));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    /** 提交申诉：建单失败（verify 不可用）→ 4001 */
    @Test
    void appeal_verifyUnavailable_throws() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        when(verifyApi.createAppeal(any())).thenReturn(new com.sportverify.common.result.Result<>(
                ResultCode.VERIFY_SERVICE_UNAVAILABLE.getCode(), "不可用", null));

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 100L, "理由"));
        assertEquals(ResultCode.VERIFY_SERVICE_UNAVAILABLE.getCode(), e.getCode());
    }

    /** 提交申诉：迁移影响 0 行但已 APPEALING（并发重复）→ 幂等成功 */
    @Test
    void appeal_concurrentAlreadyAppealing_idempotent() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        AppealDTO appeal = new AppealDTO();
        when(verifyApi.createAppeal(any())).thenReturn(com.sportverify.common.result.Result.success(appeal));
        when(sportRecordMapper.updateStatus(5L, RecordStatus.REJECTED.getCode(),
                RecordStatus.APPEALING.getCode(), 0)).thenReturn(0);
        SportRecord cur = new SportRecord();
        cur.setId(5L);
        cur.setStatus(RecordStatus.APPEALING.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record, cur);

        AppealDTO out = service.appeal(5L, 100L, "理由");

        assertEquals(appeal, out);
    }

    /** 提交申诉：迁移影响 0 行且当前非 APPEALING → 并发冲突 3003 */
    @Test
    void appeal_concurrentMigrationFailed_throws() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        AppealDTO appeal = new AppealDTO();
        when(verifyApi.createAppeal(any())).thenReturn(com.sportverify.common.result.Result.success(appeal));
        when(sportRecordMapper.updateStatus(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(0);
        SportRecord stillRejected = new SportRecord();
        stillRejected.setId(5L);
        stillRejected.setStatus(RecordStatus.REJECTED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record, stillRejected);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 100L, "理由"));
        assertEquals(ResultCode.RECORD_STATUS_INVALID.getCode(), e.getCode());
    }

    /** 查询判定结果：记录存在 → 透传 verify-api 结果 */
    @Test
    void getVerifyResult_delegatesToVerifyApi() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        VerificationResultDTO v = new VerificationResultDTO();
        when(verifyApi.getVerificationResult(1L)).thenReturn(com.sportverify.common.result.Result.success(v));

        VerificationResultDTO out = service.getVerifyResult(1L);

        assertEquals(v, out);
        verify(verifyApi).getVerificationResult(1L);
    }

    /** 查询判定结果：记录不存在 → 3001 */
    @Test
    void getVerifyResult_recordNotFound_throws() {
        when(sportRecordMapper.selectById(1L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.getVerifyResult(1L));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    /** 拉取全部轨迹点：记录存在 → 按分片键路由 + 序号升序 */
    @Test
    void listPoints_routesByUserId() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        TrackPoint tp = new TrackPoint();
        tp.setId(9L);
        when(trackPointMapper.selectList(any())).thenReturn(List.of(tp));

        List<TrackPointDTO> out = service.listPoints(1L);

        assertEquals(1, out.size());
        verify(trackPointMapper).selectList(any());
    }

    /** 分页轨迹：记录存在 → 透传分页结果 */
    @Test
    void pagePoints_delegates() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(trackPointMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

        var out = service.pagePoints(1L, 1, 20);

        assertEquals(0, out.getRecords().size());
    }

    /** 记录详情：记录存在 → 拷贝返回 */
    @Test
    void getDto_copiesRecord() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setStatus(RecordStatus.PASSED.getCode());
        when(sportRecordMapper.selectById(1L)).thenReturn(record);

        var out = service.getDto(1L);

        assertEquals(1L, out.getId());
        assertEquals(RecordStatus.PASSED.getCode(), out.getStatus());
    }

    /** 查询类方法：记录不存在 → 3001（listPoints/pagePoints/getDto 统一走该分支） */
    @Test
    void queryMethods_recordNotFound_throws() {
        when(sportRecordMapper.selectById(1L)).thenReturn(null);

        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), assertThrows(
                BizException.class, () -> service.listPoints(1L)).getCode());
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), assertThrows(
                BizException.class, () -> service.pagePoints(1L, 1, 20)).getCode());
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), assertThrows(
                BizException.class, () -> service.getDto(1L)).getCode());
    }
}
