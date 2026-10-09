package com.sportverify.user.config;

import com.sportverify.user.auth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * STOMP CONNECT 帧级鉴权拦截器单元测试（TASK-185 add-notification-ws-push）。
 *
 * <p>覆盖三态：有效 token → 会话 Principal 置为该 userId；无效/过期 token → 拒绝（抛
 * {@link MessagingException}，会话不建立）；缺失 Authorization 头 → 拒绝。另验证非
 * CONNECT 帧（SUBSCRIBE/SEND）直通不校验。JwtUtil 用真密钥离线签发（确定性，不引外部依赖）。</p>
 */
class StompConnectAuthInterceptorTest {

    private static final String SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";

    private StompConnectAuthInterceptor interceptor(JwtUtil jwtUtil) {
        return new StompConnectAuthInterceptor(jwtUtil);
    }

    /** CONNECT 消息（注入 Authorization 头） */
    private Message<?> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 非 CONNECT 帧（SUBSCRIBE，无任何凭证） */
    private Message<?> subscribeMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 有效 access token → 会话 Principal 解析为该 userId */
    @Test
    void connect_validToken_setsPrincipalToUserId() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7);
        String token = jwtUtil.issueAccessToken(1001L, "ROLE_USER");
        StompConnectAuthInterceptor guard = interceptor(jwtUtil);

        Message<?> out = guard.preSend(connectMessage("Bearer " + token), null);

        StompHeaderAccessor outAccessor = StompHeaderAccessor.wrap(out);
        Principal user = outAccessor.getUser();
        assertNotNull(user, "有效 token 应建立会话身份");
        assertEquals("1001", user.getName(), "会话 Principal 应为 token 解析出的 userId");
    }

    /** 过期 token → 拒绝，抛 MessagingException */
    @Test
    void connect_expiredToken_rejects() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, -1, 7);
        String token = jwtUtil.issueAccessToken(1001L, "ROLE_USER");
        StompConnectAuthInterceptor guard = interceptor(jwtUtil);

        assertThrows(MessagingException.class,
                () -> guard.preSend(connectMessage("Bearer " + token), null),
                "过期 token 应拒绝会话建立");
    }

    /** 无效签名（另一密钥签发的串）→ 拒绝，抛 MessagingException */
    @Test
    void connect_invalidSignature_rejects() {
        JwtUtil issuer = new JwtUtil("another-hs256-secret-key-0123456789abcdef", 15, 7);
        String token = issuer.issueAccessToken(1001L, "ROLE_USER");
        StompConnectAuthInterceptor guard = interceptor(new JwtUtil(SECRET, 15, 7));

        assertThrows(MessagingException.class,
                () -> guard.preSend(connectMessage("Bearer " + token), null),
                "错误密钥签发的 token 应拒绝");
    }

    /** 缺失 Authorization 头 → 拒绝，抛 MessagingException（WS 端点不设匿名） */
    @Test
    void connect_missingAuthorization_rejects() {
        StompConnectAuthInterceptor guard = interceptor(new JwtUtil(SECRET, 15, 7));

        assertThrows(MessagingException.class,
                () -> guard.preSend(connectMessage(null), null),
                "缺失 Authorization 应拒绝匿名会话");
    }

    /** 非 CONNECT 帧（SUBSCRIBE）直通：无凭证也不拦截、不抛异常 */
    @Test
    void nonConnectFrame_passesThrough() {
        StompConnectAuthInterceptor guard = interceptor(new JwtUtil(SECRET, 15, 7));
        Message<?> in = subscribeMessage();

        Message<?> out = guard.preSend(in, null);

        assertSame(in, out, "SUBSCRIBE 等非 CONNECT 帧应原样直通且不抛异常");
    }
}
