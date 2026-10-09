package com.sportverify.record.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.LikeEventDTO;
import com.sportverify.api.event.RecordLikeEvents;
import com.sportverify.common.trace.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 点赞事件生产者（RocketMQ，best-effort，TASK-188）.
 *
 * <p>点赞成功后异步发布 LIKED 事件至 {@code record-like-events}，
 * 由 user-service 消费落通知。发送前捕获 MDC traceId 并在回调线程恢复；
 * 采用 best-effort 模式，发送异常仅记录告警日志，不阻断点赞主流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeEventProducer {

    /** RocketMQ 发送模板. */
    private final RocketMQTemplate rocketMQTemplate;

    /** JSON 序列化器. */
    private final ObjectMapper objectMapper;

    /**
     * 异步发布 LIKED 事件.
     *
     * @param recordId 记录ID。
     * @param likerId 点赞者用户ID。
     * @param recordOwnerId 记录所有者用户ID。
     */
    public void publishLiked(final Long recordId,
                             final Long likerId,
                             final Long recordOwnerId) {
        LikeEventDTO event = new LikeEventDTO(
                UUID.randomUUID().toString(),
                recordId,
                likerId,
                recordOwnerId,
                LocalDateTime.now());
        String traceId = TraceIds.current();
        try {
            String payload = objectMapper.writeValueAsString(event);
            var builder = MessageBuilder.withPayload(payload);
            if (traceId != null && !traceId.isBlank()) {
                builder.setHeader(TraceIds.HEADER, traceId);
            }
            String destination = RecordLikeEvents.TOPIC + ":"
                    + RecordLikeEvents.TAG_LIKED;
            rocketMQTemplate.asyncSend(
                    destination,
                    builder.build(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(final SendResult sendResult) {
                            runWithTrace(traceId, () -> log.info(
                                    "已发布 LIKED 事件：recordId={}, traceId={}",
                                    recordId, traceId));
                        }

                        @Override
                        public void onException(final Throwable e) {
                            runWithTrace(traceId, () -> log.error(
                                    "发布 LIKED 事件失败：recordId={}",
                                    recordId, e));
                        }
                    });
        } catch (Exception e) {
            log.error("发布 LIKED 事件提交失败：recordId={}", recordId, e);
        }
    }

    /**
     * 异步线程恢复或清理 MDC.
     *
     * @param traceId 跟踪ID。
     * @param action 待执行动作。
     */
    private static void runWithTrace(final String traceId,
                                     final Runnable action) {
        TraceIds.put(traceId);
        try {
            action.run();
        } finally {
            TraceIds.clear();
        }
    }
}
