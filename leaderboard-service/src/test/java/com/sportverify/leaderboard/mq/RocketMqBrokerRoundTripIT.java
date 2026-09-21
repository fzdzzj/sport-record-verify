package com.sportverify.leaderboard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.producer.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * verify → leaderboard 事件链路的<b>真 RocketMQ broker</b> 生产/消费往返（TASK-110 事项 B）。
 *
 * <p>既有的 {@code VerifyOutboxRelayTest} / {@code LeaderboardEventConsumerTest} 都把生产与消费
 * 打到 mock 上，从未经过真实 broker；本类用生产端同一套 {@code DefaultMQProducer} 与消费端同一套
 * {@code DefaultMQPushConsumer}（topic + {@code VERIFIED || REJECTED} 订阅），在真实
 * {@code sport-verify-rocketmq-namesrv} 上完成发一条、收一条并断言体与 Tag 的往返。</p>
 *
 * <p>为避免干扰生产 {@code leaderboard-consumer-group}，本类使用<b>隔离的 topic 与 consumer group</b>
 * （broker.conf 已开 {@code autoCreateTopicEnable / autoCreateSubscriptionGroup}，topic 随发随建），
 * 但走的是完全相同的真 producer / 真 consumer / 真广播路由路径。消息体沿用 {@link VerifyEventDTO}。</p>
 *
 * <p><b>红线复演</b>（断言被破坏即红）：把 {@code consumer.subscribe(topic, SUBSCRIBE_TAGS)} 的订阅
 * 表达式临时改成与发送 Tag 不匹配的值（如只订阅 {@code SUBMITTED}）→ 消息不被本消费组收到 → 轮询超时、
 * {@code assertNotNull} 必红（断言消息已交付）；还原即绿。</p>
 *
 * <p><b>需要真 RocketMQ，默认不进常规构建</b>：类名以 {@code IT} 结尾，缺
 * {@code TASK110_IT_ROCKETMQ_NAMESRV} / {@code TASK110_IT_ROCKETMQ_TOPIC} 任一即 assume 跳过
 * （按未覆盖记账，不计通过）。</p>
 */
class RocketMqBrokerRoundTripIT {

    private static final String SUBSCRIBE_TAGS =
            RecordVerifyEvents.TAG_VERIFIED + " || " + RecordVerifyEvents.TAG_REJECTED;
    /** {@link VerifyEventDTO#getOccurredAt()} 是 {@link LocalDateTime}，需注册 jsr310 模块才能序列化 */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private String namesrv;
    private String topic;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private final BlockingQueue<MessageExt> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        namesrv = System.getenv("TASK110_IT_ROCKETMQ_NAMESRV");
        topic = System.getenv("TASK110_IT_ROCKETMQ_TOPIC");
        Assumptions.assumeTrue(namesrv != null && topic != null && !namesrv.isBlank() && !topic.isBlank(),
                "缺 TASK110_IT_ROCKETMQ_NAMESRV/TOPIC 环境变量，跳过真 RocketMQ 集成测试（不视为通过）");
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }

    @Test
    void verifiedEventRoundTripsThroughRealBroker() throws Exception {
        long uniq = System.currentTimeMillis();
        // 每次运行用独立 topic + consumer group，避免上一轮残留消息被 LAST_OFFSET 竞态拉到（假红）
        String topicUniq = topic + "-" + uniq;
        String group = "task110-it-" + uniq;
        String eventId = "task110-verified-" + uniq;
        Long recordId = 9900201L;

        producer = new DefaultMQProducer("task110-it-producer-" + uniq);
        producer.setNamesrvAddr(namesrv);
        producer.start();

        consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(namesrv);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.subscribe(topicUniq, SUBSCRIBE_TAGS);
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
                for (MessageExt m : msgs) {
                    received.offer(m);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        // 给消费组注册 + 路由广播一点时间，避免极端下收发竞态
        Thread.sleep(1500);

        VerifyEventDTO dto = new VerifyEventDTO(eventId, recordId, 100L,
                RecordVerifyEvents.EVENT_VERIFIED, LocalDateTime.now());
        String body = MAPPER.writeValueAsString(dto);

        Message msg = new Message(topicUniq, RecordVerifyEvents.TAG_VERIFIED, body.getBytes(StandardCharsets.UTF_8));
        SendResult sent = producer.send(msg);
        assertNotNull(sent.getMsgId(), "真 broker 上发送应返回消息 ID");

        MessageExt got = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(got, "真 broker 上按 VERIFIED||REJECTED 订阅应收到刚发送的消息");

        assertEquals(RecordVerifyEvents.TAG_VERIFIED, got.getTags(), "消费端应命中发方指定的 Tag");
        assertEquals(body, new String(got.getBody(), StandardCharsets.UTF_8), "消息体边到边字节一致");

        VerifyEventDTO back = MAPPER.readValue(got.getBody(), VerifyEventDTO.class);
        assertEquals(eventId, back.getEventId(), "往返后 eventId 必须一致（消费幂等锚点）");
        assertEquals(recordId, back.getRecordId(), "往返后 recordId 必须一致");
        assertEquals(RecordVerifyEvents.EVENT_VERIFIED, back.getEventType(), "往返后事件类型必须一致");
    }
}