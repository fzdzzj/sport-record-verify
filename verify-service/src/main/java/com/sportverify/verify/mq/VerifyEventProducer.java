package com.sportverify.verify.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.verify.entity.VerifyEventOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 校验事件生产者（规范「校验事件与幂等」「判定事件可靠投递」）。
 *
 * <p>职责一分为二，判定链路与投递链路由此彻底分开：</p>
 * <ul>
 *   <li><b>写侧</b> {@link #newPendingRow}：在判定事务内构造 outbox 待发行行——eventId 在此生成一次
 *       并随行落库，relay 重发沿用行内 eventId，消费端 SETNX 幂等键在重试间稳定；</li>
 *   <li><b>投递侧</b> {@link #syncSend}：由 {@code VerifyOutboxRelay} 唯一调用，按行内 topic/tag 同步发送
 *       行内 payload，并把落库时的 traceId 透传到消息 userProperty（add-request-tracing）。</li>
 * </ul>
 *
 * <p>投递失败不再吞异常：抛出交由 relay 记 retry_count 并下轮重试，超阈值保留行供人工处理。
 * 判定线程不再有同步直发路径（唯一出口为 relay），故不存在「直发 + relay」双发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyEventProducer {

    /** 待发行状态：relay 扫描该状态的行投递 */
    private static final String STATUS_PENDING = "PENDING";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 写侧：构造 outbox 待发行行（不同步发送，由调用方与结果行同事务落库）。
     *
     * <p>事件体序列化失败抛 {@link BizException} 使所在事务回滚——
     * 「判定落库但事件行不存在」的窗口不允许以静默方式产生。</p>
     *
     * @param verdict  PASSED → VERIFIED 事件；REJECTED → REJECTED 事件
     * @param recordId 记录ID
     * @param userId   所属用户
     * @return PENDING 行（含写入时生成的 eventId、topic、tag、payload、traceId）
     */
    public VerifyEventOutbox newPendingRow(Verdict verdict, Long recordId, Long userId) {
        boolean passed = verdict == Verdict.PASSED;
        VerifyEventDTO event = new VerifyEventDTO(
                UUID.randomUUID().toString(),
                recordId,
                userId,
                passed ? RecordVerifyEvents.EVENT_VERIFIED : RecordVerifyEvents.EVENT_REJECTED,
                LocalDateTime.now());

        VerifyEventOutbox row = new VerifyEventOutbox();
        row.setEventId(event.getEventId());
        row.setTopic(RecordVerifyEvents.TOPIC);
        row.setTag(passed ? RecordVerifyEvents.TAG_VERIFIED : RecordVerifyEvents.TAG_REJECTED);
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "校验事件体序列化失败：recordId=" + recordId);
        }
        row.setTraceId(TraceIds.current());
        row.setStatus(STATUS_PENDING);
        row.setRetryCount(0);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /**
     * 投递侧：按行内 topic/tag 同步发送行内 payload（relay 唯一调用方）。
     *
     * <p>发送失败抛出（不吞），由 relay 记 retry_count 下轮重试；
     * 行内 eventId/payload 原样投递，重发不换 eventId。</p>
     */
    public void syncSend(VerifyEventOutbox row) {
        var builder = MessageBuilder.withPayload(row.getPayload());
        if (row.getTraceId() != null && !row.getTraceId().isBlank()) {
            builder.setHeader(TraceIds.HEADER, row.getTraceId());
        }
        rocketMQTemplate.syncSend(row.getTopic() + ":" + row.getTag(), builder.build());
        log.info("outbox 事件已投递：eventId={}, topic={}, tag={}",
                row.getEventId(), row.getTopic(), row.getTag());
    }
}
