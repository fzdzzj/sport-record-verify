package com.sportverify.record.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.RecordWithPointsDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.entity.TrackPointArchive;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.RecordEventProducer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 运动记录服务单元测试（核心提交链路：提交主路径 / requestId 幂等 /
 * 并发重复提交 / 未知类型拒绝 / 分片键填充 / 批量插入开关 / 提交后事件降级）。
 *
 * <p>纯 Mockito：Mapper / RecordEventProducer / VerifyApi / VerifyDegradeService 全部 mock；
 * 不依赖 MySQL / Redis / RocketMQ。由于 {code submit} 内部调用
 * TransactionSynchronizationManager.registerSynchronization，测试需先 initSynchronization
 * 并手动触发 afterCommit，以覆盖提交成功后的「异步发事件 → 成功不降级 / 失败回调 Feign 直调 / 熔断转人工」分支。</p>
 */
class SportRecordServiceTest {

    private SportRecordMapper sportRecordMapper;
    private TrackPointMapper trackPointMapper;
    private TrackPointArchiveMapper trackPointArchiveMapper;
    private RecordEventProducer recordEventProducer;
    private VerifyApi verifyApi;
    private VerifyDegradeService verifyDegradeService;
    private SportRecordService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        trackPointMapper = mock(TrackPointMapper.class);
        trackPointArchiveMapper = mock(TrackPointArchiveMapper.class);
        recordEventProducer = mock(RecordEventProducer.class);
        verifyApi = mock(VerifyApi.class);
        verifyDegradeService = mock(VerifyDegradeService.class);
        service = new SportRecordService(sportRecordMapper, trackPointMapper, trackPointArchiveMapper,
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

    /** MQ 异步发送成功：不调用 onFailure */
    private void stubPublishSuccess() {
        doAnswer(inv -> null).when(recordEventProducer)
                .publishSubmitted(anyLong(), anyLong(), any());
    }

    /** MQ 异步发送失败：立即执行 onFailure（单测不引入真 MQ） */
    private void stubPublishFailure() {
        doAnswer(inv -> {
            Runnable onFailure = inv.getArgument(2);
            onFailure.run();
            return null;
        }).when(recordEventProducer).publishSubmitted(anyLong(), anyLong(), any());
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

    /**
     * 规范「提交事务不落不可见中间态」判别式：首次提交 INSERT 必须直接落
     * status=VERIFYING / version=0，submit 路径对 updateStatus 的调用次数为 0
     * （SUBMITTED 只作为历史行/回调路径的合法状态）；响应仍为 VERIFYING，
     * afterCommit 仍发 SUBMITTED 事件。
     *
     * <p>updateStatus 打桩返回 1 行只为让旧实现（先 INSERT SUBMITTED 再 UPDATE）
     * 走完全程，使红落在 INSERT 参数断言上而非迁移 0 行异常；新实现下该桩不被触碰
     * （由下方 never() 判别式守卫）。</p>
     */
    @Test
    void submit_insertsVerifyingDirectly_andNeverCallsUpdateStatus() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        when(sportRecordMapper.updateStatus(100L, RecordStatus.SUBMITTED.getCode(),
                RecordStatus.VERIFYING.getCode(), 0)).thenReturn(1);
        stubPublishSuccess();
        stubInsertReturnsId();

        inTx(() -> {
            RecordSubmitResultDTO result = service.submit(dto(2, 1));
            assertEquals(RecordStatus.VERIFYING.getCode(), result.getStatus(), "提交响应须为 VERIFYING");
            assertFalse(result.isDuplicated());
        }, true);

        ArgumentCaptor<SportRecord> insertCaptor = ArgumentCaptor.forClass(SportRecord.class);
        verify(sportRecordMapper).insert(insertCaptor.capture());
        SportRecord inserted = insertCaptor.getValue();
        assertEquals(RecordStatus.VERIFYING.getCode(), inserted.getStatus(),
                "首次提交 INSERT 必须直接落 VERIFYING，不得先落 SUBMITTED 再 UPDATE");
        assertEquals(0, inserted.getVersion());
        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
        verify(recordEventProducer).publishSubmitted(eq(100L), eq(100L), any());
    }

    /**
     * 显式 false → 逐条 INSERT：落主表（直接 VERIFYING）→ 逐条写轨迹 → 发事件。
     * TASK-139 后 submit 路径不再有 SUBMITTED→VERIFYING 的 updateStatus。
     */
    @Test
    void submit_happyPath_batchDisabled_writesPointsAndInsertsVerifying() {
        ReflectionTestUtils.setField(service, "batchInsertEnabled", false);
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        stubPublishSuccess();
        stubInsertReturnsId();

        inTx(() -> {
            RecordSubmitResultDTO result = service.submit(dto(2, null)); // sportType=null → 缺省 RUNNING
            assertEquals(100L, result.getRecordId());
            assertEquals("req-1", result.getRequestId());
            assertEquals(RecordStatus.VERIFYING.getCode(), result.getStatus(), "提交响应须为 VERIFYING 中间态");
            assertFalse(result.isDuplicated());
        }, true);

        // 分片前提：轨迹点必须携带 user_id 分片键（从记录归属人填充）
        ArgumentCaptor<SportRecord> insertCaptor = ArgumentCaptor.forClass(SportRecord.class);
        verify(sportRecordMapper).insert(insertCaptor.capture());
        assertEquals(RecordStatus.VERIFYING.getCode(), insertCaptor.getValue().getStatus());
        verify(trackPointMapper, times(2)).insert(any(TrackPoint.class));
        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
        verify(recordEventProducer).publishSubmitted(eq(100L), eq(100L), any());
    }

    /** 默认/true → 走多值 insertBatch，不再逐条 insert */
    @Test
    void submit_empty_sportTypeDefaultsToRunning_andBatchEnabled_usesBatchInsert() {
        ReflectionTestUtils.setField(service, "batchInsertEnabled", true);
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        stubPublishSuccess();
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
        verify(recordEventProducer, never()).publishSubmitted(anyLong(), anyLong(), any());
        verify(verifyApi, never()).triggerVerify(anyLong());
    }

    /**
     * TASK-140 connWait 归因桥接接线：计时开启时，首个 SQL（selectByRequestId）执行点
     * 线程必须已武装（物理连接获取等待才会归到本请求），事务完结（afterCompletion，
     * 含回滚语义）后解除；计时关闭时全程不武装。
     */
    @Test
    void submit_txTimingOn_armsConnWaitBeforeFirstSql_disarmsAfterCompletion() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        stubInsertReturnsId();
        stubPublishSuccess();
        boolean[] armedDuringFirstSql = {false};
        doAnswer(inv -> {
            armedDuringFirstSql[0] = SubmitTxTiming.connWaitArmed();
            return null;
        }).when(sportRecordMapper).selectByRequestId("req-1");

        ReflectionTestUtils.setField(service, "txTimingEnabled", true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(dto(1, 1));
            assertTrue(armedDuringFirstSql[0], "首个 SQL 执行点线程必须已武装");
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
                s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            assertFalse(SubmitTxTiming.connWaitArmed(), "事务完结（含回滚）后必须解除武装");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            SubmitTxTiming.disarmConnWait();
        }

        // 计时关闭（产品默认）：同一请求全程不武装，零 ThreadLocal 写入
        ReflectionTestUtils.setField(service, "txTimingEnabled", false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            boolean[] armedOnSecond = {true};
            doAnswer(inv -> {
                armedOnSecond[0] = SubmitTxTiming.connWaitArmed();
                return null;
            }).when(sportRecordMapper).selectByRequestId("req-2");
            RecordSubmitDTO second = dto(1, 1);
            second.setRequestId("req-2");
            service.submit(second);
            assertFalse(armedOnSecond[0], "计时关闭不得武装任何线程");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
        stubPublishFailure();
        stubInsertReturnsId();

        inTx(() -> service.submit(dto(0, 1)), true);

        verify(verifyApi).triggerVerify(100L);
        verify(verifyDegradeService, never()).degradeToManualReview(anyLong(), any());
    }

    /** MQ 失败 + Feign 直调也失败（verify 熔断）→ 熔断降级转人工，主链路不挂 */
    @Test
    void submit_mqAndFeignFail_degradeToManualReview() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        stubPublishFailure();
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

        AppealDTO out = service.appeal(5L, 200L, "理由");

        assertEquals(appeal, out);
        // userId 以记录归属人为准（200L），而非入参 200L（归属对齐，TASK-204）
        verify(verifyApi).createAppeal(eq(new com.sportverify.api.verify.dto.AppealCreateDTO(5L, 200L, "理由")));
    }

