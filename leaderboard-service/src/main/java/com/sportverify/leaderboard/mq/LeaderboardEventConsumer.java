package com.sportverify.leaderboard.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.leaderboard.service.LeaderboardService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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
 * 榜单事件消费者（VERIFIED / REJECTED Tag，规范差异「事件驱动入榜」「改判回滚」「事件幂等」）。
 *
 * <p>与 verify-service 的 SUBMITTED 消费者同构（编程式 {@link DefaultMQPushConsumer}，
 * 规避 rocketmq-spring 2.3.1 监听容器与离线锁定 rocketmq-client 5.1.4 的不兼容点），
 * 但独立消费组 {@code leaderboard-consumer-group}：同一 Topic 按 Tag 订阅多条支路，
 * verify 消费触发链路、leaderboard 消费沉淀榜单，互不影响位点。
 * 本消费者随榜单职责整体迁自 record-service（服务数 4→5，见 ADR-0005）；
 * record 侧原消费者已同步下线——同一时刻仅本服务订阅沉淀榜单，避免双写。</p>
 *
 * <p>处理链路：eventId SETNX 去重 → 按 eventType 分发（VERIFIED 入榜 / REJECTED 回滚）。</p>
 * <ul>
 *   <li>幂等：Redis SETNX（eventId，24h TTL）+ 业务层锚点行状态机双保险
 *       （重复投递即使去重键过期，INSERT IGNORE/乐观 UPDATE 也保证只加/扣一次）；</li>
 *   <li>失败重试：返回 RECONSUME_LATER 交由 MQ 退避重投（并删除去重键放行）；
 *       重试超阈值投递 {@code record-verify-events-dlq} 死信队列供人工排查。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderboardEventConsumer {

    /** 消费失败最大重试次数：超过后投递死信队列 */
    private static final int MAX_RETRY = 3;
    /** 去重键 TTL（小时） */
    private static final long DEDUP_TTL_HOURS = 24;
    /** 订阅表达式：VERIFIED 入榜 / REJECTED 回滚（含改判驳回 REVERSED 语义，见 RecordVerifyEvents） */
    private static final String SUBSCRIBE_TAGS =
            RecordVerifyEvents.TAG_VERIFIED + " || " + RecordVerifyEvents.TAG_REJECTED;

    private final LeaderboardService leaderboardService;
    private final RedissonClient redissonClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    /** rocketmq-spring 绑定的连接配置（与生产端同源，避免手写占位符解析差异） */
    private final RocketMQProperties rocketMQProperties;

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;
    @Value("${rocketmq.leaderboard.consumer.group:leaderboard-consumer-group}")
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
            log.error("榜单事件消费者启动失败（RocketMQ 未就绪？），进入后台重连：group={}, namesrv={}",
                    consumerGroup, ns, e);
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "leaderboard-mq-reconnect");
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
                    log.warn("榜单消费者重连失败，30s 后重试：namesrv={}", ns);
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
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(consumerGroup);
        c.setNamesrvAddr(ns);
        c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        c.subscribe(RecordVerifyEvents.TOPIC, SUBSCRIBE_TAGS);
        c.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
                for (MessageExt msg : msgs) {
                    try {
                        handleMessage(msg);
                    } catch (Exception e) {
                        // 未超重试阈值：返回 RECONSUME_LATER 由 MQ 按退避重投
                        log.error("消费榜单事件失败，等待重投：msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        c.start();
        this.consumer = c;
        log.info("榜单事件消费者已启动：topic={}, tags={}, group={}, namesrv={}",
                RecordVerifyEvents.TOPIC, SUBSCRIBE_TAGS, consumerGroup, ns);
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

    /** 单条消息处理：还原 traceId → 解析 → 去重 → 按事件类型分发；失败时按重试计数决定重投或进 DLQ */
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
                log.error("榜单事件体解析失败，丢弃：{}", body, e);
                return;
            }
            try {
                // 1) 事件幂等：eventId SETNX，重复投递直接跳过（规范差异「事件幂等」）
                RBucket<String> bucket = redissonClient.getBucket(dedupKey(event.getEventId()));
                boolean first = bucket.trySet("1", DEDUP_TTL_HOURS, TimeUnit.HOURS);
                if (!first) {
                    log.info("重复榜单事件已消费过，跳过：eventId={}", event.getEventId());
                    return;
                }
                // 2) 按 eventType 分发：VERIFIED 入榜 / REJECTED 回滚（锚点行状态机兜底幂等）
                log.info("消费榜单事件：eventType={}, eventId={}, recordId={}, traceId={}",
                        event.getEventType(), event.getEventId(), event.getRecordId(), traceId);
                if (RecordVerifyEvents.EVENT_VERIFIED.equals(event.getEventType())) {
                    leaderboardService.applyVerified(event.getRecordId());
                } else if (RecordVerifyEvents.EVENT_REJECTED.equals(event.getEventType())) {
                    leaderboardService.rollbackOnRejected(event.getRecordId());
                } else {
                    log.warn("未知榜单事件类型，跳过：eventId={}, eventType={}",
                            event.getEventId(), event.getEventType());
                }
            } catch (Exception e) {
                // 3) 失败处理：删除去重键放行重投；超阈值投递死信队列（规范「失败进死信」）
                deleteDedupKey(event.getEventId());
                if (markRetryAndExceed(event.getEventId())) {
                    sendToDlq(event, message, e);
                    return; // 已进 DLQ，视为处理完成，避免无限重试
                }
                throw new RuntimeException("榜单事件消费失败，等待重试：recordId=" + event.getRecordId(), e);
            }
        } finally {
            TraceIds.clear();
        }
    }

    /** 去重键：leaderboard:event:{eventId} */
    private String dedupKey(String eventId) {
        return "leaderboard:event:" + eventId;
    }

    /** 重试计数键：leaderboard:retry:{eventId} */
    private String retryKey(String eventId) {
        return "leaderboard:retry:" + eventId;
    }

    /** 删除去重键（失败后允许同 eventId 重投重试；Redis 异常不阻断主流程） */
    private void deleteDedupKey(String eventId) {
        try {
            redissonClient.getBucket(dedupKey(eventId)).delete();
        } catch (Exception ex) {
            log.warn("删除去重键失败（不影响重试，由锚点行状态机幂等兜底）：eventId={}", eventId);
        }
    }

    /** 重试计数自增，返回是否已超阈值（Redis 异常按未超阈值处理，继续 MQ 重试） */
    private boolean markRetryAndExceed(String eventId) {
        try {
            long count = redissonClient.getAtomicLong(retryKey(eventId)).incrementAndGet();
            return count > MAX_RETRY;
        } catch (Exception ex) {
            log.warn("重试计数失败：eventId={}", eventId);
            return false;
        }
    }

    /** 投递死信队列供人工排查（规范「失败进死信」）；失败仅告警不阻断 */
    private void sendToDlq(VerifyEventDTO event, MessageExt message, Exception cause) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            rocketMQTemplate.syncSend(RecordVerifyEvents.DLQ_TOPIC, body);
            log.error("榜单消费重试超阈值，已投递死信队列 {}：eventId={}, recordId={}, cause={}",
                    RecordVerifyEvents.DLQ_TOPIC, event.getEventId(), event.getRecordId(), cause.getMessage());
        } catch (Exception ex) {
            log.error("投递死信队列失败：eventId={}, msgId={}", event.getEventId(), message.getMsgId(), ex);
        }
    }
}
