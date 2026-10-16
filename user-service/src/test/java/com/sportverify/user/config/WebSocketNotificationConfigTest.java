package com.sportverify.user.config;

import com.sportverify.user.auth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket+STOMP 配置注册断言测试（TASK-185 add-notification-ws-push；TASK-250 D5 增 Origin 断言）。
 *
 * <p>用 mock 的 registry 断言注册动作：STOMP 端点串、<b>握手层 Origin 白名单透传</b>、
 * SimpleBroker 前缀、用户目标前缀、心跳协商值与 TaskScheduler 注入、入站通道拦截器注册。
 * 不拉起真 broker。</p>
 */
class WebSocketNotificationConfigTest {

    /** 生产默认 Origin 白名单（与 {@code @Value} 默认值同款：本地前端双源） */
    private static final List<String> DEFAULT_ORIGINS =
            List.of("http://127.0.0.1:5173", "http://localhost:5173");

    private final StompConnectAuthInterceptor interceptor = mock(StompConnectAuthInterceptor.class);
    private final WebSocketNotificationConfig config =
            new WebSocketNotificationConfig(interceptor, DEFAULT_ORIGINS);

    /** registerStompEndpoints：注册 /ws-notifications 端点并以入参 Origin 列表设置握手白名单 */
    @Test
    void registerStompEndpoints_registersEndpointAndAllowedOrigins() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(anyString())).thenReturn(registration);

        config.registerStompEndpoints(registry);

        ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
        verify(registry).addEndpoint(endpoint.capture());
        assertEquals("/ws-notifications", endpoint.getValue(), "STOMP 端点应为 /ws-notifications");

        ArgumentCaptor<String[]> origins = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOrigins(origins.capture());
        assertArrayEquals(new String[]{"http://127.0.0.1:5173", "http://localhost:5173"},
                origins.getValue(), "握手层 Origin 白名单应恰以构造入参列表透传");
    }

    /** 默认值构造透传：以 @Value 默认列表构造 → setAllowedOrigins 收到同一列表（CSWSH 纵深默认生效） */
    @Test
    void registerStompEndpoints_defaultOrigins_passedThrough() {
        WebSocketNotificationConfig defaultConfig =
                new WebSocketNotificationConfig(interceptor, DEFAULT_ORIGINS);
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(anyString())).thenReturn(registration);

        defaultConfig.registerStompEndpoints(registry);

        ArgumentCaptor<String[]> origins = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOrigins(origins.capture());
        assertArrayEquals(DEFAULT_ORIGINS.toArray(new String[0]), origins.getValue(),
                "默认 Origin 列表应原样透传（本地零扰动）");
    }

    /** 自定义 Origin 透传：生产经 USER_WS_ALLOWEDORIGINS 覆盖后的列表应原样注入握手白名单 */
    @Test
    void registerStompEndpoints_customOrigins_passedThrough() {
        List<String> prodOrigins = List.of("https://app.sportverify.example", "https://m.sportverify.example");
        WebSocketNotificationConfig prodConfig =
                new WebSocketNotificationConfig(interceptor, prodOrigins);
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(anyString())).thenReturn(registration);

        prodConfig.registerStompEndpoints(registry);

        ArgumentCaptor<String[]> origins = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOrigins(origins.capture());
        assertArrayEquals(prodOrigins.toArray(new String[0]), origins.getValue(),
                "生产覆盖的 Origin 列表应原样透传");
    }

    /** configureMessageBroker：SimpleBroker /queue + 用户前缀 /user + 心跳 10s/10s + TaskScheduler */
    @Test
    void configureMessageBroker_brokerPrefixUserPrefixHeartbeat() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerReg = mock(SimpleBrokerRegistration.class);
        when(registry.enableSimpleBroker(anyString())).thenReturn(brokerReg);
        when(brokerReg.setHeartbeatValue(any())).thenReturn(brokerReg);
        when(brokerReg.setTaskScheduler(any(TaskScheduler.class))).thenReturn(brokerReg);

        config.configureMessageBroker(registry);

        ArgumentCaptor<String> brokerPrefix = ArgumentCaptor.forClass(String.class);
        verify(registry).enableSimpleBroker(brokerPrefix.capture());
        assertEquals("/queue", brokerPrefix.getValue(), "SimpleBroker 前缀应为 /queue");
        verify(registry).setUserDestinationPrefix("/user");

        ArgumentCaptor<long[]> heartbeat = ArgumentCaptor.forClass(long[].class);
        verify(brokerReg).setHeartbeatValue(heartbeat.capture());
        assertArrayEquals(new long[]{10_000L, 10_000L}, heartbeat.getValue(),
                "STOMP 心跳协商值应为 10s/10s");
        verify(brokerReg).setTaskScheduler(any(TaskScheduler.class));
    }

    /** configureClientInboundChannel：注册 CONNECT 鉴权拦截器（兜底层不变） */
    @Test
    void configureClientInboundChannel_registersAuthInterceptor() {
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(interceptor);
    }

    /** 心跳 TaskScheduler bean：非空调度器（供心跳帧调度） */
    @Test
    void stompTaskScheduler_isAvailable() {
        assertNotNull(config.stompTaskScheduler(), "心跳调度 TaskScheduler 应可实例化");
    }

    // ==================== E20（TASK-256）：STOMP 强制 type=ACCESS ====================

    private static final String DEMO_SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";
    private static final String BEARER = "Bearer ";

    private final JwtUtil jwtUtil = new JwtUtil(DEMO_SECRET, 15, 7, false);
    private final StompConnectAuthInterceptor authInterceptor =
            new StompConnectAuthInterceptor(jwtUtil);

    private Message<?> connectFrame(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** E20：refresh token 建会话被拒（凭证窗口不得放大——刷新凭证不能当 access 用） */
    @Test
    void stompConnect_refreshToken_rejected() {
        String refresh = jwtUtil.issueRefreshToken(1001L);

        assertThrows(MessagingException.class,
                () -> authInterceptor.preSend(connectFrame(BEARER + refresh), null),
                "refresh token 不能替代 access 建立 WS 会话");
    }

    /** E20：access token 通过且 Principal 正确（userId 来自网关注入身份语义） */
    @Test
    void stompConnect_accessToken_acceptsAndSetsPrincipal() {
        String access = jwtUtil.issueAccessToken(1001L, "USER");

        Message<?> result = authInterceptor.preSend(connectFrame(BEARER + access), null);

        assertNotNull(result);
        var accessor = StompHeaderAccessor.wrap(result);
        assertNotNull(accessor.getUser(), "access 通过须绑定会话身份");
        assertEquals("1001", accessor.getUser().getName(), "Principal 应为签发 userId");
    }

    /** E20：无效 token 拒绝（签名/时效失败不入站） */
    @Test
    void stompConnect_invalidToken_rejected() {
        assertThrows(MessagingException.class,
                () -> authInterceptor.preSend(connectFrame(BEARER + "invalid.jwt.token"), null));
    }
}
