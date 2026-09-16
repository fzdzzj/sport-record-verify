package com.sportverify.leaderboard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.leaderboard.service.LeaderboardService;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 榜单事件消费者单元测试（eventId SETNX 去重 / VERIFIED 入榜 / REJECTED 回滚 /
 * 未知类型跳过 / 失败重试 / 超阈值进死信）。
 *
 * <p>入榜/回滚的「结果幂等」（影响行数=0 不重复加/扣分）由 LeaderboardService
 * 内部的锚点行状态机保证，已在 LeaderboardServiceTest 覆盖；本用例聚焦消费者的
 * 去重与按 eventType 分发分支。</p>
 */
class LeaderboardEventConsumerTest {

    private LeaderboardService leaderboardService;
    private RedissonClient redissonClient;
    private RocketMQTemplate rocketMQTemplate;
    private RBucket<Object> bucket;
    private RAtomicLong retryCounter;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private LeaderboardEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        leaderboardService = mock(LeaderboardService.class);
        redissonClient = mock(RedissonClient.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        bucket = mock(RBucket.class);
        retryCounter = mock(RAtomicLong.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(retryCounter);
        consumer = new LeaderboardEventConsumer(leaderboardService, redissonClient, rocketMQTemplate,
                mapper, mock(RocketMQProperties.class));
    }

    private MessageExt message(String eventType) throws Exception {
        String body = mapper.writeValueAsString(
                new VerifyEventDTO("evt-x", 1L, 100L, eventType, LocalDateTime.now()));
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(msg.getMsgId()).thenReturn("msg-x");
        when(msg.getUserProperty(anyString())).thenReturn(null);
        return msg;
    }

    /** 事件幂等：eventId SETNX 返回 false（重复投递）→ 直接跳过，不触发入榜/回滚 */
    @Test
    void handleMessage_duplicateEvent_skips() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message(RecordVerifyEvents.EVENT_VERIFIED));

        verify(leaderboardService, never()).applyVerified(anyLong());
        verify(leaderboardService, never()).rollbackOnRejected(anyLong());
    }

    /** VERIFIED 事件 → 入榜 */
    @Test
    void handleMessage_verified_applyVerified() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message(RecordVerifyEvents.EVENT_VERIFIED));

        verify(leaderboardService).applyVerified(1L);
        verify(leaderboardService, never()).rollbackOnRejected(anyLong());
    }

    /** REJECTED 事件 → 回滚 */
    @Test
    void handleMessage_rejected_rollback() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message(RecordVerifyEvents.EVENT_REJECTED));

        verify(leaderboardService).rollbackOnRejected(1L);
        verify(leaderboardService, never()).applyVerified(anyLong());
    }

    /** 未知事件类型 → 仅告警跳过，两条分支都不触发 */
    @Test
    void handleMessage_unknownType_skips() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message("UNKNOWN"));

        verify(leaderboardService, never()).applyVerified(anyLong());
        verify(leaderboardService, never()).rollbackOnRejected(anyLong());
    }

    /** 分发失败未超重试阈值 → 抛出让 MQ 重投，不进死信 */
    @Test
    void handleMessage_retryNotExceeded_rethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("入榜失败")).when(leaderboardService).applyVerified(1L);
        when(retryCounter.incrementAndGet()).thenReturn(1L);

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message(RecordVerifyEvents.EVENT_VERIFIED)));

        verify(bucket).delete();
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    /** 重试计数超阈值 → 投递死信队列，视为处理完成 */
    @Test
    void handleMessage_retryExceeded_sendsToDlq() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("回滚失败")).when(leaderboardService).rollbackOnRejected(1L);
        when(retryCounter.incrementAndGet()).thenReturn(4L);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message(RecordVerifyEvents.EVENT_REJECTED));

        verify(bucket).delete();
        verify(rocketMQTemplate).syncSend(eq(RecordVerifyEvents.DLQ_TOPIC), anyString());
    }

    /** 投递死信队列失败 → 仅告警，不抛出 */
    @Test
    void handleMessage_dlqSendFailure_swallows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("回滚失败")).when(leaderboardService).rollbackOnRejected(1L);
        when(retryCounter.incrementAndGet()).thenReturn(4L);
        doThrow(new RuntimeException("MQ 写入失败")).when(rocketMQTemplate).syncSend(anyString(), anyString());

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message(RecordVerifyEvents.EVENT_REJECTED));
    }

    /** Redis 重试计数异常 → 视为未超阈值，rethrow 等待 MQ 重投 */
    @Test
    void handleMessage_retryCounterRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("入榜失败")).when(leaderboardService).applyVerified(1L);
        when(retryCounter.incrementAndGet()).thenThrow(new RuntimeException("Redis 不可用"));

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_VERIFIED)));
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    /** Redis 删除去重键异常 → 不阻断 rethrow */
    @Test
    void handleMessage_deleteDedupKeyRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("入榜失败")).when(leaderboardService).applyVerified(1L);
        when(retryCounter.incrementAndGet()).thenReturn(1L);
        doThrow(new RuntimeException("Redis 不可用")).when(bucket).delete();

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_VERIFIED)));
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }
}