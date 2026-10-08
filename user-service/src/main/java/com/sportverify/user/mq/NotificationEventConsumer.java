package com.sportverify.user.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.user.enums.NotificationType;
import com.sportverify.user.service.NotificationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 判定事件通知消费者（VERIFIED / REJECTED Tag，TASK-182 add-notification-center）。
 *
 * <p>消费范式逐字沿 {@code LeaderboardEventConsumer}（编程式 {@link DefaultMQPushConsumer}，
 * 规避 rocketmq-spring 2.3.1 监听容器不兼容点），独立消费组
 * {@code notification-consumer-group}：与 leaderboard 消费组同 Topic 不同组不同位点，
 * 组间隔离——本组消费失败不影响 leaderboard 组沉淀榜单。</p>
 *
 * <p>处理链路：eventId SETNX 去重 → 按 eventType 落通知（VERIFIED → RECORD_VERIFIED /
 * REJECTED → RECORD_REJECTED，收件人取 {@code VerifyEventDTO.userId}，不回查其他服务）。</p>
 * <ul>
 *   <li>幂等：Redis SETNX（eventId，24h TTL）+ 表级 {@code uk_dedup} 唯一键双保险
 *       （去重键过期后同 eventId 重复投递只落一条，由 NotificationService#createNotification 的
 *       INSERT IGNORE 受影响行数保证）；</li>
 *   <li>失败重试：返回 RECONSUME_LATER 交由 MQ 退避重投（并删除去重键放行）；重试上限由客户端
 *       原生 {@code maxReconsumeTimes=3} 承载，超次消息由 broker 转入
 *       {@code %DLQ%notification-consumer-group} 供人工排查——不自建重试计数、不自建死信 topic；</li>
 *   <li>解析失败：直接 ack 丢弃并告警（避免无限重试）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    /** 消费失败最大重投次数：超次由 broker 转入 {@code %DLQ%notification-consumer-group}（MQ 原生重试） */
    private static final int MAX_RECONSUME_TIMES = 3;
    /** 去重键 TTL（小时） */
    private static final long DEDUP_TTL_HOURS = 24;
    /** 订阅表达式：VERIFIED 记录通过 / REJECTED 记录驳回 */
    private static final String SUBSCRIBE_TAGS =
            RecordVerifyEvents.TAG_VERIFIED + " || " + RecordVerifyEvents.TAG_REJECTED;

    private final NotificationService notificationService;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    /** rocketmq-spring 绑定的连接配置（与生产端同源，避免手写占位符解析差异） */
    private final RocketMQProperties rocketMQProperties;

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;
    @Value("${rocketmq.notification.consumer.group:notification-consumer-group}")
    private String consumerGroup;

    private DefaultMQPushConsumer consumer;
    private ScheduledExecutorService reconnectScheduler;

    /** 启动时创建并启动消费者；namesrv 未就绪时进入后台自动重连（不阻断服务启动） */
    @PostConstruct
    public void init() {
        String ns = resolveNameServer();
        try {
            startConsumer(ns);
        } catch (Exception e) {
            // MQ 未就绪：服务照常启动；后台每 30s 重连，MQ 恢复后自动接管消息
            log.error("通知事件消费者启动失败（RocketMQ 未就绪？），进入后台重连：group={}, namesrv={}",
                    consumerGroup, ns, e);
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "notification-mq-reconnect");
                t.setDaemon(true);
                return t;
            });
            reconnectScheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (consumer == null) {
                        startConsumer(ns);
                        reconnectScheduler.shutdown();
                    }
                } catch (Exception ex) {
                    log.warn("通知消费者重连失败，30s 后重试：namesrv={}", ns);
                }
            }, 30, 30, TimeUnit.SECONDS);
        }
    }

    /** 解析 namesrv：优先 rocketmq-spring 绑定值，兜底 @Value 默认 */
    private String resolveNameServer() {
        String ns = rocketMQProperties.getNameServer();
        return (ns == null || ns.isBlank()) ? nameServer : ns;
    }

    /** 创建并启动消费者（可重复调用：失败时抛出由调用方决定重连策略） */
    private void startConsumer(String ns) throws Exception {
        DefaultMQPushConsumer c = buildConsumer(ns);
        c.start();
        this.consumer = c;
        log.info("通知事件消费者已启动：topic={}, tags={}, group={}, namesrv={}",
                RecordVerifyEvents.TOPIC, SUBSCRIBE_TAGS, consumerGroup, ns);
    }

    /**
     * 创建并配置消费者（**不启动**）。
     *
     * <p>拆出本方法只为给单测留一个可观测缝：消费参数（如 {@code getMaxReconsumeTimes()}）
     * 只能从实例上读，而 {@code start()} 需要真 namesrv、无法离线执行。配置顺序、订阅表达式与
     * 监听器注册与拆前逐字一致。</p>
     */
    DefaultMQPushConsumer buildConsumer(String ns) throws MQClientException {
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(consumerGroup);
        c.setNamesrvAddr(ns);
        // 重试上限走 broker 原生：超次自动进 %DLQ%notification-consumer-group，
        // 不自建重试计数（都是消费 4 次后入死信）
        c.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);
        c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        c.subscribe(RecordVerifyEvents.TOPIC, SUBSCRIBE_TAGS);
        c.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
                for (MessageExt msg : msgs) {
                    try {
                        handleMessage(msg);
                    } catch (Exception e) {
                        // 返回 RECONSUME_LATER 交由 MQ 按退避重投；超 maxReconsumeTimes 由 broker 转入 %DLQ%
                        log.error("消费通知事件失败，等待重投：msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        return c;
    }

    /** 关闭消费者与重连调度器（服务停机释放连接） */
    @PreDestroy
    public void destroy() {
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
        }
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    /** 单条消息处理：还原 traceId → 解析 → 去重 → 按事件类型落通知；失败时删去重键并抛出，交 MQ 原生重试 */
    private void handleMessage(MessageExt message) throws Exception {
        String traceId = TraceIds.resolveOrCreate(message.getUserProperty(TraceIds.HEADER));
        TraceIds.put(traceId);
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            VerifyEventDTO event;
            try {
                event = objectMapper.readValue(body, VerifyEventDTO.class);
            } catch (Exception e) {
                // 无法解析的消息直接 ack 丢弃并告警（避免无限重试）
                log.error("通知事件体解析失败，丢弃：{}", body, e);
                return;
            }
            try {
                // 1) 事件幂等：eventId SETNX，重复投递直接跳过
                RBucket<String> bucket = redissonClient.getBucket(dedupKey(event.getEventId()));
                boolean first = bucket.trySet("1", DEDUP_TTL_HOURS, TimeUnit.HOURS);
                if (!first) {
                    log.info("重复通知事件已消费过，跳过：eventId={}", event.getEventId());
                    return;
                }
                // 2) 按 eventType 落通知：VERIFIED → RECORD_VERIFIED / REJECTED → RECORD_REJECTED
                //    （收件人 = VerifyEventDTO.userId，不回查；表级 uk_dedup(dedup_key=eventId) 兜底幂等）
                log.info("消费通知事件：eventType={}, eventId={}, recordId={}, userId={}, traceId={}",
                        event.getEventType(), event.getEventId(), event.getRecordId(),
                        event.getUserId(), traceId);
                if (RecordVerifyEvents.EVENT_VERIFIED.equals(event.getEventType())) {
                    notificationService.createNotification(event.getUserId(),
                            NotificationType.RECORD_VERIFIED, event.getRecordId(),
                            "你的运动记录已通过校验", null, event.getEventId());
                } else if (RecordVerifyEvents.EVENT_REJECTED.equals(event.getEventType())) {
                    notificationService.createNotification(event.getUserId(),
                            NotificationType.RECORD_REJECTED, event.getRecordId(),
                            "你的运动记录未通过校验", null, event.getEventId());
                } else {
                    log.warn("未知通知事件类型，跳过：eventId={}, eventType={}",
                            event.getEventId(), event.getEventType());
                }
            } catch (Exception e) {
                // 3) 失败处理：删除去重键放行重投，抛出交由监听器返回 RECONSUME_LATER；
                // 重投与超次入死信（%DLQ%notification-consumer-group）由 MQ 原生重试承担
                deleteDedupKey(event.getEventId());
                throw new RuntimeException("通知事件消费失败，等待重试：recordId=" + event.getRecordId(), e);
            }
        } finally {
            TraceIds.clear();
        }
    }

    /** 去重键：notification:event:{eventId} */
    private String dedupKey(String eventId) {
        return "notification:event:" + eventId;
    }

    /** 删除去重键（失败后允许同 eventId 重投重试；Redis 异常不阻断主流程） */
    private void deleteDedupKey(String eventId) {
        try {
            redissonClient.getBucket(dedupKey(eventId)).delete();
        } catch (Exception ex) {
            log.warn("删除去重键失败（不影响重试，由表级 uk_dedup 幂等兜底）：eventId={}", eventId);
        }
    }
}