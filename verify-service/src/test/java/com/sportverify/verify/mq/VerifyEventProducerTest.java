package com.sportverify.verify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.verify.entity.VerifyEventOutbox;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验事件生产者单元测试（TASK-131 拆分写侧 / 投递侧）。
 *
 * <p>写侧：PASSED → VERIFIED Tag、REJECTED → REJECTED Tag、eventId 在写入时生成且落进行内、
 * MDC traceId 随行落库；投递侧：按行内 topic/tag 与行内 payload 发送、traceId 透传消息头、
 * 失败**不吞**（抛出交由 relay 记 retry_count）。纯 Mockito：RocketMQTemplate mock，
 * 真实 ObjectMapper 序列化事件体并反解校验类型字段。</p>
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

    /** 写侧：PASSED → PENDING 行，Tag=VERIFIED，eventId 落行且与 payload 内 eventId 逐字一致 */
    @Test
    void newPendingRow_passed_setsVerifiedTagAndEventId() throws Exception {
        VerifyEventOutbox row = producer.newPendingRow(Verdict.PASSED, 1L, 100L);

        assertEquals(RecordVerifyEvents.TOPIC, row.getTopic());
        assertEquals(RecordVerifyEvents.TAG_VERIFIED, row.getTag());
        assertEquals("PENDING", row.getStatus());
        assertEquals(0, row.getRetryCount());
        assertTrue(row.getEventId() != null && !row.getEventId().isBlank());
        assertTrue(row.getCreatedAt() != null);
        VerifyEventDTO event = mapper.readValue(row.getPayload(), VerifyEventDTO.class);
        assertEquals(row.getEventId(), event.getEventId());
        assertEquals(1L, event.getRecordId());
        assertEquals(100L, event.getUserId());
        assertEquals(RecordVerifyEvents.EVENT_VERIFIED, event.getEventType());
    }

    /** 写侧：REJECTED → Tag=REJECTED，事件类型 REJECTED */
    @Test
    void newPendingRow_rejected_setsRejectedTag() throws Exception {
        VerifyEventOutbox row = producer.newPendingRow(Verdict.REJECTED, 2L, 200L);

        assertEquals(RecordVerifyEvents.TOPIC, row.getTopic());
        assertEquals(RecordVerifyEvents.TAG_REJECTED, row.getTag());
        VerifyEventDTO event = mapper.readValue(row.getPayload(), VerifyEventDTO.class);
        assertEquals(RecordVerifyEvents.EVENT_REJECTED, event.getEventType());
        assertEquals(2L, event.getRecordId());
    }

    /** 写侧：每次写入生成新 eventId（同一记录重复判定不共用幂等键） */
    @Test
    void newPendingRow_generatesFreshEventIdEachWrite() {
        String first = producer.newPendingRow(Verdict.PASSED, 1L, 100L).getEventId();
        String second = producer.newPendingRow(Verdict.PASSED, 1L, 100L).getEventId();

        assertTrue(!first.equals(second), "两次写入应生成不同 eventId");
    }

    /** 写侧：MDC 有 traceId 时随行落库；无 traceId 时留空（不写入 null 字符串） */
    @Test
    void newPendingRow_capturesTraceIdFromMdc() {
        TraceIds.put("trace-abc");
        assertEquals("trace-abc", producer.newPendingRow(Verdict.PASSED, 1L, 100L).getTraceId());

        TraceIds.clear();
        assertNull(producer.newPendingRow(Verdict.PASSED, 1L, 100L).getTraceId());
    }

    /** 投递侧：按行内 topic:tag 发送行内 payload，eventId 沿用行内值（重发不换 id），traceId 透传消息头 */
    @Test
    @SuppressWarnings("unchecked")
    void syncSend_usesRowTopicTagPayloadAndEventId() {
        VerifyEventOutbox row = producer.newPendingRow(Verdict.PASSED, 1L, 100L);
        row.setTraceId("trace-abc");
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);

        producer.syncSend(row);

        verify(rocketMQTemplate).syncSend(dest.capture(), msgCaptor.capture());
        assertEquals(RecordVerifyEvents.TOPIC + ":" + RecordVerifyEvents.TAG_VERIFIED, dest.getValue());
        String body = String.valueOf(msgCaptor.getValue().getPayload());
        assertEquals(row.getPayload(), body);
        assertTrue(body.contains(row.getEventId()), "投递体应含行内 eventId");
        assertEquals("trace-abc", msgCaptor.getValue().getHeaders().get(TraceIds.HEADER));
    }

    /** 投递侧：发送失败**抛出**（不再吞异常），交由 relay 记 retry_count 下轮重试 */
    @Test
    @SuppressWarnings("unchecked")
    void syncSend_sendFailure_propagates() {
        doThrow(new RuntimeException("MQ 不可用"))
                .when(rocketMQTemplate).syncSend(anyString(), any(Message.class));

        VerifyEventOutbox row = producer.newPendingRow(Verdict.PASSED, 3L, 300L);

        assertThrows(RuntimeException.class, () -> producer.syncSend(row));
    }
}