    /** 红 R4a：appeal caller 非空且非记录归属人（record.userId=200L）→ FORBIDDEN，不建单不迁移 */
    @Test
    void appeal_callerMismatch_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 100L, "理由"));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(verifyApi, never()).createAppeal(any());
        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    /** 绿 R4b：appeal caller == 记录归属人 → 成功（createAppeal 以 200L 为权威） */
    @Test
    void appeal_callerIsOwner_passes() {
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

        AppealDTO out = service.appeal(5L, 200L, "理由");

        assertEquals(appeal, out);
        verify(verifyApi).createAppeal(eq(new com.sportverify.api.verify.dto.AppealCreateDTO(5L, 200L, "理由")));
    }

    /** 绿 R4c：appeal caller 为 null（鉴权关闭降级）→ 放行 */
    @Test
    void appeal_callerNull_degradeAllowed() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        record.setVersion(0);
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        AppealDTO appeal = new AppealDTO();
        when(verifyApi.createAppeal(any())).thenReturn(com.sportverify.common.result.Result.success(appeal));
        when(sportRecordMapper.updateStatus(5L, RecordStatus.REJECTED.getCode(),
                RecordStatus.APPEALING.getCode(), record.getVersion())).thenReturn(1);

        AppealDTO out = service.appeal(5L, null, "理由");

        assertEquals(appeal, out);
    }

    /** 提交申诉：非 REJECTED 记录 → 3003 */
    @Test
    void appeal_notRejected_throws() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setStatus(RecordStatus.PASSED.getCode());
        when(sportRecordMapper.selectById(5L)).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, null, "理由"));
        assertEquals(ResultCode.RECORD_STATUS_INVALID.getCode(), e.getCode());
    }

    /** 提交申诉：记录不存在 → 3001 */
    @Test
    void appeal_recordNotFound_throws() {
        when(sportRecordMapper.selectById(5L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, null, "理由"));
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

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 200L, "理由"));
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

        AppealDTO out = service.appeal(5L, 200L, "理由");

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

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 200L, "理由"));
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

    /** 聚合拉取：记录存在 → 单次查主表 + 单次携带分片键查轨迹，字段与顺序正确 */
    @Test
    @SuppressWarnings("unchecked")
    void getRecordWithPoints_routesByUserIdAndAssemblesDto() {
        // MP 单测惯用初始化：注册 TrackPoint 列缓存，使 wrapper 的 lambda 列名可渲染
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPoint.class);

        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setStatus(RecordStatus.PASSED.getCode());
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        TrackPoint first = new TrackPoint();
        first.setSeq(1);
        TrackPoint second = new TrackPoint();
        second.setSeq(2);
        when(trackPointMapper.selectList(any())).thenReturn(List.of(first, second));

        RecordWithPointsDTO out = service.getRecordWithPoints(1L);

        assertEquals(1L, out.getRecord().getId());
        assertEquals(100L, out.getRecord().getUserId());
        assertEquals(RecordStatus.PASSED.getCode(), out.getRecord().getStatus());
        assertEquals(2, out.getPoints().size());
        assertEquals(1, out.getPoints().get(0).getSeq());
        assertEquals(2, out.getPoints().get(1).getSeq());
        verify(sportRecordMapper, times(1)).selectById(1L);
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(trackPointMapper, times(1)).selectList(wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("user_id ="), "轨迹查询必须携带分片键 user_id：" + segment);
        assertTrue(segment.contains("seq ASC"), "轨迹必须按 seq 升序：" + segment);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(100L),
                "分片键取值必须为记录归属人 user_id=100");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1L));
    }

    /** 聚合拉取：记录不存在 → 3001 且不触轨迹表 */
    @Test
    void getRecordWithPoints_recordNotFound_throws() {
        when(sportRecordMapper.selectById(1L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.getRecordWithPoints(1L));

        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        verify(trackPointMapper, never()).selectList(any());
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

        var out = service.pagePoints(1L, 100L, 1, 20);

        assertEquals(0, out.getRecords().size());
    }

    // ==================== 归属校验（TASK-204） ====================

    /** 红 R1a：caller 非空且非记录归属人 → 403（FORBIDDEN），拒绝读取他人轨迹 */
    @Test
    void pagePoints_callerMismatch_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);

        BizException e = assertThrows(BizException.class,
                () -> service.pagePoints(1L, 999L, 1, 20));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(trackPointMapper, never()).selectPage(any(Page.class), any());
    }

    /** 绿 R1b：caller == 记录归属人 → 正常分页 */
    @Test
    void pagePoints_callerIsOwner_passes() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(trackPointMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

        var out = service.pagePoints(1L, 100L, 1, 20);

        assertEquals(0, out.getRecords().size());
    }

    /** 绿 R1c：caller 为 null（鉴权关闭降级）→ 放行 */
    @Test
    void pagePoints_callerNull_degradeAllowed() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(trackPointMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

        var out = service.pagePoints(1L, null, 1, 20);

        assertEquals(0, out.getRecords().size());
    }

    // ==================== 我的记录（TASK-211） ====================

    /** R1a：mapper 过滤即身份——selectPage wrapper 必须携带 user_id=200 过滤（只查本人行） */
    @Test
    @SuppressWarnings("unchecked")
    void pageMyRecords_filtersByUserId() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        Page<SportRecord> mpPage = new Page<>(1, 20, 0);
        when(sportRecordMapper.selectPage(any(Page.class), any())).thenReturn(mpPage);

        Page<RecordSummaryDTO> out = service.pageMyRecords(200L, 1, 20);

        assertEquals(0, out.getRecords().size());
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("user_id ="),
                "归属即身份：分页查询必须带 user_id 过滤");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(200L),
                "过滤值必须是解析出的归属人 user_id=200");
    }

    /** R1b：分页边界——page 下限 1、size 上限 500、默认由控制器 20 承担（此处夹取测试） */
    @Test
    @SuppressWarnings("unchecked")
    void pageMyRecords_clampsPageAndSize() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenAnswer(inv -> {
                    Page<SportRecord> p = inv.getArgument(0);
                    return new Page<>(p.getCurrent(), p.getSize(), 0);
                });

        service.pageMyRecords(200L, 0, 501);
        service.pageMyRecords(200L, -5, 1);
        service.pageMyRecords(200L, 2, 500);

        ArgumentCaptor<Page> pageArg = ArgumentCaptor.forClass(Page.class);
        verify(sportRecordMapper, times(3)).selectPage(pageArg.capture(), any());
        java.util.List<Page> pages = pageArg.getAllValues();
        assertEquals(1L, pages.get(0).getCurrent(), "page 下限 1");
        assertEquals(500L, pages.get(0).getSize(), "size cap 500");
        assertEquals(1L, pages.get(1).getCurrent(), "负 page 夹到 1");
        assertEquals(1L, pages.get(1).getSize(), "size 下限 1");
        assertEquals(2L, pages.get(2).getCurrent());
        assertEquals(500L, pages.get(2).getSize());
    }

    /** R1b：形如 points 端点的 Page 分页形态——DTO 字段映射完整（id/sportType/distance/status/createdAt） */
    @Test
    @SuppressWarnings("unchecked")
    void pageMyRecords_mapsToSummaryDto() {
        SportRecord rec = new SportRecord();
        rec.setId(9L);
        rec.setUserId(200L);
        rec.setSportType(1);
        rec.setDistance(new BigDecimal("12.5"));
        rec.setStatus(RecordStatus.PASSED.getCode());
        rec.setCreatedAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        Page<SportRecord> mpPage = new Page<>(1, 20, 1);
        mpPage.setRecords(java.util.List.of(rec));
        when(sportRecordMapper.selectPage(any(Page.class), any())).thenReturn(mpPage);

        Page<RecordSummaryDTO> out = service.pageMyRecords(200L, 1, 20);

        assertEquals(1, out.getTotal());
        assertEquals(1, out.getRecords().size());
        RecordSummaryDTO dto = out.getRecords().get(0);
        assertEquals(9L, dto.getId());
        assertEquals(1, dto.getSportType());
        assertEquals(new BigDecimal("12.5"), dto.getDistance());
        assertEquals(RecordStatus.PASSED.getCode(), dto.getStatus());
        assertEquals(LocalDateTime.of(2026, 10, 1, 10, 0), dto.getCreatedAt());
    }

    /** R1d：空结果 → 空 Page（非错误，total=0、records 空） */
    @Test
    @SuppressWarnings("unchecked")
    void pageMyRecords_emptyReturnsEmptyPage() {
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenReturn(new Page<SportRecord>(1, 20, 0));

        Page<RecordSummaryDTO> out = service.pageMyRecords(200L, 1, 20);

        assertEquals(0L, out.getTotal());
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
                BizException.class, () -> service.pagePoints(1L, null, 1, 20)).getCode());
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), assertThrows(
                BizException.class, () -> service.getDto(1L)).getCode());
    }

    // ==================== 冷热路由与申诉防线（TASK-175） ====================

    /** 冷热路由判别式：archived=1 → listPoints 读归档表，热表不被访问 */
    @Test
    void listPoints_archivedRecord_readsArchiveTable() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setArchived(1);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        TrackPointArchive arch = new TrackPointArchive();
        arch.setId(9L);
        arch.setRecordId(1L);
        arch.setUserId(100L);
        arch.setSeq(3);
        when(trackPointArchiveMapper.selectList(any())).thenReturn(List.of(arch));

        List<TrackPointDTO> out = service.listPoints(1L);

        assertEquals(1, out.size());
        assertEquals(3, out.get(0).getSeq());
        verify(trackPointArchiveMapper).selectList(any());
        verify(trackPointMapper, never()).selectList(any());
    }

    /** 冷热路由判别式：archived=1 → pagePoints 走归档分页（selectPage），热表不被访问 */
    @Test
    @SuppressWarnings("unchecked")
    void pagePoints_archivedRecord_readsArchiveTable() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setArchived(1);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        TrackPointArchive arch = new TrackPointArchive();
        arch.setId(9L);
        arch.setRecordId(1L);
        arch.setUserId(100L);
        arch.setSeq(2);
        Page<TrackPointArchive> archPage = new Page<>(1, 20, 1);
        archPage.setRecords(List.of(arch));
        when(trackPointArchiveMapper.selectPage(any(Page.class), any())).thenReturn(archPage);

        var out = service.pagePoints(1L, 100L, 1, 20);

        assertEquals(1, out.getTotal());
        assertEquals(1, out.getRecords().size());
        assertEquals(2, out.getRecords().get(0).getSeq());
        verify(trackPointArchiveMapper).selectPage(any(Page.class), any());
        verify(trackPointMapper, never()).selectPage(any(Page.class), any());
    }

    /** 判定聚合契约恒热判别式：即便 archived=1，getRecordWithPoints 也恒查热表（归档 mapper 零交互） */
    @Test
    void getRecordWithPoints_alwaysReadsHotTable() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setStatus(RecordStatus.PASSED.getCode());
        record.setArchived(1);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        TrackPoint tp = new TrackPoint();
        tp.setSeq(1);
        when(trackPointMapper.selectList(any())).thenReturn(List.of(tp));

        RecordWithPointsDTO out = service.getRecordWithPoints(1L);

        assertEquals(1, out.getPoints().size());
        verify(trackPointMapper).selectList(any());
        verifyNoInteractions(trackPointArchiveMapper);
    }

    /** 申诉防线判别式：可申诉终态（REJECTED）但 archived=1 → 3003，状态机不迁移、不建申诉单 */
    @Test
    void submitAppeal_archivedRecord_rejected() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        record.setStatus(RecordStatus.REJECTED.getCode());
        record.setArchived(1);
        when(sportRecordMapper.selectById(5L)).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> service.appeal(5L, 200L, "理由"));
        assertEquals(ResultCode.RECORD_STATUS_INVALID.getCode(), e.getCode());
        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
        verify(verifyApi, never()).createAppeal(any());
    }

    // ==================== 连续打卡（TASK-220） ====================

    /**
     * R2 判别式：myStreak 以单列投影 selectList 查询——
     * (a) 归属过滤 user_id=200；(b) status 过滤恰为通过终态集 {PASSED(2), RE_PASSED(5)}
     * （captor 参数值集精确断言，非通过终态值不得混入）；(c) createdAt 映射自然日且同日去重；
     * (d) 输出由真实 StreakCalculator 对固定日期（相对服务器时钟 today）计算：今日/昨日/前日
     * 三条（含同日第二条）→ current=3、longest=3。
     */
    @Test
    @SuppressWarnings("unchecked")
    void myStreak_filtersPassedStatuses_mapsDates_computesStreak() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        LocalDate today = LocalDate.now();
        SportRecord today1 = new SportRecord();
        today1.setUserId(200L);
        today1.setStatus(RecordStatus.PASSED.getCode());
        today1.setCreatedAt(today.atTime(9, 0));
        SportRecord today2 = new SportRecord(); // 同日第二条：去重后只算一天
        today2.setUserId(200L);
        today2.setStatus(RecordStatus.PASSED.getCode());
        today2.setCreatedAt(today.atTime(22, 0));
        SportRecord yesterday = new SportRecord();
        yesterday.setUserId(200L);
        yesterday.setStatus(RecordStatus.RE_PASSED.getCode());
        yesterday.setCreatedAt(today.minusDays(1).atTime(8, 0));
        SportRecord before = new SportRecord(); // today-2：与今昨构成连续 3 天
        before.setUserId(200L);
        before.setStatus(RecordStatus.PASSED.getCode());
        before.setCreatedAt(today.minusDays(2).atTime(8, 0));
        SportRecord rejected = new SportRecord(); // 非通过终态：必须被 status 过滤剔除
        rejected.setUserId(200L);
        rejected.setStatus(RecordStatus.REJECTED.getCode());
        rejected.setCreatedAt(today.atTime(7, 0));
        when(sportRecordMapper.selectList(any())).thenReturn(List.of(today1, today2, yesterday, before, rejected));

        MyStreakDTO out = service.myStreak(200L);

        assertEquals(3, out.getCurrent(), "今日/昨日/前日连续 → current=3（同日去重不得计 4）");
        assertEquals(3, out.getLongest(), "历史最长连续段 = 3");
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectList(wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("user_id ="), "归属即身份：查询必须带 user_id 过滤：" + segment);
        assertTrue(segment.contains("IN"), "必须按状态集合过滤（in）：" + segment);
        java.util.Set<Object> paramValues = new java.util.HashSet<>(
                wrapper.getValue().getParamNameValuePairs().values());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(200L,
                        RecordStatus.PASSED.getCode(), RecordStatus.RE_PASSED.getCode())),
                paramValues, "status 过滤参数值必须恰为 {PASSED, RE_PASSED}，不得混入其他状态");
    }
}
