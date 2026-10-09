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
 * 通知推送跨实例扇出中继（TASK-185 add-notification-ws-push）。
 *
 * <p>生命周期职责：本实例在启动时订阅 Redis pub/sub topic {@value #PUSH_TOPIC}；每个
 * 服务实例都收到同一广播，但 Spring 用户目标注册表只解析本实例持有的会话——当收件人的
 * 会话建立在其它实例时，本实例的 {@link SimpMessagingTemplate#convertAndSendToUser}
 * 按「无会话用户不投递」的 Spring 语义静默跳过，机制性保证同一收件人不会双推。</p>
 *
 * <p>链路：生产侧（{@code NotificationService.createNotification} 落库成功后）调用
 * {@link #publish} 把 {@link NotificationPushMessage} 序列化为 JSON 发布到
 * {@code notification:push}；本类 {@link #handle} 收到后反序列化并经用户目标
 * {@code /queue/notifications} 投递给在线会话。推送是尽力而为——发布/投递失败仅告警、
 * 不影响通知落库（前端 60s 轮询兜底，见任务书 §2.1）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushRelay {

    /** 通知推送扇出 topic（Redis pub/sub，跨实例广播） */
    public static final String PUSH_TOPIC = "notification:push";

    /** STOMP 用户目标队列后缀（挂在 {@code /user} 前缀下，投递路径为 /user/queue/notifications） */
    public static final String USER_QUEUE = "/queue/notifications";

    private final RedissonClient redissonClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private RTopic topic;
    private int listenerId;

    /** 启动时注册订阅：收到扇出 JSON 即反序列化并本地投递给在线会话 */
    @PostConstruct
    public void subscribe() {
        topic = redissonClient.getTopic(PUSH_TOPIC, StringCodec.INSTANCE);
        listenerId = topic.addListener(String.class, (channel, payload) -> handle(payload));
        log.info("通知推送扇出订阅已注册：topic={}", PUSH_TOPIC);
    }

    /** 停机时退订（释放 Redis 订阅通道） */
    @PreDestroy
    public void destroy() {
        if (topic != null) {
            topic.removeListener(listenerId);
            log.info("通知推送扇出订阅已退订：topic={}", PUSH_TOPIC);
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
}
