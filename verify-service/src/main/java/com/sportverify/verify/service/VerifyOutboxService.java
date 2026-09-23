package com.sportverify.verify.service;

import com.sportverify.api.verify.Verdict;
import com.sportverify.verify.entity.VerificationResult;
import com.sportverify.verify.mapper.AppealMapper;
import com.sportverify.verify.mapper.VerificationResultMapper;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import com.sportverify.verify.mq.VerifyEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 判定结果 + outbox 事件的事务写入器（F03 本地消息表，TASK-131 接线）。
 *
 * <p>两条判定发事件路径都在这里落库：判定主链路「verification_result upsert + outbox PENDING 行」、
 * 申诉终判「appeal 终判行更新 + outbox PENDING 行」——同事务同生共死，
 * 消除「判定成功但事件不存在」的窗口（事件之后由 relay 唯一投递）。</p>
 *
 * <p>独立成 Bean 以让 @Transactional 经代理生效（VerifyService 自调用注解会失效）；
 * 两个方法都只包本地 DB 写，不含任何远程调用，不违反「无本地长事务」（ADR-0009）——
 * Feign 回调仍在事务外执行，投递也由 relay 在事务外完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyOutboxService {

    private final VerificationResultMapper verificationResultMapper;
    private final AppealMapper appealMapper;
    private final VerifyEventOutboxMapper outboxMapper;
    private final VerifyEventProducer verifyEventProducer;

    /**
     * 判定主链路：同事务落「判定结果 upsert + outbox 事件行」。
     *
     * <p>任一失败整体回滚（由消费端重投触发重判补齐）；事件体序列化失败同样回滚，
     * 不以「结果落库、事件丢失」收场。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistResultAndEvent(VerificationResult result, Verdict verdict,
                                      Long recordId, Long userId) {
        verificationResultMapper.upsert(result);
        outboxMapper.insert(verifyEventProducer.newPendingRow(verdict, recordId, userId));
    }

    /**
     * 申诉终判链路：同事务落「appeal 终判行更新（乐观锁）+ outbox 事件行」。
     *
     * <p>影响 0 行说明并发已改状态，返回 0 且不写 outbox 行；调用方据此报 3006，
     * 事务随之回滚，不会留下「申诉行没动、事件却排了队」的错态。</p>
     *
     * @return appeal 更新影响行数（0 = 并发冲突）
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateAppealAndEvent(Long appealId, Integer fromStatus, Integer toStatus,
                                    String operator, String recheckResult,
                                    Verdict verdict, Long recordId, Long userId) {
        int rows = appealMapper.updateStatus(appealId, fromStatus, toStatus, operator, recheckResult);
        if (rows > 0) {
            outboxMapper.insert(verifyEventProducer.newPendingRow(verdict, recordId, userId));
        }
        return rows;
    }
}
