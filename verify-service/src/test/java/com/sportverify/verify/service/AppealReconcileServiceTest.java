package com.sportverify.verify.service;

import com.sportverify.api.record.RecordApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.verify.AppealStatus;
import com.sportverify.common.result.Result;
import com.sportverify.verify.entity.Appeal;
import com.sportverify.verify.mapper.AppealMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申诉终判偏斜兜底对账服务单元测试（TASK-253 R2/E6）。
 *
 * <p>纯 Mockito：AppealMapper / RecordApi / RedissonClient（RLock 恒可获取）打桩，
 * 覆盖偏斜修复参数 / 已迁移跳过 / 其他终态跳过 / 单行失败继续批内 / 锁未获取跳过
 * （与 RecordLikeServiceTest / VerifyServiceTest 同款风格，不依赖真实中间件）。</p>
 */
class AppealReconcileServiceTest {

    private AppealMapper appealMapper;
    private RecordApi recordApi;
    private RedissonClient redissonClient;
    private RLock lock;
    private AppealReconcileService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        appealMapper = mock(AppealMapper.class);
        recordApi = mock(RecordApi.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        service = new AppealReconcileService(appealMapper, recordApi, redissonClient);
    }

    private Appeal appeal(Long id, Long recordId, Integer status) {
        Appeal a = new Appeal();
        a.setId(id);
        a.setRecordId(recordId);
        a.setStatus(status);
        return a;
    }

    private SportRecordDTO record(Integer status, Integer version) {
        SportRecordDTO r = new SportRecordDTO();
        r.setId(1L);
        r.setStatus(status);
        r.setVersion(version);
        return r;
    }

    /** ① 偏斜修复：appeal RE_PASSED + record 仍 APPEALING → statusCallback 恰一次且参数正确 */
    @Test
    void reconcile_skewedAppeal_rePassed_repairsOnce() {
        Appeal appeal = appeal(10L, 1L, AppealStatus.RE_PASSED.getCode());
        when(appealMapper.selectFinalizedBatch(100)).thenReturn(List.of(appeal));
        when(recordApi.getRecord(1L)).thenReturn(Result.success(record(RecordStatus.APPEALING.getCode(), 7)));

        service.reconcile();

        ArgumentCaptor<StatusCallbackDTO> captor = ArgumentCaptor.forClass(StatusCallbackDTO.class);
        verify(recordApi, times(1)).statusCallback(eq(1L), captor.capture());
        StatusCallbackDTO dto = captor.getValue();
        assertNotNull(dto);
        assertEquals(1L, dto.getRecordId());
        assertEquals(RecordStatus.APPEALING.getCode(), dto.getFromStatus());
        assertEquals(RecordStatus.RE_PASSED.getCode(), dto.getToStatus());
        assertEquals(7, dto.getVersion());
    }

    /** ② record 已迁移（RE_PASSED）→ 跳过零回调 */
    @Test
    void reconcile_recordAlreadyRePassed_skipsCallback() {
        Appeal appeal = appeal(10L, 1L, AppealStatus.RE_PASSED.getCode());
        when(appealMapper.selectFinalizedBatch(100)).thenReturn(List.of(appeal));
        when(recordApi.getRecord(1L)).thenReturn(Result.success(record(RecordStatus.RE_PASSED.getCode(), 7)));

        service.reconcile();

        verify(recordApi, never()).statusCallback(anyLong(), any(StatusCallbackDTO.class));
    }

    /** ③ record 其他终态（如 PASSED）→ 跳过零回调 */
    @Test
    void reconcile_recordOtherFinalState_skipsCallback() {
        Appeal appeal = appeal(10L, 1L, AppealStatus.RE_CONFIRMED.getCode());
        when(appealMapper.selectFinalizedBatch(100)).thenReturn(List.of(appeal));
        when(recordApi.getRecord(1L)).thenReturn(Result.success(record(RecordStatus.PASSED.getCode(), 3)));

        service.reconcile();

        verify(recordApi, never()).statusCallback(anyLong(), any(StatusCallbackDTO.class));
    }

    /** ④ 单行失败（非 0 响应 / Feign 异常）→ 记日志后批内其余行仍处理 */
    @Test
    void reconcile_rowFailure_continuesRestOfBatch() {
        // 行 A：record APPEALING 但回调返回非 0 → 失败
        Appeal a = appeal(1L, 100L, AppealStatus.RE_PASSED.getCode());
        // 行 B：getRecord 抛 Feign 异常 → 失败
        Appeal b = appeal(2L, 200L, AppealStatus.RE_CONFIRMED.getCode());
        // 行 C：正常修复
        Appeal c = appeal(3L, 300L, AppealStatus.RE_PASSED.getCode());
        when(appealMapper.selectFinalizedBatch(100)).thenReturn(List.of(a, b, c));
        when(recordApi.getRecord(100L)).thenReturn(Result.success(record(RecordStatus.APPEALING.getCode(), 1)));
        when(recordApi.getRecord(200L)).thenThrow(new RuntimeException("Feign 调用异常"));
        when(recordApi.getRecord(300L)).thenReturn(Result.success(record(RecordStatus.APPEALING.getCode(), 2)));
        when(recordApi.statusCallback(eq(100L), any(StatusCallbackDTO.class)))
                .thenReturn(Result.failure(3003, "状态回调失败"));
        when(recordApi.statusCallback(eq(300L), any(StatusCallbackDTO.class))).thenReturn(Result.success());

        service.reconcile(); // 不抛异常即批内其余仍处理

        // 行 C 修复回调恰一次且参数正确；行 A 失败路径也产生一次回调调用（非 0 响应）
        ArgumentCaptor<StatusCallbackDTO> captor = ArgumentCaptor.forClass(StatusCallbackDTO.class);
        verify(recordApi, times(2)).statusCallback(anyLong(), captor.capture());
        StatusCallbackDTO last = captor.getAllValues().get(1);
        assertEquals(300L, last.getRecordId());
        assertEquals(RecordStatus.RE_PASSED.getCode(), last.getToStatus());
    }

    /** ⑤ 锁未获取 → 本轮跳过，零扫描零回调 */
    @Test
    void reconcile_lockNotAcquired_skipsRound() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        service.reconcile();

        verify(appealMapper, never()).selectFinalizedBatch(anyInt());
        verify(recordApi, never()).getRecord(anyLong());
        verify(recordApi, never()).statusCallback(anyLong(), any(StatusCallbackDTO.class));
    }

    /** ⑥ RE_CONFIRMED 映射：appeal RE_CONFIRMED → record 回调 RE_CONFIRMED */
    @Test
    void reconcile_skewedAppeal_reConfirmed_mapsToReConfirmed() {
        Appeal appeal = appeal(10L, 1L, AppealStatus.RE_CONFIRMED.getCode());
        when(appealMapper.selectFinalizedBatch(100)).thenReturn(List.of(appeal));
        when(recordApi.getRecord(1L)).thenReturn(Result.success(record(RecordStatus.APPEALING.getCode(), 5)));

        service.reconcile();

        ArgumentCaptor<StatusCallbackDTO> captor = ArgumentCaptor.forClass(StatusCallbackDTO.class);
        verify(recordApi, times(1)).statusCallback(eq(1L), captor.capture());
        assertEquals(RecordStatus.RE_CONFIRMED.getCode(), captor.getValue().getToStatus());
    }
}
