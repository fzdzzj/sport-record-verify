package com.sportverify.record.service;

import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.RecordWithPointsDTO;
import com.sportverify.api.record.dto.FeedItemDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.dto.RecordAggDTO;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.entity.TrackPointArchive;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.RecordEventProducer;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.service.RecordLikeService;
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
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    private com.sportverify.api.user.UserApi userApi;
    private RecordLikeMapper recordLikeMapper;
    private RecordLikeService recordLikeService;
    private LeaderboardApi leaderboardApi;
    private SportRecordService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        trackPointMapper = mock(TrackPointMapper.class);
        trackPointArchiveMapper = mock(TrackPointArchiveMapper.class);
        recordEventProducer = mock(RecordEventProducer.class);
        verifyApi = mock(VerifyApi.class);
        verifyDegradeService = mock(VerifyDegradeService.class);
        userApi = mock(com.sportverify.api.user.UserApi.class);
        recordLikeMapper = mock(RecordLikeMapper.class);
        recordLikeService = mock(RecordLikeService.class);
        leaderboardApi = mock(LeaderboardApi.class);
        service = new SportRecordService(sportRecordMapper, trackPointMapper, trackPointArchiveMapper,
                recordEventProducer, verifyApi, verifyDegradeService, userApi);
        service.setRecordLikeMapper(recordLikeMapper);
        service.setRecordLikeService(recordLikeService);
        service.setLeaderboardApi(leaderboardApi);
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

    // ==================== 提交可见性（TASK-221，R4） ====================

    /** null visibility → 落库 PUBLIC(0)：新记录/旧客户端缺省默认公开（向后兼容） */
    @Test
    @SuppressWarnings("unchecked")
    void submit_visibilityNull_persistsPublic() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        stubInsertReturnsId();
        stubPublishSuccess();

        inTx(() -> assertEquals(RecordStatus.VERIFYING.getCode(), service.submit(dto(1, 1)).getStatus()), true);

        ArgumentCaptor<SportRecord> captor = ArgumentCaptor.forClass(SportRecord.class);
        verify(sportRecordMapper).insert(captor.capture());
        assertEquals(Integer.valueOf(0), captor.getValue().getVisibility(), "null visibility 必须落库 PUBLIC(0)");
    }

    /** 显式 1（FRIENDS）/ 2（PRIVATE）→ 原样落库（独立 mapper 逐值断言） */
    @Test
    void submit_visibilityExplicit_persistsExactly() {
        for (int vis : new int[]{1, 2}) {
            SportRecordMapper localMapper = mock(SportRecordMapper.class);
            when(localMapper.selectByRequestId("req-1")).thenReturn(null);
            doAnswer(inv -> {
                SportRecord r = inv.getArgument(0);
                r.setId(100L);
                return 1;
            }).when(localMapper).insert(any(SportRecord.class));
            SportRecordService localService = new SportRecordService(localMapper,
                    trackPointMapper, trackPointArchiveMapper, recordEventProducer,
                    verifyApi, verifyDegradeService, userApi);
            ReflectionTestUtils.setField(localService, "batchInsertEnabled", true);

            RecordSubmitDTO req = dto(1, 1);
            req.setVisibility(vis);
            inTx(() -> localService.submit(req), true);

            ArgumentCaptor<SportRecord> captor = ArgumentCaptor.forClass(SportRecord.class);
            verify(localMapper).insert(captor.capture());
            assertEquals(Integer.valueOf(vis), captor.getValue().getVisibility(),
                    "显式 visibility=" + vis + " 必须原样落库");
        }
    }

    /** 枚举外可见性 → 3007 拒绝，不落库（与未知运动类型同款 resolveSportType 先例） */
    @Test
    void submit_visibilityOutOfRange_rejected() {
        when(sportRecordMapper.selectByRequestId("req-1")).thenReturn(null);
        RecordSubmitDTO req = dto(1, 1);
        req.setVisibility(99);

        BizException e = assertThrows(BizException.class, () -> service.submit(req));
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

    /** 查询判定结果：记录存在 → 经可见性门（owner 放行）透传 verify-api 结果 */
    @Test
    void getVerifyResult_delegatesToVerifyApi() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(0); // PUBLIC
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        VerificationResultDTO v = new VerificationResultDTO();
        when(verifyApi.getVerificationResult(1L)).thenReturn(com.sportverify.common.result.Result.success(v));

        VerificationResultDTO out = service.getVerifyResult(1L, 100L);

        assertEquals(v, out);
        verify(verifyApi).getVerificationResult(1L);
    }

    /** 查询判定结果：记录不存在 → 3001 */
    @Test
    void getVerifyResult_recordNotFound_throws() {
        when(sportRecordMapper.selectById(1L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.getVerifyResult(1L, null));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    // ==================== 读取面可见性门（TASK-221，R2/R3） ====================

    /** 红：PRIVATE 记录非归属人读取判定 → 403（FORBIDDEN），不透传 verify-api */
    @Test
    void getVerifyResult_privateRecord_stranger_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(2); // PRIVATE
        when(sportRecordMapper.selectById(1L)).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> service.getVerifyResult(1L, 999L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verifyNoInteractions(userApi);
        verify(verifyApi, never()).getVerificationResult(anyLong());
    }

    /** 绿：PRIVATE 记录归属人读取判定 → 200，透传 verify-api 结果 */
    @Test
    void getVerifyResult_privateRecord_owner_passes() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(2); // PRIVATE
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        VerificationResultDTO v = new VerificationResultDTO();
        when(verifyApi.getVerificationResult(1L)).thenReturn(com.sportverify.common.result.Result.success(v));

        VerificationResultDTO out = service.getVerifyResult(1L, 100L);

        assertEquals(v, out);
        verify(verifyApi).getVerificationResult(1L);
    }

    /** 绿：caller=null（鉴权关闭降级态）读取判定 → 放行，行为与今日持平（R3 降级态） */
    @Test
    void getVerifyResult_callerNull_degradeAllowed() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(2); // PRIVATE 也不拦降级态
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        VerificationResultDTO v = new VerificationResultDTO();
        when(verifyApi.getVerificationResult(1L)).thenReturn(com.sportverify.common.result.Result.success(v));

        VerificationResultDTO out = service.getVerifyResult(1L, null);

        assertEquals(v, out);
        verify(verifyApi).getVerificationResult(1L);
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

    // ==================== 归属校验（TASK-204，语义被 TASK-221 可见性门细化） ====================

    /** 红 R1a：PRIVATE 记录 caller 非归属人 → 403（FORBIDDEN），拒绝读取他人轨迹 */
    @Test
    void pagePoints_callerMismatch_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(2); // PRIVATE（非归属人才 403；PUBLIC/FRIENDS 见下方门测试）
        when(sportRecordMapper.selectById(1L)).thenReturn(record);

        BizException e = assertThrows(BizException.class,
                () -> service.pagePoints(1L, 999L, 1, 20));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(trackPointMapper, never()).selectPage(any(Page.class), any());
    }

    // ==================== 读取面可见性门（TASK-221，R2 门矩阵核心） ====================

    /** 绿：owner × PRIVATE → 放行（归属人读自己任何人可见性） */
    @Test
    void pagePoints_owner_privateRecord_passes() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(2);
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(trackPointMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

        var out = service.pagePoints(1L, 100L, 1, 20);

        assertEquals(0, out.getRecords().size());
        verifyNoInteractions(userApi);
    }

    /** 绿：stranger × PUBLIC → 放行（premigration null 亦同，向后兼容）；不触 userService */
    @Test
    void pagePoints_stranger_publicRecord_passes() {
        for (Integer vis : new Integer[]{0, null}) {
            SportRecord record = new SportRecord();
            record.setId(1L);
            record.setUserId(100L);
            record.setVisibility(vis);
            when(sportRecordMapper.selectById(1L)).thenReturn(record);
            when(trackPointMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                    any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

            var out = service.pagePoints(1L, 999L, 1, 20);

            assertEquals(0, out.getRecords().size());
        }
        verifyNoInteractions(userApi);
    }

    /** 绿：friend × FRIENDS → 放行（userApi.areFriends(owner, caller)=true） */
    @Test
    void pagePoints_friend_friendsRecord_passes() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(1); // FRIENDS
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(userApi.areFriends(100L, 999L))
                .thenReturn(com.sportverify.common.result.Result.success(true));
        when(trackPointMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

        var out = service.pagePoints(1L, 999L, 1, 20);

        assertEquals(0, out.getRecords().size());
        verify(userApi).areFriends(100L, 999L);
    }

    /** 红：stranger × FRIENDS → 403（userApi.areFriends=false） */
    @Test
    void pagePoints_stranger_friendsRecord_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(1); // FRIENDS
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(userApi.areFriends(100L, 999L))
                .thenReturn(com.sportverify.common.result.Result.success(false));

        BizException e = assertThrows(BizException.class,
                () -> service.pagePoints(1L, 999L, 1, 20));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(trackPointMapper, never()).selectPage(any(Page.class), any());
        verify(userApi).areFriends(100L, 999L);
    }

    /** 红：userApi 抛异常 → FRIENDS 按非好友 403（fail-safe 隐私） */
    @Test
    void pagePoints_friendsRecord_userApiThrows_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(1); // FRIENDS
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(userApi.areFriends(100L, 999L))
                .thenThrow(new RuntimeException("user-service 熔断"));

        BizException e = assertThrows(BizException.class,
                () -> service.pagePoints(1L, 999L, 1, 20));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(trackPointMapper, never()).selectPage(any(Page.class), any());
    }

    /** 红：userApi 软降级返回非成功 Result（data=null）→ FRIENDS 按非好友 403（fail-safe 隐私） */
    @Test
    void pagePoints_friendsRecord_userApiDegrade_throwsForbidden() {
        SportRecord record = new SportRecord();
        record.setId(1L);
        record.setUserId(100L);
        record.setVisibility(1); // FRIENDS
        when(sportRecordMapper.selectById(1L)).thenReturn(record);
        when(userApi.areFriends(100L, 999L))
                .thenReturn(new com.sportverify.common.result.Result<>(4006, "不可用", null));

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

    /** TASK-224 R2：全量过滤参数（sportType/status/startDate/endDate）组合生效，wrapper 条件与参数对齐 */
    @Test
    @SuppressWarnings("unchecked")
    void pageMyRecords_withAllFilters_buildsCorrectWrapper() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        Page<SportRecord> mpPage = new Page<>(1, 20, 0);
        when(sportRecordMapper.selectPage(any(Page.class), any())).thenReturn(mpPage);

        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 5);
        Page<RecordSummaryDTO> out = service.pageMyRecords(200L, 1, 20, 1, 2, start, end);

        assertEquals(0, out.getRecords().size());
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id ="), "必须含 user_id 过滤");
        assertTrue(sql.contains("sport_type ="), "必须含 sport_type 过滤");
        assertTrue(sql.contains("status ="), "必须含 status 过滤");
        assertTrue(sql.contains("start_time >="), "必须含 start_time >= 过滤");
        assertTrue(sql.contains("start_time <="), "必须含 start_time <= 过滤");
        assertTrue(sql.contains("ORDER BY created_at DESC"), "必须保持 createdAt 倒序");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(200L));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(2));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(start.atStartOfDay()));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(end.atTime(LocalTime.MAX)));
    }

    /** TASK-224 R2：单维度过滤启用时，其余条件为 null 跳过（空条件不污染 wrapper） */
    @Test
    @SuppressWarnings("unchecked")
    void pageMyRecords_individualFilters_buildsCorrectWrapper() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        Page<SportRecord> mpPage = new Page<>(1, 20, 0);
        when(sportRecordMapper.selectPage(any(Page.class), any())).thenReturn(mpPage);

        // 仅 sportType
        service.pageMyRecords(200L, 1, 20, 2, null, null, null);
        ArgumentCaptor<LambdaQueryWrapper> captor1 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), captor1.capture());
        assertTrue(captor1.getValue().getSqlSegment().contains("sport_type ="));
        assertFalse(captor1.getValue().getSqlSegment().contains("status ="));
        assertFalse(captor1.getValue().getSqlSegment().contains("start_time"));

        // 仅 status
        service.pageMyRecords(200L, 1, 20, null, 3, null, null);
        ArgumentCaptor<LambdaQueryWrapper> captor2 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper, times(2)).selectPage(any(Page.class), captor2.capture());
        assertTrue(captor2.getValue().getSqlSegment().contains("status ="));
        assertFalse(captor2.getValue().getSqlSegment().contains("sport_type ="));

        // 仅 startDate
        LocalDate start = LocalDate.of(2026, 10, 1);
        service.pageMyRecords(200L, 1, 20, null, null, start, null);
        ArgumentCaptor<LambdaQueryWrapper> captor3 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper, times(3)).selectPage(any(Page.class), captor3.capture());
        assertTrue(captor3.getValue().getSqlSegment().contains("start_time >="));
        assertFalse(captor3.getValue().getSqlSegment().contains("start_time <="));

        // 仅 endDate
        LocalDate end = LocalDate.of(2026, 10, 5);
        service.pageMyRecords(200L, 1, 20, null, null, null, end);
        ArgumentCaptor<LambdaQueryWrapper> captor4 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper, times(4)).selectPage(any(Page.class), captor4.capture());
        assertTrue(captor4.getValue().getSqlSegment().contains("start_time <="));
        assertFalse(captor4.getValue().getSqlSegment().contains("start_time >="));
    }

    /** TASK-224 R3：服务层非法 sportType → 抛 400（IllegalArgumentException） */
    @Test
    void pageMyRecords_illegalSportType_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.pageMyRecords(200L, 1, 20, 99, null, null, null));
        assertEquals("非法运动类型：99", e.getMessage());
    }

    /** TASK-224 R3：服务层非法 status → 抛 400（IllegalArgumentException） */
    @Test
    void pageMyRecords_illegalStatus_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.pageMyRecords(200L, 1, 20, null, 99, null, null));
        assertEquals("非法记录状态：99", e.getMessage());
    }

    /** TASK-224 R3：服务层 startDate > endDate → 抛 400（IllegalArgumentException） */
    @Test
    void pageMyRecords_startDateAfterEndDate_throwsIllegalArgument() {
        LocalDate start = LocalDate.of(2026, 10, 10);
        LocalDate end = LocalDate.of(2026, 10, 1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.pageMyRecords(200L, 1, 20, null, null, start, end));
        assertEquals("开始日期不能晚于结束日期", e.getMessage());
    }

    // ==================== 好友动态 feed（TASK-222 add-friend-feed） ====================

    /**
     * 红 R1：auth on feed 过滤即身份——候选归属人 = self ∪ friendIds（user_id IN），
     * 且可见性约束 user_id=self OR visibility ∈ {0,1}（本人任意、好友 PUBLIC/FRIENDS）。
     * 陌生人记录即使 PUBLIC 也不入 feed：feed 查询携带的候选作者集合不含陌生人。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_authOn_filtersSelfAndFriendAuthors() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        when(userApi.listFriendIds(200L))
                .thenReturn(Result.success(java.util.List.of(201L, 202L, 500L)));
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenReturn(new Page<SportRecord>(1, 20, 0));

        Page<FeedItemDTO> out = service.pageFeed(200L, 1, 20);

        assertEquals(0, out.getRecords().size());
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("user_id IN"), "feed 必须按候选作者集 user_id IN 过滤：" + segment);
        // 候选集 = self(200) ∪ 好友(201,202,500)；陌生人(如 999)不在其内
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(200L),
                "候选作者集必须含本人 user_id=200");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(201L),
                "候选作者集必须含好友 201");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(500L),
                "候选作者集必须含好友 500");
        verify(userApi).listFriendIds(200L);
    }

    /**
     * 红 R6：auth on 可见性约束——好友记录仅 PUBLIC/FRIENDS；feed wrapper 携带
     * 可见性 IN (0,1) 与「本人任意」的 or 分支，使 PRIVATE 好友记录 / 陌生人不入 feed。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_authOn_visibilityConstraint() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(java.util.List.of(201L)));
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenReturn(new Page<SportRecord>(1, 20, 0));

        service.pageFeed(200L, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("visibility IN"), "可见性约束必须含 visibility IN (0,1)：" + segment);
        // 本人任意可见性：user_id=200 与 visibility 约束按 or 组织（segments 含 OR）
        assertTrue(segment.toUpperCase().contains("OR"), "本人任意可见性与好友可见性约束须 OR：" + segment);
    }

    /**
     * 红 R2/R4：auth on feed 组装——creator DESC 返回时，FeedItemDTO 载 Summary 全字段 +
     * authorUserId；昵称仅当页 distinct author 经 listUsersByIds 批量补齐。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_authOn_assemblesDtoWithNickname() {
        SportRecord own = rec(1L, 200L, 1, 0, "12.5");
        SportRecord friend = rec(2L, 201L, 2, 1, "20.0");
        Page<SportRecord> mpPage = new Page<>(1, 20, 2);
        mpPage.setRecords(java.util.List.of(friend, own)); // createdAt DESC
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(java.util.List.of(201L)));
        when(sportRecordMapper.selectPage(any(Page.class), any())).thenReturn(mpPage);
        com.sportverify.api.user.dto.UserDTO u = new com.sportverify.api.user.dto.UserDTO();
        u.setId(201L);
        u.setNickname("B");
        // 当页 distinct author 顺序为 [201,200]（friend 先）；用 any 匹配批量拉取
        when(userApi.listUsersByIds(any()))
                .thenReturn(Result.success(java.util.List.of(u)));

        Page<FeedItemDTO> out = service.pageFeed(200L, 1, 20);

        assertEquals(2, out.getRecords().size());
        FeedItemDTO first = out.getRecords().get(0); // friend 先（createdAt DESC）
        assertEquals(2L, first.getId());
        assertEquals(2, first.getSportType()); // friend sportType=2
        assertEquals(RecordStatus.PASSED.getCode(), first.getStatus());
        assertEquals(Integer.valueOf(1), first.getVisibility(), "friend 节点可见性=FRIENDS(1)");
        assertEquals(201L, first.getAuthorUserId());
        assertEquals("B", first.getAuthorNickname());
        assertEquals(200L, out.getRecords().get(1).getAuthorUserId());
        ArgumentCaptor<java.util.List<Long>> idsCap = ArgumentCaptor.forClass(java.util.List.class);
        verify(userApi).listUsersByIds(idsCap.capture());
        List<Long> asked = idsCap.getValue();
        assertTrue(asked.contains(200L) && asked.contains(201L),
                "昵称批量拉取必须只含当页 distinct author（本人+好友）:" + asked);
    }

    /** 红 R4 fail-safe：昵称拉取软降级空 → authorNickname=null（web 回退 userId 字符串），不报错 */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_nicknameFallback_null() {
        SportRecord own = rec(1L, 200L, 1, 0, "12.5");
        Page<SportRecord> mpPage = new Page<>(1, 20, 1);
        mpPage.setRecords(java.util.List.of(own));
        when(userApi.listFriendIds(200L)).thenReturn(Result.success(java.util.List.of()));
        when(sportRecordMapper.selectPage(any(Page.class), any())).thenReturn(mpPage);
        when(userApi.listUsersByIds(java.util.List.of(200L)))
                .thenReturn(Result.success(java.util.Collections.emptyList()));

        Page<FeedItemDTO> out = service.pageFeed(200L, 1, 20);

        assertNull(out.getRecords().get(0).getAuthorNickname());
        assertEquals(200L, out.getRecords().get(0).getAuthorUserId());
    }

    /**
     * 红 R3 fail-safe：user-service 不可用（listFriendIds 软降级空）→ feed 退化为仅本人。
     * 候选作者集合只含 self，查询仍执行（不崩）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_userApiDown_degradesToSelfOnly() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        when(userApi.listFriendIds(200L)).thenThrow(new RuntimeException("熔断"));
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenReturn(new Page<SportRecord>(1, 20, 0));

        service.pageFeed(200L, 1, 20);

        // fail-safe：user-service 不可用仍执行 self 作用域 IN 查询（非全扫），不崩；
        // 候选作者集不含任何好友（friend 500 不出现），降级为仅本人。
        verify(userApi).listFriendIds(200L);
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("user_id IN"), "降级后仍须按候选作者集 user_id IN 过滤：" + segment);
        // 好友集空 → 候选只含本人：不出现好友 id 参数
        assertFalse(wrapper.getValue().getParamNameValuePairs().containsValue(500L),
                "好友不可用后候选集不得含好友 500");
        assertFalse(wrapper.getValue().getParamNameValuePairs().containsValue(201L),
                "好友不可用后候选集不得含好友 201");
    }

    /** 红 R2 degrade：caller=null → PUBLIC(0) 全站时间线（不区分归属），无好友/昵称调用 */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_degrade_callerNull_publicOnly() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenReturn(new Page<SportRecord>(1, 20, 0));

        service.pageFeed(null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).selectPage(any(Page.class), wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("visibility =") || segment.contains("visibility =0"),
                "degrade 必须按 visibility=PUBLIC 过滤全站：" + segment);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(0),
                "degrade 过滤值必须是 PUBLIC(0)");
        verifyNoInteractions(userApi);
    }

    /** R1 分页边界：page 下限 1、size 夹 [1,500]，与 /my 同款钳制 */
    @Test
    @SuppressWarnings("unchecked")
    void pageFeed_clampsPageAndSize() {
        when(sportRecordMapper.selectPage(any(Page.class), any()))
                .thenAnswer(inv -> {
                    Page<SportRecord> p = inv.getArgument(0);
                    return new Page<>(p.getCurrent(), p.getSize(), 0);
                });

        service.pageFeed(200L, 0, 501);
        service.pageFeed(200L, -5, 1);

        ArgumentCaptor<Page> pageArg = ArgumentCaptor.forClass(Page.class);
        verify(sportRecordMapper, times(2)).selectPage(pageArg.capture(), any());
        java.util.List<Page> pages = pageArg.getAllValues();
        assertEquals(1L, pages.get(0).getCurrent(), "page 下限 1");
        assertEquals(500L, pages.get(0).getSize(), "size cap 500");
        assertEquals(1L, pages.get(1).getCurrent(), "负 page 夹到 1");
        assertEquals(1L, pages.get(1).getSize(), "size 下限 1");
    }

    /** 构造 feed 测试记录（visibility 显式置入） */
    private SportRecord rec(Long id, Long userId, Integer sportType, Integer visibility, String dist) {
        SportRecord r = new SportRecord();
        r.setId(id);
        r.setUserId(userId);
        r.setSportType(sportType);
        r.setVisibility(visibility);
        r.setDistance(new BigDecimal(dist));
        r.setStatus(RecordStatus.PASSED.getCode());
        r.setCreatedAt(LocalDateTime.of(2026, 10, 1, 10, 0).plusSeconds(id));
        return r;
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

    // ==================== 个人核心统计 my/stats（TASK-231） ====================

    /** 统计聚合用行（归属人固定 200；distance/status/createdAt 为统计口径三要素） */
    private SportRecord statsRow(Integer status, String distance, LocalDateTime createdAt) {
        SportRecord row = new SportRecord();
        row.setUserId(200L);
        row.setStatus(status);
        row.setDistance(distance == null ? null : new BigDecimal(distance));
        row.setCreatedAt(createdAt);
        return row;
    }

    /** 窗口投影行（selectDailyDistanceSince 结果）：只带 createdAt / distance 两列 */
    private RecordAggDTO dailyRow(SportRecord row) {
        RecordAggDTO agg = new RecordAggDTO();
        agg.setCreatedAt(row.getCreatedAt());
        agg.setDistance(row.getDistance());
        return agg;
    }

    /**
     * R1/R2 判别式（TASK-232 SQL 聚合下推）——全量指标由单行聚合查询承担、日粒度由
     * created_at 时间窗投影承担，不再把该用户全部记录拉到 JVM：
     * (a) 聚合入参恰为 user_id=200 与通过终态码集 {PASSED(2), RE_PASSED(5)}（与 RecordStatus 枚举同源）；
     * (b) 窗口入参恰为 user_id=200 与 today-29 00:00（与 30 键窗口同源）；窗口查询必带 created_at
     *     下界（idx_user_time 复合索引范围扫描的判别式，且窗口外行不进日粒度）；
     * (c) myStats 不再调用 selectList（全量行拉取已消灭）；
     * (d) totalCount/totalDistance/passCount/passRate 为全量口径（含窗口外记录）；
     * (e) dailyDistances 恒 30 键且升序，窗口内按自然日汇总（同日多条累加）、无记录日补 0；
     * (f) 防御性：投影行 createdAt 为 null 时跳过，不抛 NPE。
     */
    @Test
    @SuppressWarnings("unchecked")
    void myStats_aggregatesAllRecordsAndDailyWindow() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        LocalDate today = LocalDate.now();
        SportRecord passedToday = statsRow(RecordStatus.PASSED.getCode(), "3.5", today.atTime(7, 0));
        SportRecord rePassedToday = statsRow(RecordStatus.RE_PASSED.getCode(), "1.5", today.atTime(20, 0));
        SportRecord rejectedToday = statsRow(RecordStatus.REJECTED.getCode(), "2.0", today.atTime(9, 0));
        SportRecord passedYesterday = statsRow(RecordStatus.PASSED.getCode(), "4.0", today.minusDays(1).atTime(8, 0));
        SportRecord windowEdge = statsRow(RecordStatus.PASSED.getCode(), "5.0", today.minusDays(29).atTime(8, 0));
        SportRecord nullDistance = statsRow(RecordStatus.VERIFYING.getCode(), null, today.atTime(10, 0));

        // 全量指标：DB 端单行聚合结果（覆盖全量 8 条，含窗口外 2 条；null 距离不计入 SUM）
        RecordAggDTO agg = new RecordAggDTO();
        agg.setTotalCount(8L);
        agg.setTotalDistance(new BigDecimal("22.00"));
        agg.setPassCount(5L);
        when(sportRecordMapper.selectAggByUserId(any(), any())).thenReturn(agg);

        // 近 30 天窗口投影（DB 端已按 created_at >= windowStart 排除窗口外行）
        RecordAggDTO nullCreatedAtRow = new RecordAggDTO(); // 防御性：无 createdAt 的行必须被跳过
        nullCreatedAtRow.setDistance(new BigDecimal("9.9"));
        when(sportRecordMapper.selectDailyDistanceSince(any(), any()))
                .thenReturn(List.of(dailyRow(passedToday), dailyRow(rePassedToday), dailyRow(rejectedToday),
                        dailyRow(passedYesterday), dailyRow(windowEdge), dailyRow(nullDistance), nullCreatedAtRow));

        MyStatsDTO out = service.myStats(200L);

        assertEquals(8L, out.getTotalCount(), "totalCount 取 DB 单行聚合（覆盖全量记录，含窗口外）");
        assertEquals(new BigDecimal("22.00"), out.getTotalDistance(),
                "totalDistance 取 DB 单行聚合：3.5+1.5+2+4+5+6+null(0)");
        assertEquals(5L, out.getPassCount(), "passCount 取 DB 端 CASE 聚合：仅 PASSED×4 + RE_PASSED×1");
        assertEquals("62.5%", out.getPassRate(), "5/8 = 62.5%（一位小数）");

        java.util.Map<String, BigDecimal> daily = out.getDailyDistances();
        assertEquals(30, daily.size(), "近 30 天窗口恒 30 键");
        assertEquals(today.minusDays(29).toString(), daily.keySet().iterator().next(), "键按日期升序");
        assertEquals(today.toString(), new java.util.ArrayList<>(daily.keySet()).get(29), "窗口右端为今日");
        assertEquals(new BigDecimal("7.00"), daily.get(today.toString()),
                "同日多条累加：3.5+1.5+2.0=7.00（日粒度覆盖全部状态，含 REJECTED）");
        assertEquals(new BigDecimal("4.00"), daily.get(today.minusDays(1).toString()));
        assertEquals(new BigDecimal("5.00"), daily.get(today.minusDays(29).toString()));
        assertEquals(new BigDecimal("0.00"), daily.get(today.minusDays(2).toString()), "无记录日补 0");
        assertNull(daily.get(today.minusDays(30).toString()), "窗口外记录不进日粒度（仍计入总量）");

        // (a) 单行聚合入参：归属人 + 通过终态码集（枚举同源，不得硬编码漂移）
        ArgumentCaptor<List<Integer>> statusCaptor = ArgumentCaptor.forClass(List.class);
        verify(sportRecordMapper).selectAggByUserId(eq(200L), statusCaptor.capture());
        assertEquals(List.of(RecordStatus.PASSED.getCode(), RecordStatus.RE_PASSED.getCode()),
                statusCaptor.getValue(), "通过终态码集必须与 RecordStatus 枚举同源（PASSED/RE_PASSED）");

        // (b) 窗口入参：归属人 + created_at 下界（今日-29 00:00）
        ArgumentCaptor<LocalDateTime> windowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sportRecordMapper).selectDailyDistanceSince(eq(200L), windowCaptor.capture());
        assertEquals(today.minusDays(29).atStartOfDay(), windowCaptor.getValue(),
                "日粒度查询必须带 created_at >= windowStart（idx_user_time 范围扫描）");

        // (c) 下推判别式：myStats 路径不再有全量行拉取
        verify(sportRecordMapper, never()).selectList(any());
    }

    /** R2 空态：无记录 → totalCount/passCount 为 0、总里程 0.00、passRate="0%"、日粒度仍 30 键全 0 */
    @Test
    void myStats_emptyReturnsZeroStatsWithFullDailyWindow() {
        RecordAggDTO emptyAgg = new RecordAggDTO();
        emptyAgg.setTotalCount(0L);
        emptyAgg.setTotalDistance(BigDecimal.ZERO);
        emptyAgg.setPassCount(0L);
        when(sportRecordMapper.selectAggByUserId(any(), any())).thenReturn(emptyAgg);
        when(sportRecordMapper.selectDailyDistanceSince(any(), any())).thenReturn(List.of());

        MyStatsDTO out = service.myStats(200L);

        assertEquals(0L, out.getTotalCount());
        assertEquals(new BigDecimal("0.00"), out.getTotalDistance());
        assertEquals(0L, out.getPassCount());
        assertEquals("0%", out.getPassRate(), "无记录时通过率为 0%（非 0.0%）");
        assertEquals(30, out.getDailyDistances().size());
        assertTrue(out.getDailyDistances().values().stream()
                        .allMatch(v -> v.compareTo(BigDecimal.ZERO) == 0),
                "无记录时窗口内每日里程全为 0.00");
    }

    /** R3 防御性：聚合行缺失（null）时回退零值，不抛 NPE，日粒度仍为完整 30 键 */
    @Test
    void myStats_nullAggregateRowFallsBackToZero() {
        when(sportRecordMapper.selectAggByUserId(any(), any())).thenReturn(null);
        when(sportRecordMapper.selectDailyDistanceSince(any(), any())).thenReturn(List.of());

        MyStatsDTO out = service.myStats(200L);

        assertEquals(0L, out.getTotalCount());
        assertEquals(new BigDecimal("0.00"), out.getTotalDistance());
        assertEquals(0L, out.getPassCount());
        assertEquals("0%", out.getPassRate());
        assertEquals(30, out.getDailyDistances().size());
    }

    // ==================== 记录删除级联与缓存清理（TASK-223） ====================

    /** R2/R3: 级联恰四表 + afterCommit 清理缓存 */
    @Test
    @SuppressWarnings("unchecked")
    void deleteRecord_cascadesAllFourTablesAndCleansCacheAfterCommit() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPoint.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPointArchive.class);

        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);

        inTx(() -> service.deleteRecord(200L, 100L), true);

        // 1. track_point 条件删除
        ArgumentCaptor<LambdaQueryWrapper<TrackPoint>> tpCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(trackPointMapper).delete(tpCaptor.capture());
        assertTrue(tpCaptor.getValue().getSqlSegment().contains("record_id ="));
        assertTrue(tpCaptor.getValue().getSqlSegment().contains("user_id ="));

        // 2. track_point_archive 条件删除
        ArgumentCaptor<LambdaQueryWrapper<TrackPointArchive>> tpaCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(trackPointArchiveMapper).delete(tpaCaptor.capture());
        assertTrue(tpaCaptor.getValue().getSqlSegment().contains("record_id ="));
        assertTrue(tpaCaptor.getValue().getSqlSegment().contains("user_id ="));

        // 3. record_like DB 行删除
        verify(recordLikeMapper).deleteByRecordId(100L);

        // 4. sport_record 条件删除
        ArgumentCaptor<LambdaQueryWrapper<SportRecord>> srCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper).delete(srCaptor.capture());
        assertTrue(srCaptor.getValue().getSqlSegment().contains("id ="));
        assertTrue(srCaptor.getValue().getSqlSegment().contains("user_id ="));

        // 5. Redis 缓存清理
        verify(recordLikeService).cleanupCache(100L);
    }

    /** R1: 非 owner 删除抛 1002 FORBIDDEN，零 DB 删除 */
    @Test
    void deleteRecord_forbiddenWhenCallerNotOwner() {
        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);

        BizException e = assertThrows(BizException.class,
                () -> service.deleteRecord(999L, 100L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());

        verify(sportRecordMapper, never()).delete(any());
        verify(trackPointMapper, never()).delete(any());
        verify(trackPointArchiveMapper, never()).delete(any());
        verify(recordLikeMapper, never()).deleteByRecordId(anyLong());
        verify(recordLikeService, never()).cleanupCache(anyLong());
        verifyNoInteractions(leaderboardApi);
    }

    /** R1: 不存在抛 3001 RECORD_NOT_FOUND，零 DB 删除 */
    @Test
    void deleteRecord_notFoundWhenRecordDoesNotExist() {
        when(sportRecordMapper.selectById(100L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.deleteRecord(200L, 100L));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());

        verify(sportRecordMapper, never()).delete(any());
        verify(trackPointMapper, never()).delete(any());
        verify(trackPointArchiveMapper, never()).delete(any());
        verify(recordLikeMapper, never()).deleteByRecordId(anyLong());
        verify(recordLikeService, never()).cleanupCache(anyLong());
        verifyNoInteractions(leaderboardApi);
    }

    /** R3: Redis 缓存清理抛异常时，不阻断删除、不抛出异常 */
    @Test
    void deleteRecord_toleratesRedisCleanupFailure() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPoint.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPointArchive.class);

        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis connection error"))
                .when(recordLikeService).cleanupCache(100L);

        // afterCommit 中抛出异常被吞并留证，正常完成
        inTx(() -> service.deleteRecord(200L, 100L), true);

        verify(sportRecordMapper).delete(any());
        verify(recordLikeMapper).deleteByRecordId(100L);
        verify(recordLikeService).cleanupCache(100L);
    }

    // ==================== 删除后榜单贡献回撤（TASK-223 D1） ====================

    /** D1: 回撤只在事务提交后触发——事务内（afterCommit 前）零调用，通知的是已提交的删除 */
    @Test
    void deleteRecord_doesNotRollbackLeaderboardBeforeCommit() {
        initDeleteTableInfo();
        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);

        inTx(() -> service.deleteRecord(200L, 100L), false);

        verify(leaderboardApi, never()).rollbackContribution(anyLong());
    }

    /** D1: afterCommit 内 best-effort 通知 leaderboard-service 回撤该记录里程贡献 */
    @Test
    void deleteRecord_rollsBackLeaderboardContributionAfterCommit() {
        initDeleteTableInfo();
        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);

        inTx(() -> service.deleteRecord(200L, 100L), true);

        verify(leaderboardApi).rollbackContribution(100L);
        // 删除本身仍已完成（回撤不改变本地删除语义）
        verify(sportRecordMapper).delete(any());
    }

    /** D1: 回撤失败（leaderboard 熔断降级 4008 / 锁竞争超时）被吞并留证，不回滚删除、不抛出 */
    @Test
    void deleteRecord_toleratesLeaderboardRollbackFailure() {
        initDeleteTableInfo();
        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);
        doThrow(new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE, "榜单服务不可用：rollbackContribution"))
                .when(leaderboardApi).rollbackContribution(100L);

        // afterCommit 中异常被吞并留证，正常完成
        inTx(() -> service.deleteRecord(200L, 100L), true);

        verify(leaderboardApi).rollbackContribution(100L);
        verify(sportRecordMapper).delete(any());
        verify(recordLikeMapper).deleteByRecordId(100L);
    }

    /** D1: 回撤抛运行时异常（网络/序列化故障）同样不阻断删除 */
    @Test
    void deleteRecord_toleratesLeaderboardRollbackRuntimeFailure() {
        initDeleteTableInfo();
        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);
        doThrow(new RuntimeException("connection reset")).when(leaderboardApi).rollbackContribution(100L);

        inTx(() -> service.deleteRecord(200L, 100L), true);

        verify(leaderboardApi).rollbackContribution(100L);
        verify(sportRecordMapper).delete(any());
    }

    /** D1: 无事务同步上下文（直接调用）时立即回撤，镜像缓存清理的 else 分支 */
    @Test
    void deleteRecord_rollsBackLeaderboardWithoutTransactionSynchronization() {
        initDeleteTableInfo();
        SportRecord record = new SportRecord();
        record.setId(100L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(100L)).thenReturn(record);

        service.deleteRecord(200L, 100L);

        verify(leaderboardApi).rollbackContribution(100L);
    }

    /** 删除路径所需的 MyBatis-Plus 表信息（LambdaQueryWrapper 列名解析） */
    private void initDeleteTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPoint.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPointArchive.class);
    }
}
