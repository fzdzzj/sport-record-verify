package com.sportverify.gateway.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * actuator 白名单收窄「配置 ↔ 过滤器行为」一致性断言（findings F08，承
 * {@link LeaderboardDailyAdminOnlyTest} 先例：从 classpath application.yml 读真实配置灌进过滤器，
 * 配置漂移当场变红）。
 *
 * <p>守的这条：网关白名单只放健康探针 {@code /actuator/health}（精确匹配，无 /** 后缀），
 * 其余 actuator 子路径（/actuator/metrics、/actuator/env 等）落回鉴权分支——
 * 监控栈按 ADR-0007 走内网直连（prometheus.yml 抓 host.docker.internal 服务端口，不经网关），
 * 指标端点经网关裸放行等于把组件明细与指标泄给任意未认证调用方。</p>
 */
class ActuatorWhitelistNarrowTest {

    private static final String SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";

    private static String yml() throws IOException {
        return new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /** 取 key 行的值（逗号分隔列表）；承 LeaderboardDailyAdminOnlyTest 同款解析 */
    private static List<String> whitelist() throws IOException {
        String content = yml();
        int k = content.indexOf("whitelist:");
        assertTrue(k >= 0, "app.auth.whitelist 应存在于 application.yml");
        int end = content.indexOf('\n', k);
        String value = content.substring(k + "whitelist:".length(), end < 0 ? content.length() : end);
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private AuthGlobalFilter filterWith(List<String> white) {
        AuthGlobalFilter filter = new AuthGlobalFilter(new JwtTokenParser(SECRET));
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "whitelist", white);
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", true);
        return filter;
    }

    private boolean whitelisted(AuthGlobalFilter filter, String path) {
        Boolean hit = ReflectionTestUtils.invokeMethod(filter, "isWhitelisted", path);
        return Boolean.TRUE.equals(hit);
    }

    private String token(long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().subject(String.valueOf(userId)).claim("type", "ACCESS").claim("role", role)
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    @Test
    void deployedWhitelistIsHealthProbeOnly() throws IOException {
        List<String> white = whitelist();
        AuthGlobalFilter filter = filterWith(white);
        // 健康探针必须放行
        assertTrue(whitelisted(filter, "/actuator/health"),
                "健康探针 /actuator/health 必须在白名单，实际=" + white);
        // 其余 actuator 子路径必须落回鉴权分支（精确匹配，无 /** 后缀）
        assertFalse(whitelisted(filter, "/actuator/metrics"),
                "指标查询端点 /actuator/metrics 不得经网关裸放行，实际=" + white);
        assertFalse(whitelisted(filter, "/actuator/env"),
                "环境端点 /actuator/env 不得经网关裸放行，实际=" + white);
        assertFalse(whitelisted(filter, "/actuator/prometheus"),
                "指标暴露端点 /actuator/prometheus 不得经网关裸放行（监控走内网直连），实际=" + white);
        // 白名单不得出现 /actuator/** 整段通配（一通配全泄）
        assertTrue(white.stream().noneMatch(p -> p.contains("actuator/**")),
                "白名单不得含 /actuator/** 整段通配，实际=" + white);
        // 发 token 端点不受收窄影响
        assertTrue(whitelisted(filter, "/api/auth/login"),
                "发 token 端点白名单语义不得被本次收窄破坏，实际=" + white);
    }

    @Test
    void healthProbePassesWithoutTokenWithDeployedConfig() throws IOException {
        AuthGlobalFilter filter = filterWith(whitelist());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health"));
        filter.filter(exchange, e -> Mono.empty()).block();
        assertNull(exchange.getResponse().getStatusCode(),
                "健康探针无 token 也必须放行（网关不写终态），实际=" + whitelist());
    }

    @Test
    void metricsWithoutTokenGets401WithDeployedConfig() throws IOException {
        AuthGlobalFilter filter = filterWith(whitelist());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/metrics"));
        filter.filter(exchange, e -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(),
                "指标查询端点无 token 必须 401（走鉴权分支），实际=" + whitelist());
    }

    @Test
    void defaultWhitelistEqualsHealthProbeOnly() {
        // 判别式（TASK-127 漂移②）：ApplicationContextRunner 不注入任何 whitelist 属性实例化过滤器，
        // 生效白名单必须等于新默认值 /api/auth/**,/actuator/health——
        // @Value 默认值若仍是旧整段通配（yml 常在时运行时漂移不可见），此处当场变红
        new ApplicationContextRunner()
                // Boot 生产语境靠 finishBeanFactoryInitialization 先把名为 conversionService 的
                // ConversionService bean 设进 beanFactory，@Value 的 List<String> 才按逗号拆分
                // （裸 runner 不挂它时实测注入为单元素整串）——此处注册同名 bean 复刻生产语义
                .withBean("conversionService", ConversionService.class,
                        ApplicationConversionService::getSharedInstance)
                .withBean(JwtTokenParser.class, () -> new JwtTokenParser(SECRET))
                .withUserConfiguration(AuthGlobalFilter.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AuthGlobalFilter filter = context.getBean(AuthGlobalFilter.class);
                    assertThat(ReflectionTestUtils.getField(filter, "whitelist"))
                            .as("@Value 默认白名单必须是 /api/auth/**,/actuator/health（健康探针精确匹配）")
                            .isEqualTo(List.of("/api/auth/**", "/actuator/health"));
                    assertThat(whitelisted(filter, "/actuator/health"))
                            .as("默认白名单下健康探针仍须放行").isTrue();
                    assertThat(whitelisted(filter, "/actuator/metrics"))
                            .as("默认白名单下指标端点不得放行（默认值收敛的判别点）").isFalse();
                    assertThat(whitelisted(filter, "/api/auth/login"))
                            .as("默认白名单下发 token 端点仍须放行").isTrue();
                });
    }
}
