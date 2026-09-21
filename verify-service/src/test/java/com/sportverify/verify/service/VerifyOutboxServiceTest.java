package com.sportverify.verify.service;

import com.sportverify.api.verify.Verdict;
import com.sportverify.verify.entity.VerificationResult;
import com.sportverify.verify.mapper.VerificationResultMapper;
import com.sportverify.verify.mq.VerifyEventProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * 判定结果 + outbox 事件事务写入器单元测试（F03）。
 *
 * <p>纯 Mockito：验证同一方法内先 upsert 判定结果、再落 outbox 事件行
 * （事务边界本身由 Spring 代理提供，单测只锁顺序与协作）。</p>
 */
class VerifyOutboxServiceTest {

    private VerificationResultMapper verificationResultMapper;
    private VerifyEventProducer verifyEventProducer;
    private VerifyOutboxService service;

    @BeforeEach
    void setUp() {
        verificationResultMapper = mock(VerificationResultMapper.class);
        verifyEventProducer = mock(VerifyEventProducer.class);
        service = new VerifyOutboxService(verificationResultMapper, verifyEventProducer);
    }

    /** upsert 判定结果与 outbox 事件行在同一方法内顺序执行（同事务同生共死） */
    @Test
    void persistResultAndEvent_upsertsThenPublishes() {
        VerificationResult result = new VerificationResult();
        result.setRecordId(1L);
        result.setVerdict(Verdict.PASSED.getCode());

        service.persistResultAndEvent(result, Verdict.PASSED, 1L, 100L);

        InOrder order = inOrder(verificationResultMapper, verifyEventProducer);
        order.verify(verificationResultMapper).upsert(result);
        order.verify(verifyEventProducer).publish(Verdict.PASSED, 1L, 100L);
    }
}
