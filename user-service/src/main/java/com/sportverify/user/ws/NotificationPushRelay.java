package com.sportverify.user.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 通知推送跨实例扇出中继（TASK-185 add-notification-ws-push / TASK-186 add-notification-read-receipt）。
 *
 * <p>生命周期职责：本实例在启动时订阅 Redis pub/sub topic {@value #PUSH_TOPIC} 与
 * {@value #READ_TOPIC}；每个服务实例都收到同一广播，但 Spring 用户目标注册表只解析
 * 本实例持有的会话——当收件人的会话建立在其它实例时，本实例的
 * {@link SimpMessagingTemplate#convertAndSendToUser} 按「无会话用户不投递」的 Spring 语义
 * 静默跳过，机制性保证同一收件人不会双推。</p>
 *
 * <p>链路：生产侧（{@code NotificationService.createNotification} 落库成功后）调用
 * {@link #publish} 把 {@link NotificationPushMessage} 序列化为 JSON 发布到
 * {@code notification:push}；已读流转成功（{@code markRead} / {@code markAllRead} 影响行数 > 0）
 * 后调用 {@link #publishRead} 把 {@link NotificationReadReceipt} 序列化为 JSON 发布到
 * {@code notification:read}；本类收到后反序列化并分别经用户目标
 * {@code /queue/notifications} 与 {@code /queue/notification-read} 投递给在线会话。
 * 推送与回执均是尽力而为——发布/投递失败仅告警、不影响业务结果（前端 60s 轮询兜底）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushRelay {

    /** 通知推送扇出 topic（Redis pub/sub，跨实例广播） */
    public static final String PUSH_TOPIC = "notification:push";

    /** STOMP 用户目标队列后缀（挂在 {@code /user} 前缀下，投递路径为 /user/queue/notifications） */
    public static final String USER_QUEUE = "/queue/notifications";

    /** 通知已读回执扇出 topic（Redis pub/sub，跨实例广播，TASK-186） */
    public static final String READ_TOPIC = "notification:read";

    /** STOMP 已读回执用户目标队列后缀（投递路径为 /user/queue/notification-read，TASK-186） */
    public static final String READ_QUEUE = "/queue/notification-read";

    private final RedissonClient redissonClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private RTopic topic;
    private int listenerId;

    private RTopic readTopic;
    private int readListenerId;

    /** 启动时注册订阅：收到扇出 JSON 即反序列化并本地投递给在线会话（双订对称） */
    @PostConstruct
    public void subscribe() {
        topic = redissonClient.getTopic(PUSH_TOPIC, StringCodec.INSTANCE);
        listenerId = topic.addListener(String.class, (channel, payload) -> handle(payload));
        log.info("通知推送扇出订阅已注册：topic={}", PUSH_TOPIC);

        readTopic = redissonClient.getTopic(READ_TOPIC, StringCodec.INSTANCE);
        readListenerId = readTopic.addListener(String.class, (channel, payload) -> handleRead(payload));
        log.info("通知已读回执扇出订阅已注册：topic={}", READ_TOPIC);
    }

    /** 停机时退订（释放 Redis 订阅通道，双退对称） */
    @PreDestroy
    public void destroy() {
        if (topic != null) {
            topic.removeListener(listenerId);
            log.info("通知推送扇出订阅已退订：topic={}", PUSH_TOPIC);
        }
        if (readTopic != null) {
            readTopic.removeListener(readListenerId);
            log.info("通知已读回执扇出订阅已退订：topic={}", READ_TOPIC);
        }
    }

    /**
     * 发布通知推送（尽力而为）：把 {@link NotificationPushMessage} 序列化为 JSON 发到
     * 扇出 topic。失败仅告警、不抛出——推送是尽力而为，通知已落库，前端有轮询兜底。
     *
     * @param message 通知推送消息载荷
     */
    public void publish(NotificationPushMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            topic.publish(json);
        } catch (Exception e) {
            log.warn("通知推送发布失败（尽力而为，轮询兜底）：userId={}", message.userId(), e);
        }
    }

    /**
     * 发布通知已读回执（尽力而为，TASK-186）：把 {@link NotificationReadReceipt} 序列化为 JSON 发到
     * 扇出 topic。失败仅告警、不抛出——REST 仍是唯一权威变更通道，回执丢失退化为轮询兜底。
     *
     * @param receipt 通知已读回执载荷
     */
    public void publishRead(NotificationReadReceipt receipt) {
        if (receipt == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(receipt);
            readTopic.publish(json);
        } catch (Exception e) {
            log.warn("通知已读回执发布失败（尽力而为，轮询兜底）：userId={}, kind={}", receipt.userId(), receipt.kind(), e);
        }
    }

    /**
     * 处理扇出消息：反序列化后经用户目标队列投递给收件人在线会话。
     * 解析/投递失败仅告警并丢弃，不影响后续消息（成功落库的通知由前端轮询兜底呈现）。
     */
    void handle(String payload) {
        try {
            NotificationPushMessage message = objectMapper.readValue(payload, NotificationPushMessage.class);
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(message.userId()), USER_QUEUE, message);
            log.info("已向用户推送通知：userId={}, type={}", message.userId(), message.type());
        } catch (Exception e) {
            log.warn("通知推送消息解析/投递失败，丢弃：payload={}", payload, e);
        }
    }

    /**
     * 处理已读回执扇出消息（TASK-186）：反序列化后经用户目标队列投递给收件人在线会话。
     * 解析/投递失败或非法载荷仅告警并丢弃，不影响后续消息。
     */
    void handleRead(String payload) {
        try {
            NotificationReadReceipt receipt = objectMapper.readValue(payload, NotificationReadReceipt.class);
            if (receipt == null || receipt.userId() == null || receipt.kind() == null) {
                log.warn("通知已读回执载荷非法，丢弃：payload={}", payload);
                return;
            }
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(receipt.userId()), READ_QUEUE, receipt);
            log.info("已向用户投递已读回执：userId={}, kind={}, notificationId={}",
                    receipt.userId(), receipt.kind(), receipt.notificationId());
        } catch (Exception e) {
            log.warn("通知已读回执消息解析/投递失败，丢弃：payload={}", payload, e);
        }
    }
}
