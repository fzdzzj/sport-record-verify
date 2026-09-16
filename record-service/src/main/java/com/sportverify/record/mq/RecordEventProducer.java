package com.sportverify.record.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
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
 * 记录事件生产者（RocketMQ，规范「校验事件与幂等」）。
 *
 * <p>提交成功并迁移 VERIFYING 后异步发布 SUBMITTED 事件，由 verify-service 消费
 * 触发「拉轨迹 → 判定 → 回调」校验闭环。发送前捕获 MDC traceId，写入消息 userProperty，
 * 并在异步回调线程恢复（add-request-tracing）；不在提交请求线程上 syncSend。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordEventProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 异步发布 SUBMITTED 事件。调用线程立即返回，不阻塞等待 Broker ACK。
     *
     * @param recordId  记录ID
     * @param userId    所属用户（冗余分片键）
     * @param onFailure 发送失败回调（在 RocketMQ 回调线程执行，已恢复 traceId）；成功不调用
     */
    public void publishSubmitted(Long recordId, Long userId, Runnable onFailure) {
        // eventId 全局唯一：消费者 SETNX 去重，保证重复投递只消费一次
        VerifyEventDTO event = new VerifyEventDTO(
                UUID.randomUUID().toString(),
                recordId,
                userId,
                RecordVerifyEvents.EVENT_SUBMITTED,
                LocalDateTime.now());
        // 发送前捕获：async 回调不在请求线程，MDC 不会自动传播
        String traceId = TraceIds.current();
        try {
            String payload = objectMapper.writeValueAsString(event);
            var builder = MessageBuilder.withPayload(payload);
            if (traceId != null && !traceId.isBlank()) {
                builder.setHeader(TraceIds.HEADER, traceId);
            }
            String destination = RecordVerifyEvents.TOPIC + ":" + RecordVerifyEvents.TAG_SUBMITTED;
            // Tag 区分事件类型；消息体为 JSON；traceId 经 header → RocketMQ userProperty
            rocketMQTemplate.asyncSend(destination, builder.build(), new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    runWithTrace(traceId, () ->
                            log.info("已发布 SUBMITTED 事件：recordId={}, userId={}, traceId={}, msgId={}",
                                    recordId, userId, traceId,
                                    sendResult != null ? sendResult.getMsgId() : null));
                }

                @Override
                public void onException(Throwable e) {
                    runWithTrace(traceId, () -> {
                        // 发送失败不阻断提交（记录已落库）；由调用方降级 Feign 直调触发校验
                        log.error("发布 SUBMITTED 事件失败：recordId={}", recordId, e);
                        if (onFailure != null) {
                            onFailure.run();
                        }
                    });
                }
            });
        } catch (Exception e) {
            // 序列化/提交发送本身失败：同步触发失败回调（仍不在「等 Broker」语义上阻塞成功路径）
            log.error("发布 SUBMITTED 事件提交失败：recordId={}", recordId, e);
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }

    /** 异步线程恢复 / 清理 MDC，避免线程池复用串号 */
    private static void runWithTrace(String traceId, Runnable action) {
        TraceIds.put(traceId);
        try {
            action.run();
        } finally {
            TraceIds.clear();
        }
    }
}
