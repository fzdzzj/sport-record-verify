package com.sportverify.user.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * user-service 健康探针测试（TASK-198 批3）。
 *
 * <p>现网懒初始化范式：真实拉起服务上下文与 Web 端口（{@code spring.main.lazy-initialization=true}
 * 延迟业务 bean；Nacos 注册失败经 fail-fast=false 降级为日志；db/redis 探针在无中间件环境下
 * 由 HealthIndicator 捕获连接异常返回 DOWN，不阻断启动）。
 * <b>偏差登记</b>：{@code StompConnectAuthInterceptor} 在主源无任何 bean 注册（缺 {@code @Component}，
 * 也无 {@code @Bean} 工厂），WebSocket 基础设施急切创建 {@code webSocketNotificationConfig}
 * 时因依赖缺失使整上下文启动失败——此为既有生产 boot 缺陷（TASK-198 之前已存在，主源不在本任务
 * 白名单内不可修），本测试以 {@code @Import} 供给该 bean 使探针端点可真实验证；缺陷修复已列
 * PLAN 后续项。断言口径（任务书 §0-5）：只断言 liveness UP 与 readiness 组端点/结构存在，
 * <strong>禁断言 db/redis UP</strong>（CI 无中间件，本地有中间件时 readiness 为 UP——两种实况
 * 都必须绿，故 readiness 状态值不做硬断言）；readiness 组成员结构以资源文件断言
 * （show-details=never 下 HTTP 层不暴露组件明细）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.lazy-initialization=true",
        "spring.cloud.nacos.discovery.fail-fast=false"})
@Import(com.sportverify.user.config.StompConnectAuthInterceptor.class)
class UserProbeTest {

    @Autowired
    private TestRestTemplate rest;

    /** liveness 探针：不挂任何外部依赖，任何环境必须 200 + UP。 */
    @Test
    void livenessIsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/liveness", String.class);
        assertEquals(org.springframework.http.HttpStatus.OK, resp.getStatusCode(), "liveness 必须 200");
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("\"status\":\"UP\""), resp.getBody());
    }

    /** readiness 组端点存在且按 health 端点语义响应（状态值随中间件实况 UP/DOWN，均合法）。 */
    @Test
    void readinessGroupEndpointResponds() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/readiness", String.class);
        assertTrue(resp.getStatusCode() == org.springframework.http.HttpStatus.OK
                        || resp.getStatusCode() == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "readiness 端点应可访问，实测=" + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().toLowerCase(Locale.ROOT).contains("status"), resp.getBody());
    }

    /** readiness 组结构（资源配置断言）：本服务依赖 MySQL + Redis，include 必须逐字一致。 */
    @Test
    void readinessGroupStructureMatchesRealDependencies() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(url, "application.yml should be on classpath for test");
        String content = Files.readString(Paths.get(url.toURI()));

        assertTrue(content.contains("probes:"), "probes 配置存在");
        assertTrue(content.contains("enabled: true"), "probes.enabled=true");
        assertTrue(content.contains("include: readinessState,db,redis"),
                "user 依赖 MySQL+Redis，readiness 组 include 必须逐字为 readinessState,db,redis");
        assertTrue(content.contains("show-details: never"), "show-details=never 维持");
    }
}
