package com.sportverify.verify.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.verify.service.VerifyService;
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
 * 校验事件消费者（SUBMITTED Tag，规范「触发校验事件」「事件幂等消费」「失败进死信」）。
 *
 * <p>采用 rocketmq-client 原生 {@link DefaultMQPushConsumer} 编程式订阅：rocketmq-spring 2.3.1
 * 的监听容器依赖 rocketmq-client 5.3.0 才新增的 {@code setNamespaceV2}，而本项目离线依赖
 * 锁定为 rocketmq-client 5.1.4，故绕开该容器初始化路径；生产端仍使用 rocketmq-spring 的
 * RocketMQTemplate（不涉及该不兼容点）。</p>
 *
 * <p>处理链路：eventId SETNX 去重 → verify 拉轨迹判定 → 回调 record 迁移状态。</p>
 * <ul>
 *   <li>幂等：Redis SETNX（eventId，24h TTL）；业务内再以 verification_result 主键兜底；</li>
 *   <li>失败重试：返回 RECONSUME_LATER 交由 MQ 按退避重投（并删除去重键放行）；重试超阈值
 *       投递 {@code record-verify-events-dlq} 死信队列供人工排查。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyEventConsumer {

    /** 消费失败最大重试次数：超过后投递死信队列 */
    private static final int MAX_RETRY = 3;
    /** 去重键 TTL（小时） */
    private static final long DEDUP_TTL_HOURS = 24;

    private final VerifyService verifyService;
    private final RedissonClient redissonClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    /** rocketmq-spring 绑定的连接配置（与生产端同源，避免手写占位符解析差异） */
    private final RocketMQProperties rocketMQProperties;

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;
    @Value("${rocketmq.consumer.group:verify-consumer-group}")
    private String consumerGroup;

    // ---- 消费端调度参数（突发 P95 调优，压测报告 §4.2/§10；库默认：22/20/1/0） ----
    /** 消费线程池最小线程数 */
    @Value("${rocketmq.consumer.consume-thread-min:20}")
    private int consumeThreadMin;
    /** 消费线程池最大线程数：突发下太小会使尾部消息排队等待批次调度 */
    @Value("${rocketmq.consumer.consume-thread-max:20}")
    private int consumeThreadMax;
    /** 单批消费最大条数：批次偏大会放大尾部等待 */
    @Value("${rocketmq.consumer.consume-message-batch-max-size:1}")
    private int consumeMessageBatchMaxSize;
    /** 拉取长轮询间隔（毫秒），0=由 broker 推送（默认行为） */
    @Value("${rocketmq.consumer.pull-interval-ms:0}")
    private long pullIntervalMs;

    private DefaultMQPushConsumer consumer;
    private ScheduledExecutorService reconnectScheduler;

    /** 启动时创建并启动消费者；namesrv 未就绪时进入后台自动重连（不阻断服务启动） */
    @PostConstruct
    public void init() {
        String ns = resolveNameServer();
        try {
            startConsumer(ns);
        } catch (Exception e) {
            // MQ 未就绪：服务照常启动，校验改由 record 侧 Feign 直调降级兜底；
            // 后台每 30s 重连，MQ 恢复后自动接管消息
            log.error("SUBMITTED 事件消费者启动失败（RocketMQ 未就绪？），进入后台重连：group={}, namesrv={}",
                    consumerGroup, ns, e);
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "verify-mq-reconnect");
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
                    log.warn("消费者重连失败，30s 后重试：namesrv={}", ns);
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
        // 消费端调度参数（突发 P95 调优，压测报告 §4.2/§10）：改配置即生效，无需改业务逻辑
        c.setConsumeThreadMin(consumeThreadMin);
        c.setConsumeThreadMax(consumeThreadMax);
        c.setConsumeMessageBatchMaxSize(consumeMessageBatchMaxSize);
        c.setPullInterval(pullIntervalMs);
        c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        c.subscribe(RecordVerifyEvents.TOPIC, RecordVerifyEvents.TAG_SUBMITTED);
        c.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
                for (MessageExt msg : msgs) {
                    try {
                        handleMessage(msg);
                    } catch (Exception e) {
                        // 未超重试阈值：返回 RECONSUME_LATER 由 MQ 按退避重投
                        log.error("消费 SUBMITTED 事件失败，等待重投：msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        c.start();
        this.consumer = c;
        log.info("SUBMITTED 事件消费者已启动：topic={}, tag={}, group={}, namesrv={}, " +
                        "consumeThread={}/{} batchMaxSize={} pullIntervalMs={}",
                RecordVerifyEvents.TOPIC, RecordVerifyEvents.TAG_SUBMITTED, consumerGroup, ns,
                consumeThreadMin, consumeThreadMax, consumeMessageBatchMaxSize, pullIntervalMs);
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

    /** 单条消息处理：解析 → 去重 → 校验；失败时按重试计数决定重投或进 DLQ */
    private void handleMessage(MessageExt message) throws Exception {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        VerifyEventDTO event;
        try {
            event = objectMapper.readValue(body, VerifyEventDTO.class);
        } catch (Exception e) {
            // 无法解析的消息直接 ack 丢弃并告警（避免无限重试）
            log.error("事件体解析失败，丢弃：{}", body, e);
            return;
        }
        try {
            // 1) 事件幂等：eventId SETNX，重复投递直接跳过（规范「事件幂等消费」）
            RBucket<String> bucket = redissonClient.getBucket(dedupKey(event.getEventId()));
            boolean first = bucket.trySet("1", DEDUP_TTL_HOURS, TimeUnit.HOURS);
            if (!first) {
                log.info("重复事件已消费过，跳过：eventId={}", event.getEventId());
                return;
            }
            // 2) 业务执行：拉轨迹 → 预处理+R1-R4 → 判定落库 → 回调（recordId 幂等兜底）
            verifyService.verify(event.getRecordId());
        } catch (Exception e) {
            // 3) 失败处理：删除去重键放行重投；超阈值投递死信队列（规范「失败进死信」）
            deleteDedupKey(event.getEventId());
            if (markRetryAndExceed(event.getEventId())) {
                sendToDlq(event, message, e);
                return; // 已进 DLQ，视为处理完成，避免无限重试
            }
            throw new RuntimeException("校验消费失败，等待重试：recordId=" + event.getRecordId(), e);
        }
    }

    /** 去重键：verify:event:{eventId} */
    private String dedupKey(String eventId) {
        return "verify:event:" + eventId;
    }

    /** 重试计数键：verify:retry:{eventId} */
    private String retryKey(String eventId) {
        return "verify:retry:" + eventId;
    }

    /** 删除去重键（失败后允许同 eventId 重投重试；Redis 异常不阻断主流程） */
    private void deleteDedupKey(String eventId) {
        try {
            redissonClient.getBucket(dedupKey(eventId)).delete();
        } catch (Exception ex) {
            log.warn("删除去重键失败（不影响重试，由 recordId 幂等兜底）：eventId={}", eventId);
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
            log.error("消费重试超阈值，已投递死信队列 {}：eventId={}, recordId={}, cause={}",
                    RecordVerifyEvents.DLQ_TOPIC, event.getEventId(), event.getRecordId(), cause.getMessage());
        } catch (Exception ex) {
            log.error("投递死信队列失败：eventId={}, msgId={}", event.getEventId(), message.getMsgId(), ex);
        }
    }
}
