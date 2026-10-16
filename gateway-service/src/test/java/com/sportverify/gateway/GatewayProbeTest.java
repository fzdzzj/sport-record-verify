package com.sportverify.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
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
 * gateway 健康探针测试（TASK-198 批3）。
 *
 * <p>现网懒初始化范式：真实拉起网关上下文与 Web 端口（{@code spring.main.lazy-initialization=true}
 * 延迟业务 bean；Nacos/RPC 客户端为惰性或后台重试，不阻塞离线启动）。
 * 断言口径（任务书 §0-5）：只断言 liveness UP 与 readiness 组端点/结构存在，
 * <strong>禁断言 db/redis UP</strong>（CI 无中间件）；readiness 组成员结构以资源文件断言
 * （show-details=never 下 HTTP 层不暴露组件明细，组结构以 yml 配置字面量为准）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.lazy-initialization=true",
        // 测试环境无 Nacos：注册失败降级为日志（SCA failFast 默认 true 会终止启动），
        // 其余发现/路由/限流口径保持现网不变
        "spring.cloud.nacos.discovery.fail-fast=false"})
class GatewayProbeTest {

    @Autowired
    private TestRestTemplate rest;

    /** liveness 探针：无外部依赖，任何环境必须 200 + UP。 */
    @Test
    void livenessIsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "liveness 必须 200");
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("\"status\":\"UP\""), resp.getBody());
    }

    /** readiness 组端点存在且按 health 端点语义响应（gateway 组=readinessState，无中间件也应 UP）。 */
    @Test
    void readinessGroupEndpointResponds() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/readiness", String.class);
        assertTrue(resp.getStatusCode() == HttpStatus.OK
                        || resp.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE,
                "readiness 端点应可访问，实测=" + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().toLowerCase(Locale.ROOT).contains("status"), resp.getBody());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "gateway readiness 组无外部依赖，应为 UP");
    }

    /** readiness 组结构（资源配置断言，GatewayCorsConfigTest 范式）：gateway 仅 readinessState。 */
    @Test
    void readinessGroupStructureMatchesRealDependencies() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(url, "application.yml should be on classpath for test");
        String content = Files.readString(Paths.get(url.toURI()));

        assertTrue(content.contains("probes:"), "probes 配置存在");
        assertTrue(content.contains("enabled: true"), "probes.enabled=true");
        int groupStart = content.indexOf("group:");
        assertTrue(groupStart >= 0, "readiness group 配置存在");
        String groupPart = content.substring(groupStart);
        assertTrue(groupPart.contains("readinessState"), "readiness 组含 readinessState");
        assertTrue(!groupPart.contains("db,redis"), "gateway 无 db/redis，readiness 组不得复制他服务 include");
    }

    /** 网关白名单精确追加两探针路径（保持既有精确匹配语义），其余 actuator 端点不白名单。 */
    @Test
    void whitelistAddsProbePathsExactly() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(url, "application.yml should be on classpath for test");
        String content = Files.readString(Paths.get(url.toURI()));

        int wStart = content.indexOf("whitelist:");
        assertTrue(wStart >= 0, "whitelist 配置存在");
        String line = content.substring(wStart, content.indexOf('\n', wStart));
        assertTrue(line.contains("/actuator/health/liveness"), "白名单含 liveness 探针路径: " + line);
        assertTrue(line.contains("/actuator/health/readiness"), "白名单含 readiness 探针路径: " + line);
        assertTrue(line.contains("/actuator/health,"), "聚合 health 原样保留: " + line);
        assertTrue(!line.contains("/actuator/health/**"), "禁止通配放行 actuator: " + line);
        assertTrue(!line.contains("/actuator/prometheus"), "prometheus 不经网关放行: " + line);
    }

    /** TASK-254 E10：网关 HTTP 客户端超时资源断言——httpclient 段含 connect-timeout 3000ms
     *  与 response-timeout 10s（SCG 默认 response-timeout 无截止，上游 hang 死连接无限占用，
     *  见任务书 §3 R1；数值为静态推演值，调整须指导侧授权）。 */
    @Test
    void httpclientTimeoutsConfigured() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(url, "application.yml should be on classpath for test");
        String content = Files.readString(Paths.get(url.toURI()));

        assertTrue(content.contains("httpclient:"), "spring.cloud.gateway.httpclient 配置存在");
        assertTrue(content.contains("connect-timeout: 3000"), "connect-timeout 须为 3000ms");
        assertTrue(content.contains("response-timeout: 10s"), "response-timeout 须为 10s");
    }
}
