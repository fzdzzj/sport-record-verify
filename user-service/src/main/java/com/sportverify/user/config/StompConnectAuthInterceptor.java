package com.sportverify.user.config;

import com.sportverify.user.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

/**
 * STOMP CONNECT 帧级鉴权拦截器（通知实时推送 WS+STOMP，TASK-185 add-notification-ws-push）。
 *
 * <p>浏览器 WS API 不支持自定义 HTTP 头，故 access token 经 STOMP CONNECT 帧的
 * {@code Authorization} 头携带（协议内正规位），由本拦截器在入站通道解析并建立会话身份：
 * 剥 {@code Bearer } 前缀后用 {@link JwtUtil} 解析（网关与 user-service 同源密钥，
 * 见 ADR-0007 既有约束），取 {@code userId} 置为会话 {@link Principal}。无效/过期/缺失
 * 一律拒绝连接（抛 {@link MessagingException}，会话不建立）——WS 端点不设匿名。</p>
 *
 * <p>仅拦截 CONNECT 命令（会话建立前唯一携带凭证的帧）；此后同连接内的订阅/发送帧走
 * 已建立的会话身份，直通不重复校验。不向 query string 传 token（见任务书红线 §0.3）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class StompConnectAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    /**
     * 入站帧前置处理：CONNECT 帧解析并绑定用户；其它命令直通。
     *
     * @return 绑定会话身份的 CONNECT 消息（或原帧直通）；校验失败抛 {@link MessagingException}
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }
        String auth = accessor.getFirstNativeHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            throw new MessagingException("STOMP CONNECT 缺少 Authorization 头，拒绝匿名会话建立");
        }
        String token = auth.startsWith(BEARER_PREFIX)
                ? auth.substring(BEARER_PREFIX.length()).trim()
                : auth.trim();
        final Long userId;
        try {
            Claims claims = jwtUtil.parse(token);
            userId = Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            log.warn("STOMP CONNECT 鉴权失败，拒绝会话：{}", e.getMessage());
            throw new MessagingException("STOMP CONNECT 携带的 access token 无效或已过期，拒绝会话建立");
        }
        accessor.setUser(new Principal() {
            @Override
            public String getName() {
                return String.valueOf(userId);
            }
        });
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }
}
