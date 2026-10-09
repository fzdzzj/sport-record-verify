package com.sportverify.user.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket+STOMP 配置注册断言测试（TASK-185 add-notification-ws-push）。
 *
 * <p>用 mock 的 registry 断言注册动作：STOMP 端点串、SimpleBroker 前缀、用户目标前缀、
 * 心跳协商值与 TaskScheduler 注入、入站通道拦截器注册。不拉起真 broker。</p>
 */
class WebSocketNotificationConfigTest {

    private final StompConnectAuthInterceptor interceptor = mock(StompConnectAuthInterceptor.class);
    private final WebSocketNotificationConfig config = new WebSocketNotificationConfig(interceptor);

    /** registerStompEndpoints：注册 /ws-notifications 端点 */
    @Test
    void registerStompEndpoints_registersEndpoint() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        when(registry.addEndpoint(anyString())).thenReturn(mock(StompWebSocketEndpointRegistration.class));

        config.registerStompEndpoints(registry);

        ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
        verify(registry).addEndpoint(endpoint.capture());
        assertEquals("/ws-notifications", endpoint.getValue(), "STOMP 端点应为 /ws-notifications");
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

    /** configureClientInboundChannel：注册 CONNECT 鉴权拦截器 */
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
}
