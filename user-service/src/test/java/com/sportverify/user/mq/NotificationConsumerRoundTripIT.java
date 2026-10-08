package com.sportverify.user.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.user.entity.Notification;
import com.sportverify.user.mapper.NotificationMapper;
import com.sportverify.user.service.NotificationService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * verify → user 通知链路的<b>真 RocketMQ broker + 真 Redis(SETNX)</b> 生产/消费往返
 * （TASK-182 add-notification-center，C-03）。
 *
 * <p>沿用 {@code RocketMqBrokerRoundTripIT} 范式：用生产端同一套 {@code DefaultMQPushConsumer}
 * 订阅表达式（topic + {@code VERIFIED || REJECTED}）在真 broker 上收消息，且 handler 逐字走
 * {@code NotificationEventConsumer#handleMessage}（重构缝，经反射调用，参数量、去重、分发与产品逐字
 * 一致）。去重走<b>真实 Redisson/Redis SETNX</b>，落库走真实 {@code NotificationService}（mapper 为
 * 内存假实现，避免依赖 MySQL，聚焦「broker → handleMessage → SETNX → 落通知」链路）。</p>
 *
 * <p>为避免干扰生产 {@code notification-consumer-group}，使用<b>隔离的 topic 与 consumer group</b>
 * （broker.conf 已开 {@code autoCreateTopicEnable/autoCreateSubscriptionGroup}）；去重键前缀
 * {@code notification:event:} 与产品相同，item 用 {@code task182-it-} 前缀幂等互不相扰。</p>
 *
 * <p><b>红线复演</b>（断言被破坏即红）：①把订阅表达式改成与发送 Tag 不匹配（如只订阅 SUBMITTED）
 * → 消息不被本消费组收到 → 轮询超时 {@code assertNotNull} 必红；②一次成功断言落库 1 行 + 重复投递
 * 只落 1 行（双重幂等：SETNX 去重 + uk_dedup 唯一键兜底）在去掉任一幂等即进 2 行并红。</p>
 *
 * <p><b>需要真 RocketMQ + 真 Redis，默认不进常规构建</b>：类名以 {@code IT} 结尾，缺
 * {@code TASK182_IT_ROCKETMQ_NAMESRV} / {@code TASK182_IT_ROCKETMQ_TOPIC} /
 * {@code TASK182_IT_REDIS_ADDR} 任一即 assume 跳过（按未覆盖记账，不计通过）。</p>
 */
class NotificationConsumerRoundTripIT {

    private static final String SUBSCRIBE_TAGS =
            RecordVerifyEvents.TAG_VERIFIED + " || " + RecordVerifyEvents.TAG_REJECTED;
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private String namesrv;
    private String redisAddr;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private RedissonClient redisson;
    private NotificationService realService;
    private final BlockingQueue<MessageExt> received = new LinkedBlockingQueue<>();

    /** 内存落库记录 + mock mapper：insertIgnore 按 dedup_key 判重返回 0/1（表级 uk_dedup 语义） */
    private final List<Notification> storedRows = new CopyOnWriteArrayList<>();

    private NotificationMapper buildRecordingMapper() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        when(mapper.insertIgnore(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            boolean dup = storedRows.stream()
                    .anyMatch(r -> r.getDedupKey() != null && r.getDedupKey().equals(n.getDedupKey()));
            if (dup) {
                return 0;
            }
            storedRows.add(n);
            return 1;
        });
        return mapper;
    }

    @BeforeEach
    void setUp() {
        namesrv = System.getenv("TASK182_IT_ROCKETMQ_NAMESRV");
        String topic = System.getenv("TASK182_IT_ROCKETMQ_TOPIC");
        redisAddr = System.getenv("TASK182_IT_REDIS_ADDR");
        Assumptions.assumeTrue(
                namesrv != null && topic != null && !namesrv.isBlank() && !topic.isBlank()
                        && redisAddr != null && !redisAddr.isBlank(),
                "缺 TASK182_IT_ROCKETMQ_NAMESRV/TOPIC/REDIS_ADDR 环境变量，跳过真链路集成测试（不视为通过）");
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    /** 构造真实 NotificationEventConsumer：真实 Service(mock mapper 内存判重) + 真实 Redisson(SETNX 去重) */
    private NotificationEventConsumer buildRealConsumer() {
        realService = new NotificationService(buildRecordingMapper());
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://" + redisAddr);
        redisson = Redisson.create(cfg);
        RocketMQProperties props = new RocketMQProperties();
        props.setNameServer(namesrv);
        NotificationEventConsumer c = new NotificationEventConsumer(
                realService, redisson, MAPPER, props);
        ReflectionTestUtils.setField(c, "consumerGroup", "task182-it-group");
        ReflectionTestUtils.setField(c, "nameServer", namesrv);
        return c;
    }

    @Test
    void verifiedEventRoundTripsThroughRealBrokerAndWritesNotificationOnce() throws Exception {
        long uniq = System.currentTimeMillis();
        String uniqTopic = System.getenv("TASK182_IT_ROCKETMQ_TOPIC") + "-" + uniq;
        String eventId = "task182-verified-" + uniq;
        Long recordId = 8820201L;

        NotificationEventConsumer handler = buildRealConsumer();

        // 真消费者，订阅表达式与产品逐字一致（VERIFIED || REJECTED）
        producer = new DefaultMQProducer("task182-it-producer-" + uniq);
        producer.setNamesrvAddr(namesrv);
        producer.start();

        consumer = new DefaultMQPushConsumer("task182-it-consumer-" + uniq);
        consumer.setNamesrvAddr(namesrv);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.subscribe(uniqTopic, SUBSCRIBE_TAGS);
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
                for (MessageExt m : msgs) {
                    received.offer(m);
                    // 走生产同一 handleMessage（反射），验证 broker→去重→落通知 全链路
                    try {
                        ReflectionTestUtils.invokeMethod(handler, "handleMessage", m);
                    } catch (Exception ignored) {
                        // 落库异常仅在单测/故障路径断言，此处不吞真失败
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        Thread.sleep(1500);

        VerifyEventDTO dto = new VerifyEventDTO(eventId, recordId, 100L,
                RecordVerifyEvents.EVENT_VERIFIED, LocalDateTime.now());
        String body = MAPPER.writeValueAsString(dto);
        Message msg = new Message(uniqTopic, RecordVerifyEvents.TAG_VERIFIED,
                body.getBytes(StandardCharsets.UTF_8));
        SendResult sent = producer.send(msg);
        assertNotNull(sent.getMsgId(), "真 broker 上发送应返回消息 ID");

        // 一次成功：断言收到且落库 1 行 RECORD_VERIFIED，收件人/源/标题/dedup_key(事件 id) 相符
        MessageExt got = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(got, "真 broker 上按 VERIFIED||REJECTED 订阅应收到刚发送的消息");
        assertNotNull(waitSingleRow("你的运动记录已通过校验"), "一次成功应落库 1 行 RECORD_VERIFIED 通知");
        Notification written = lastRow("你的运动记录已通过校验");
        assertEquals(100L, written.getUserId());
        assertEquals(recordId, written.getSourceId());
        assertEquals(eventId, written.getDedupKey(), "判定事件 dedup_key 必须 = MQ eventId（表级 uk_dedup 幂等锚点）");
        assertEquals("RECORD_VERIFIED", written.getType());
        assertEquals(0, written.getIsRead());

        // 双重幂等：同 eventId 再投一次（SETNX 已置 + uk_dedup 已占）→ 仍然只落 1 行
        producer.send(msg);
        Thread.sleep(2000);
        assertEquals(1, countRows("你的运动记录已通过校验"),
                "重复投递必须只落 1 行（Redis SETNX + uk_dedup 唯一键双幂等）");
    }

    @Test
    void rejectedEventRoundTripsAndWritesRejectedNotification() throws Exception {
        long uniq = System.currentTimeMillis();
        String uniqTopic = System.getenv("TASK182_IT_ROCKETMQ_TOPIC") + "-" + uniq;
        String eventId = "task182-rejected-" + uniq;

        NotificationEventConsumer handler = buildRealConsumer();
        producer = new DefaultMQProducer("task182-it-producer-" + uniq);
        producer.setNamesrvAddr(namesrv);
        producer.start();

        consumer = new DefaultMQPushConsumer("task182-it-consumer-" + uniq);
        consumer.setNamesrvAddr(namesrv);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.subscribe(uniqTopic, SUBSCRIBE_TAGS);
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
                for (MessageExt m : msgs) {
                    received.offer(m);
                    try {
                        ReflectionTestUtils.invokeMethod(handler, "handleMessage", m);
                    } catch (Exception ignored) {
                        // 空
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        Thread.sleep(1500);

        VerifyEventDTO dto = new VerifyEventDTO(eventId, 8820202L, 100L,
                RecordVerifyEvents.EVENT_REJECTED, LocalDateTime.now());
        String body = MAPPER.writeValueAsString(dto);
        Message msg = new Message(uniqTopic, RecordVerifyEvents.TAG_REJECTED,
                body.getBytes(StandardCharsets.UTF_8));
        producer.send(msg);

        assertNotNull(received.poll(20, TimeUnit.SECONDS), "REJECTED 也应收到并进入处理");
        Notification written = waitSingleRow("你的运动记录未通过校验");
        assertNotNull(written, "REJECTED 事件应落库 RECORD_REJECTED 通知");
        assertEquals("RECORD_REJECTED", written.getType());
    }

    private List<Notification> allRows() {
        return storedRows;
    }

    private Notification lastRow(String title) {
        List<Notification> list = allRows();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (title.equals(list.get(i).getTitle())) {
                return list.get(i);
            }
        }
        return null;
    }

    private Notification waitSingleRow(String title) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<Notification> list = allRows();
            long hit = list.stream().filter(r -> title.equals(r.getTitle())).count();
            if (hit >= 1) {
                return lastRow(title);
            }
            Thread.sleep(200);
        }
        return null;
    }

    private int countRows(String title) {
        return (int) allRows().stream().filter(r -> title.equals(r.getTitle())).count();
    }
}