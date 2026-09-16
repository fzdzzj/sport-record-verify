package com.sportverify.verify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.verify.Verdict;
import com.sportverify.common.trace.TraceIds;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验事件生产者单元测试（PASSED → VERIFIED Tag / REJECTED → REJECTED Tag / 发送失败吞异常 / traceId 透传）。
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

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    /** PASSED → 发布 VERIFIED Tag 事件，事件体含 recordId/userId */
    @Test
    @SuppressWarnings("unchecked")
    void publish_passed_sendsVerifiedTag() throws Exception {
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);

        producer.publish(Verdict.PASSED, 1L, 100L);

        verify(rocketMQTemplate).syncSend(dest.capture(), msgCaptor.capture());
        String topic = dest.getValue();
        assertTrue(topic.startsWith(RecordVerifyEvents.TOPIC + ":"));
        assertEquals(RecordVerifyEvents.TAG_VERIFIED, topic.substring(topic.indexOf(':') + 1));
        String body = String.valueOf(msgCaptor.getValue().getPayload());
        var event = mapper.readValue(body, com.sportverify.api.event.VerifyEventDTO.class);
        assertTrue(event.getEventId() != null && !event.getEventId().isBlank());
        assertEquals(1L, event.getRecordId());
        assertEquals(100L, event.getUserId());
        assertEquals(RecordVerifyEvents.EVENT_VERIFIED, event.getEventType());
    }

    /** MDC 有 traceId 时写入消息头（→ MQ userProperty） */
    @Test
    @SuppressWarnings("unchecked")
    void publish_withTraceId_setsHeader() {
        TraceIds.put("trace-abc");
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);

        producer.publish(Verdict.PASSED, 1L, 100L);

        verify(rocketMQTemplate).syncSend(anyString(), msgCaptor.capture());
        assertEquals("trace-abc", msgCaptor.getValue().getHeaders().get(TraceIds.HEADER));
    }

    /** REJECTED → 发布 REJECTED Tag 事件 */
    @Test
    void publish_rejected_sendsRejectedTag() {
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);

        producer.publish(Verdict.REJECTED, 2L, 200L);

        verify(rocketMQTemplate).syncSend(dest.capture(), any(Message.class));
        assertEquals(RecordVerifyEvents.TOPIC + ":" + RecordVerifyEvents.TAG_REJECTED, dest.getValue());
    }

    /** 发送失败 → 仅告警，不抛出（事件失败不阻断判定回调） */
    @Test
    void publish_sendFailure_swallows() {
        doThrow(new RuntimeException("MQ 发送失败")).when(rocketMQTemplate).syncSend(anyString(), any(Message.class));

        producer.publish(Verdict.PASSED, 3L, 300L); // 不抛异常
    }
}
