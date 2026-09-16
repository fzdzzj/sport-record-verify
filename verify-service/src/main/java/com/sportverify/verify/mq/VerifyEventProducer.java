package com.sportverify.verify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.common.trace.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 校验事件生产者（规范「校验事件与幂等」）。
 *
 * <p>判定/终判后发布 VERIFIED / REJECTED 事件（Tag 区分）；状态同步走 Feign 回调，
 * 事件供下游（排行榜等）消费。发送时透传 MDC traceId 到消息 userProperty（add-request-tracing）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyEventProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布判定/终判事件。
     *
     * @param verdict   PASSED → VERIFIED 事件；REJECTED → REJECTED 事件
     * @param recordId  记录ID
     * @param userId    所属用户
     */
    public void publish(Verdict verdict, Long recordId, Long userId) {
        boolean passed = verdict == Verdict.PASSED;
        VerifyEventDTO event = new VerifyEventDTO(
                UUID.randomUUID().toString(),
                recordId,
                userId,
                passed ? RecordVerifyEvents.EVENT_VERIFIED : RecordVerifyEvents.EVENT_REJECTED,
                LocalDateTime.now());
        try {
            String payload = objectMapper.writeValueAsString(event);
            var builder = MessageBuilder.withPayload(payload);
            String traceId = TraceIds.current();
            if (traceId != null && !traceId.isBlank()) {
                builder.setHeader(TraceIds.HEADER, traceId);
            }
            rocketMQTemplate.syncSend(RecordVerifyEvents.TOPIC + ":" + (passed
                            ? RecordVerifyEvents.TAG_VERIFIED : RecordVerifyEvents.TAG_REJECTED),
                    builder.build());
            log.info("已发布 {} 事件：recordId={}, traceId={}", event.getEventType(), recordId, traceId);
        } catch (Exception e) {
            // 事件发送失败不阻断判定回调；生产可加本地消息表补偿
            log.error("发布 {} 事件失败：recordId={}", event.getEventType(), recordId, e);
        }
    }
}
