package com.sportverify.user.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * 通知实时推送 WebSocket+STOMP 配置（TASK-185 add-notification-ws-push）。
 *
 * <p>启用代理式消息 broker（{@code @EnableWebSocketMessageBroker}）：</p>
 * <ul>
 *   <li>椭圆端点 {@value #STOMP_ENDPOINT}，经网关 {@code /user/ws-notifications}（StripPrefix 后到达）；</li>
 *   <li>SimpleBroker 前缀 {@value #SIMPLE_BROKER_PREFIX} + 用户目标前缀 {@value #USER_DEST_PREFIX}
 *       → 收件人队列实为 {@code /user/queue/notifications}；</li>
 *   <li>协议心跳 10s/10s + TaskScheduler（保活空闲连接，见任务书 §2.1 新增参数新命名空间）；</li>
 *   <li>入站通道注册 {@link StompConnectAuthInterceptor}（CONNECT 帧级鉴权）。</li>
 * </ul>
 *
 * <p><b>CSWSH 双层防线</b>（TASK-250 D5）：握手层 {@code setAllowedOrigins} Origin 白名单
 * （{@code user.ws.allowed-origins}，生产经环境变量 {@code USER_WS_ALLOWEDORIGINS} 覆盖）
 * 拦掉跨站 WebSocket 握手；CONNECT 帧级 JWT 鉴权（{@link StompConnectAuthInterceptor}）
 * 作为兜底层不变。二者互补：Origin 白名单是纵深加固而非唯一防线。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketNotificationConfig implements WebSocketMessageBrokerConfigurer {

    /** STOMP 椭圆端点（浏览器业务端连接路径） */
    public static final String STOMP_ENDPOINT = "/ws-notifications";
    /** SimpleBroker 前缀（应用层目的地 /queue/** 交由本地 broker 处理） */
    public static final String SIMPLE_BROKER_PREFIX = "/queue";
    /** 用户目标前缀（convertAndSendToUser 的用户隔离命名空间） */
    public static final String USER_DEST_PREFIX = "/user";
    /** STOMP 协议心跳（毫秒，发送/接收）：10s/10s，本变更新增参数（不碰既有键） */
    public static final long[] HEARTBEAT_SERVER = {10_000L, 10_000L};

    private final StompConnectAuthInterceptor stompAuthInterceptor;
    /** 握手层 Origin 白名单（CSWSH 纵深；默认本地前端源，生产经环境变量覆盖） */
    private final List<String> allowedOrigins;

    /**
     * 构造注入 CONNECT 帧鉴权拦截器与握手层 Origin 白名单。
     *
     * @param stompAuthInterceptor CONNECT 帧级 JWT 鉴权拦截器（兜底层）
     * @param allowedOrigins 允许握手的 Origin 列表（逗号分隔配置经 Spring 自动拆分）
     */
    public WebSocketNotificationConfig(
            StompConnectAuthInterceptor stompAuthInterceptor,
            @Value("${user.ws.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}")
            List<String> allowedOrigins) {
        this.stompAuthInterceptor = stompAuthInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOrigins(allowedOrigins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(SIMPLE_BROKER_PREFIX)
                .setHeartbeatValue(HEARTBEAT_SERVER)
                .setTaskScheduler(stompTaskScheduler());
        registry.setUserDestinationPrefix(USER_DEST_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }

    /** STOMP 心跳协商用 TaskScheduler（心跳帧调度与空闲连接超时判定） */
    @Bean
    public TaskScheduler stompTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setPoolSize(2);
        return scheduler;
    }
}
