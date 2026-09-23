package com.sportverify.leaderboard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.leaderboard.service.LeaderboardService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 榜单事件消费者单元测试（eventId SETNX 去重 / VERIFIED 入榜 / REJECTED 回滚 /
 * 未知类型跳过 / 失败交 MQ 原生重试）。
 *
 * <p>入榜/回滚的「结果幂等」（影响行数=0 不重复加/扣分）由 LeaderboardService
 * 内部的锚点行状态机保证，已在 LeaderboardServiceTest 覆盖；本用例聚焦消费者的
 * 去重、按 eventType 分发与「重试走 MQ 原生」分支。</p>
 *
 * <p>消费参数断言直接读 {@code buildConsumer(...)} 产出的**未启动**消费者实例：
 * 重试上限走 RocketMQ 原生 {@code maxReconsumeTimes}（超次由 broker 转
 * {@code %DLQ%leaderboard-consumer-group}），业务侧不再有自建计数键与自建死信投递。</p>
 */
class LeaderboardEventConsumerTest {

    private LeaderboardService leaderboardService;
    private RedissonClient redissonClient;
    private RBucket<Object> bucket;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private LeaderboardEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        leaderboardService = mock(LeaderboardService.class);
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        // 去双轨后消费者不再持有仅供自建 DLQ 使用的 RocketMQTemplate（构造参数随之收窄）
        consumer = new LeaderboardEventConsumer(leaderboardService, redissonClient,
                mapper, mock(RocketMQProperties.class));
        // 消费组名经 @Value 注入，单测手工补（buildConsumer 用它构造原生消费者）
        ReflectionTestUtils.setField(consumer, "consumerGroup", "leaderboard-consumer-group");
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

    /** 消费参数：重试上限由 MQ 原生承载（maxReconsumeTimes=3），不再自建计数 */
    @Test
    void buildConsumer_enablesNativeMaxReconsumeTimes3() throws Exception {
        DefaultMQPushConsumer c = consumer.buildConsumer("127.0.0.1:9876");

        assertEquals(3, c.getMaxReconsumeTimes());
    }

    /** 消费参数：订阅表达式仍覆盖 VERIFIED 与 REJECTED 两个 Tag（抽取观测缝不得改坏订阅） */
    @Test
    void buildConsumer_subscribesVerifiedAndRejectedTags() throws Exception {
        DefaultMQPushConsumer c = consumer.buildConsumer("127.0.0.1:9876");

        // 坑：DefaultMQPushConsumer#getSubscription() 读的是另一枚从未被 subscribe() 填充的字段（恒 null），
        // 订阅真身在 impl 的 rebalance 容器里，只能从 getSubscriptionInner() 读。
        SubscriptionData sub = c.getDefaultMQPushConsumerImpl()
                .getSubscriptionInner().get(RecordVerifyEvents.TOPIC);
        assertNotNull(sub);
        String expr = sub.getSubString();
        assertTrue(expr.contains(RecordVerifyEvents.TAG_VERIFIED), "订阅表达式缺 VERIFIED：" + expr);
        assertTrue(expr.contains(RecordVerifyEvents.TAG_REJECTED), "订阅表达式缺 REJECTED：" + expr);
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

    /** 分发失败 → 删除去重键放行重投并 rethrow；**不**查询自建重试计数键（去双轨） */
    @Test
    void handleMessage_failure_neverConsultsSelfBuiltRetryKey() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("入榜失败")).when(leaderboardService).applyVerified(1L);

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_VERIFIED)));

        verify(bucket).delete();
        verify(redissonClient, never()).getAtomicLong(anyString());
    }

    /** 即便自建计数键被塞到旧口径的「超阈值」（4），失败仍 rethrow 交 MQ 原生重试——那套轨道已删 */
    @Test
    void handleMessage_failureAfterSelfBuiltThreshold_rethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("回滚失败")).when(leaderboardService).rollbackOnRejected(1L);
        RAtomicLong legacyCounter = mock(RAtomicLong.class);
        when(legacyCounter.incrementAndGet()).thenReturn(4L); // 旧口径 count > 3 才进自建 DLQ
        when(redissonClient.getAtomicLong(anyString())).thenReturn(legacyCounter);

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_REJECTED)));
        verify(redissonClient, never()).getAtomicLong(anyString());
    }

    /** Redis 删除去重键异常 → 不阻断 rethrow */
    @Test
    void handleMessage_deleteDedupKeyRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("入榜失败")).when(leaderboardService).applyVerified(1L);
        doThrow(new RuntimeException("Redis 不可用")).when(bucket).delete();

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_VERIFIED)));
        verify(redissonClient, never()).getAtomicLong(anyString());
    }
}
