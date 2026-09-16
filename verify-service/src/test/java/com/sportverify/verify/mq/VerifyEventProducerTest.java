package com.sportverify.verify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.verify.Verdict;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验事件生产者单元测试（PASSED → VERIFIED Tag / REJECTED → REJECTED Tag / 发送失败吞异常）。
 *
 * <p>纯 Mockito：RocketMQTemplate mock；真实 ObjectMapper 序列化事件体并反解校验类型字段。</p>
 */
class VerifyEventProducerTest {

    private RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private VerifyEventProducer producer;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        producer = new VerifyEventProducer(rocketMQTemplate, mapper);
    }

    /** PASSED → 发布 VERIFIED Tag 事件，事件体含 recordId/userId */
    @Test
    void publish_passed_sendsVerifiedTag() throws Exception {
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        producer.publish(Verdict.PASSED, 1L, 100L);

        verify(rocketMQTemplate).syncSend(dest.capture(), body.capture());
        String topic = dest.getValue();
        assertTrue(topic.startsWith(RecordVerifyEvents.TOPIC + ":"));
        assertEquals(RecordVerifyEvents.TAG_VERIFIED, topic.substring(topic.indexOf(':') + 1));
        var event = mapper.readValue(body.getValue(), com.sportverify.api.event.VerifyEventDTO.class);
        assertTrue(event.getEventId() != null && !event.getEventId().isBlank());
        assertEquals(1L, event.getRecordId());
        assertEquals(100L, event.getUserId());
        assertEquals(RecordVerifyEvents.EVENT_VERIFIED, event.getEventType());
    }

    /** REJECTED → 发布 REJECTED Tag 事件 */
    @Test
    void publish_rejected_sendsRejectedTag() throws Exception {
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);

        producer.publish(Verdict.REJECTED, 2L, 200L);

        verify(rocketMQTemplate).syncSend(dest.capture(), anyString());
        assertEquals(RecordVerifyEvents.TOPIC + ":" + RecordVerifyEvents.TAG_REJECTED, dest.getValue());
    }

    /** 发送失败 → 仅告警，不抛出（事件失败不阻断判定回调） */
    @Test
    void publish_sendFailure_swallows() {
        doThrow(new RuntimeException("MQ 发送失败")).when(rocketMQTemplate).syncSend(anyString(), anyString());

        producer.publish(Verdict.PASSED, 3L, 300L); // 不抛异常
    }
}