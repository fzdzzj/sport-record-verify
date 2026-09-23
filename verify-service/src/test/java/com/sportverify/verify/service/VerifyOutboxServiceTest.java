package com.sportverify.verify.service;

import com.sportverify.api.verify.Verdict;
import com.sportverify.verify.entity.VerificationResult;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.AppealMapper;
import com.sportverify.verify.mapper.VerificationResultMapper;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import com.sportverify.verify.mq.VerifyEventProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判定结果 + outbox 事件事务写入器单元测试（F03 / TASK-131 接线）。
 *
 * <p>纯 Mockito：两组 Mapper 与生产者全部 mock，锁「同一方法内先写结果行、再写 outbox 事件行」
 * 的协作顺序，并以注解判别式锁事务边界（事务代理本身由 Spring 提供，单测不启动容器）。</p>
 */
class VerifyOutboxServiceTest {

    private VerificationResultMapper verificationResultMapper;
    private AppealMapper appealMapper;
    private VerifyEventOutboxMapper outboxMapper;
    private VerifyEventProducer verifyEventProducer;
    private VerifyOutboxService service;

    @BeforeEach
    void setUp() {
        verificationResultMapper = mock(VerificationResultMapper.class);
        appealMapper = mock(AppealMapper.class);
        outboxMapper = mock(VerifyEventOutboxMapper.class);
        verifyEventProducer = mock(VerifyEventProducer.class);
        service = new VerifyOutboxService(verificationResultMapper, appealMapper,
                outboxMapper, verifyEventProducer);
    }

    private VerifyEventOutbox pendingRow(String eventId) {
        VerifyEventOutbox row = new VerifyEventOutbox();
        row.setEventId(eventId);
        row.setStatus("PENDING");
        row.setRetryCount(0);
        return row;
    }

    /** 判定主链路：upsert 判定结果与 insert outbox 待发行行在同一方法内顺序执行（同事务同生共死） */
    @Test
    void persistResultAndEvent_upsertsThenInsertsPendingRow() {
        VerificationResult result = new VerificationResult();
        result.setRecordId(1L);
        result.setVerdict(Verdict.PASSED.getCode());
        VerifyEventOutbox pending = pendingRow("evt-1");
        when(verifyEventProducer.newPendingRow(Verdict.PASSED, 1L, 100L)).thenReturn(pending);

        service.persistResultAndEvent(result, Verdict.PASSED, 1L, 100L);

        InOrder order = inOrder(verificationResultMapper, outboxMapper);
        order.verify(verificationResultMapper).upsert(result);
        order.verify(outboxMapper).insert(pending);
    }

    /** 申诉终判链路：appeal 终判行更新与 outbox 行 insert 顺序执行 */
    @Test
    void updateAppealAndEvent_updatesAppealThenInsertsPendingRow() {
        VerifyEventOutbox pending = pendingRow("evt-9");
        when(appealMapper.updateStatus(9L, 0, 1, "admin", "复核通过")).thenReturn(1);
        when(verifyEventProducer.newPendingRow(Verdict.PASSED, 5L, 100L)).thenReturn(pending);

        int rows = service.updateAppealAndEvent(9L, 0, 1, "admin", "复核通过",
                Verdict.PASSED, 5L, 100L);

        assertEquals(1, rows);
        InOrder order = inOrder(appealMapper, outboxMapper);
        order.verify(appealMapper).updateStatus(9L, 0, 1, "admin", "复核通过");
        order.verify(outboxMapper).insert(pending);
    }

    /** 申诉乐观锁冲突（影响 0 行）：不写 outbox 行（不给未生效的终判排队事件） */
    @Test
    void updateAppealAndEvent_zeroRows_skipsOutbox() {
        when(appealMapper.updateStatus(any(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(0);

        int rows = service.updateAppealAndEvent(9L, 0, 1, "admin", null,
                Verdict.REJECTED, 5L, 100L);

        assertEquals(0, rows);
        verify(outboxMapper, never()).insert(any(VerifyEventOutbox.class));
        verify(verifyEventProducer, never()).newPendingRow(any(), any(), any());
    }

    /** 事务边界判别式：两个写入方法都带 @Transactional(rollbackFor=Exception)（结果行与事件行同生共死） */
    @Test
    void outboxWriteMethods_areTransactionalWithRollbackForException() throws Exception {
        Method persistResult = VerifyOutboxService.class.getMethod("persistResultAndEvent",
                VerificationResult.class, Verdict.class, Long.class, Long.class);
        Method updateAppeal = VerifyOutboxService.class.getMethod("updateAppealAndEvent",
                Long.class, Integer.class, Integer.class, String.class, String.class,
                Verdict.class, Long.class, Long.class);

        for (Method m : new Method[]{persistResult, updateAppeal}) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertNotNull(tx, m.getName() + " 缺少 @Transactional（两次写会各自提交）");
            assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class),
                    m.getName() + " 的 rollbackFor 未含 Exception");
        }
    }
}
