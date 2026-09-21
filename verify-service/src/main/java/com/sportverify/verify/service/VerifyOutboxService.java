package com.sportverify.verify.service;

import com.sportverify.api.verify.Verdict;
import com.sportverify.verify.entity.VerificationResult;
import com.sportverify.verify.mapper.VerificationResultMapper;
import com.sportverify.verify.mq.VerifyEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 判定结果 + outbox 事件的事务写入器（F03 本地消息表）。
 *
 * <p>独立成 Bean 以让 @Transactional 经代理生效（VerifyService 自调用注解会失效）；
 * 仅包本地两次 DB 写，不含任何远程调用，不违反「无本地长事务」（ADR-0009）——
 * Feign 回调仍在事务外执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyOutboxService {

    private final VerificationResultMapper verificationResultMapper;
    private final VerifyEventProducer verifyEventProducer;

    /**
     * 同事务落「判定结果 upsert + outbox 事件行」：两步同生共死，
     * 任一失败整体回滚（由消费端重投触发重判补齐），消除「判定成功但事件丢失」窗口。
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistResultAndEvent(VerificationResult result, Verdict verdict,
                                      Long recordId, Long userId) {
        verificationResultMapper.upsert(result);
        verifyEventProducer.publish(verdict, recordId, userId);
    }
}
