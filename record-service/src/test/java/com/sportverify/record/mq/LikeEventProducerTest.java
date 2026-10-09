package com.sportverify.record.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.LikeEventDTO;
import com.sportverify.api.event.RecordLikeEvents;
import com.sportverify.common.trace.TraceIds;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 点赞事件生产者单元测试（TASK-188 新立先例）。
 *
 * <p>基于 Mockito 对 RocketMQTemplate 打桩，断言：
 * <ol>
 *   <li>目的地为 {@code record-like-events:LIKED}；</li>
 *   <li>payload 经 ObjectMapper 真序列化包含五字段（eventId/recordId/likerId/recordOwnerId/occurredAt）；</li>
 *   <li>MDC traceId 经消息头透传；</li>
 *   <li>RocketMQ 提交异常或异步回调异常均安全吞吐仅告警，不抛出异常。</li>
 * </ol>
 * </p>
 */
class LikeEventProducerTest {

    private RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private LikeEventProducer producer;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        producer = new LikeEventProducer(rocketMQTemplate, mapper);
    }

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    /** 正常发布：断言 destination=record-like-events:LIKED，且 payload 真序列化含五字段 */
    @Test
    @SuppressWarnings("unchecked")
    void publishLiked_success_sendsDestinationAndPayloadWithFiveFields() throws Exception {
        TraceIds.put("test-trace-id");
        ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<SendCallback> callbackCaptor = ArgumentCaptor.forClass(SendCallback.class);

        producer.publishLiked(10L, 20L, 30L);

        verify(rocketMQTemplate).asyncSend(destCaptor.capture(), msgCaptor.capture(), callbackCaptor.capture());
        assertEquals(RecordLikeEvents.TOPIC + ":" + RecordLikeEvents.TAG_LIKED, destCaptor.getValue());

        Message<?> msg = msgCaptor.getValue();
        assertEquals("test-trace-id", msg.getHeaders().get(TraceIds.HEADER));

        String payload = String.valueOf(msg.getPayload());
        LikeEventDTO dto = mapper.readValue(payload, LikeEventDTO.class);
        assertNotNull(dto.getEventId());
        assertFalse(dto.getEventId().isBlank());
        assertEquals(10L, dto.getRecordId());
        assertEquals(20L, dto.getLikerId());
        assertEquals(30L, dto.getRecordOwnerId());
        assertNotNull(dto.getOccurredAt());

        // 异步回调成功不抛出
        SendCallback callback = callbackCaptor.getValue();
        assertDoesNotThrow(() -> callback.onSuccess(mock(SendResult.class)));
    }

    /** 异步回调异常：best-effort 仅记录日志，不抛出异常 */
    @Test
    @SuppressWarnings("unchecked")
    void publishLiked_asyncCallbackException_doesNotThrow() {
        ArgumentCaptor<SendCallback> callbackCaptor = ArgumentCaptor.forClass(SendCallback.class);
        producer.publishLiked(10L, 20L, 30L);

        verify(rocketMQTemplate).asyncSend(anyString(), any(Message.class), callbackCaptor.capture());
        SendCallback callback = callbackCaptor.getValue();
        assertDoesNotThrow(() -> callback.onException(new RuntimeException("Broker 连接超时")));
    }

    /** 提交阶段异常：RocketMQTemplate 抛异常时不向外抛出（best-effort） */
    @Test
    @SuppressWarnings("unchecked")
    void publishLiked_submitException_doesNotThrow() {
        doThrow(new RuntimeException("RocketMQTemplate 同步异常"))
                .when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class));

        assertDoesNotThrow(() -> producer.publishLiked(10L, 20L, 30L));
    }
}
