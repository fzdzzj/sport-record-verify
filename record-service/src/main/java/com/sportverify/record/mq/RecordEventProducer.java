package com.sportverify.record.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 记录事件生产者（RocketMQ，规范「校验事件与幂等」）。
 *
 * <p>提交成功并迁移 VERIFYING 后发布 SUBMITTED 事件，由 verify-service 消费
 * 触发「拉轨迹 → 判定 → 回调」校验闭环。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordEventProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布 SUBMITTED 事件。
     *
     * @param recordId 记录ID
     * @param userId   所属用户（冗余分片键）
     * @return 是否发布成功（失败时调用方走 Feign 直调降级路径）
     */
    public boolean publishSubmitted(Long recordId, Long userId) {
        // eventId 全局唯一：消费者 SETNX 去重，保证重复投递只消费一次
        VerifyEventDTO event = new VerifyEventDTO(
                UUID.randomUUID().toString(),
                recordId,
                userId,
                RecordVerifyEvents.EVENT_SUBMITTED,
                LocalDateTime.now());
        try {
            // Tag 区分事件类型；消息体为 JSON（rocketmq-spring 默认 Jackson 转换器）
            rocketMQTemplate.syncSend(
                    RecordVerifyEvents.TOPIC + ":" + RecordVerifyEvents.TAG_SUBMITTED,
                    objectMapper.writeValueAsString(event));
            log.info("已发布 SUBMITTED 事件：recordId={}, userId={}", recordId, userId);
            return true;
        } catch (Exception e) {
            // 发送失败不阻断提交（记录已落库）；由调用方降级 Feign 直调触发校验
            log.error("发布 SUBMITTED 事件失败：recordId={}", recordId, e);
            return false;
        }
    }
}
