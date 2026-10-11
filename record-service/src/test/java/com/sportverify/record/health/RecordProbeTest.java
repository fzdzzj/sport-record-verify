package com.sportverify.record.health;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

import com.sportverify.record.RecordApplication;
import com.sportverify.record.config.ShardingDataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * record-service 健康探针测试（TASK-198 批3）。
 *
 * <p>现网懒初始化范式：真实拉起服务上下文与 Web 端口（{@code spring.main.lazy-initialization=true}
 * 延迟业务 bean；Nacos 注册失败经 fail-fast=false 降级为日志；db 探针在无中间件环境下
 * 由 HealthIndicator 捕获连接异常返回 DOWN，不阻断启动）。断言口径（任务书 §0-5）：只断言
 * liveness UP 与 readiness 组端点/结构存在，<strong>禁断言 db UP</strong>（CI 无中间件，
 * 本地有中间件时 readiness 为 UP——两种实况都必须绿，故 readiness 状态值不做硬断言）。
 * record 配置为 application.properties：readiness 组成员结构走 properties 上下文口径断言
 * （show-details=never 下 HTTP 层不暴露组件明细）。</p>
 *
 * <p><b>偏差登记（既有约束，非本任务改动面）</b>：① 主源自定义 ShardingSphere 数据源
 * （ShardingDataSourceConfig 读 classpath sharding.yaml）在测试类路径被既有夹具
 * {@code src/test/resources/sharding.yaml}（占位符替换验证用，刻意不含 dataSourceClassName）
 * 覆盖，构造即失败；且 @Scheduled bean（flush/对账/归档/降级补偿）在 Boot 懒初始化下仍急切
 * 实例化、必然触达数据源与 Redis/MQ 客户端链；② Boot 3.2.4 的 db 健康贡献者按
 * {@code Map<String, DataSource>} 注入，会实例化<strong>全部</strong> DataSource bean，
 * 不能用 @Primary 二选一。故本测试以 ProbeApp 定制上下文（组件扫描排除
 * ShardingDataSourceConfig，数据源由 ProbeStubConfig 唯一提供——懒连接
 * connectTimeout=300ms 保证 db 探针无中间件时快速落 DOWN）；CI 无 Redis、TASK-196 根除
 * fastjson 后 rocketMQTemplate bean 创建期即 NoClassDefFoundError，故 Redisson /
 * RocketMQTemplate 同以 mock 顶替（两处自动配置均 {@code @ConditionalOnMissingBean}
 * 让位）。主源与既有夹具零改动（均不在本任务白名单内）；fastjson 模板创建缺陷与
 * sharding.yaml 测试夹具覆盖问题已列 PLAN 后续项。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {RecordProbeTest.ProbeApp.class, RecordProbeTest.ProbeStubConfig.class},
        properties = {
        "spring.main.lazy-initialization=true",
        "spring.cloud.nacos.discovery.fail-fast=false"})
class RecordProbeTest {

    /** 测试专用应用装配：与 RecordApplication 同构，仅排除被既有测试夹具卡死的 ShardingDataSourceConfig
     * 与 RecordApplication 本身（避免其 scanBasePackages="com.sportverify" 二次扫描把排除项与
     * 测试类重新捞入）；mapper 按名挂 ProbeStubConfig.probeSqlSessionFactory。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.sportverify.record",
            excludeFilters = {@Filter(type = FilterType.ASSIGNABLE_TYPE,
                            classes = {ShardingDataSourceConfig.class, RecordApplication.class})})
    @MapperScan(value = "com.sportverify.record.mapper",
            sqlSessionFactoryRef = "probeSqlSessionFactory")
    static class ProbeApp {
    }

    @TestConfiguration
    static class ProbeStubConfig {

        /** 唯一 DataSource（ProbeApp 已排除分片数据源）：懒连接，db 探针无中间件时快速落 DOWN。 */
        @Bean
        DataSource probeDataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:mysql://127.0.0.1:1/record_db?connectTimeout=300");
            ds.setUsername("probe");
            ds.setPassword("probe");
            ds.setMaximumPoolSize(1);
            ds.setInitializationFailTimeout(-1);
            ds.setPoolName("record-probe-pool");
            return ds;
        }

        /** 探针专用 MyBatis-Plus 工厂（纯注解 mapper，无 XML 需加载）；@MapperScan 按名引用。 */
        @Bean
        org.apache.ibatis.session.SqlSessionFactory probeSqlSessionFactory(
                DataSource probeDataSource) throws Exception {
            com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean factory =
                    new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(probeDataSource);
            return factory.getObject();
        }

        /** CI 无 Redis：@Scheduled 急切链注入 RedissonClient，真实创建期即尝试连接，mock 顶替。 */
        @Bean
        org.redisson.api.RedissonClient probeRedissonClient() {
            return mock(org.redisson.api.RedissonClient.class);
        }

        /** 事务管理器（flush 补偿注入）：与 JdbcTransactionManager 同源，直接挂探针数据源。 */
        @Bean
        org.springframework.transaction.PlatformTransactionManager probeTransactionManager(
                DataSource probeDataSource) {
            return new org.springframework.jdbc.support.JdbcTransactionManager(probeDataSource);
        }

        /** TASK-196 根除 fastjson 后 rocketMQTemplate bean 创建期即 NoClassDefFoundError，mock 顶替。 */
        @Bean
        RocketMQTemplate probeRocketMQTemplate() {
            return mock(RocketMQTemplate.class);
        }
    }

    @Autowired
    private TestRestTemplate rest;

    /** liveness 探针：不挂任何外部依赖，任何环境必须 200 + UP。 */
    @Test
    void livenessIsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "liveness 必须 200");
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("\"status\":\"UP\""), resp.getBody());
    }

    /** readiness 组端点存在且按 health 端点语义响应（状态值随中间件实况 UP/DOWN，均合法）。 */
    @Test
    void readinessGroupEndpointResponds() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/readiness", String.class);
        assertTrue(resp.getStatusCode() == HttpStatus.OK
                        || resp.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE,
                "readiness 端点应可访问，实测=" + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().toLowerCase(Locale.ROOT).contains("status"), resp.getBody());
    }

    /** readiness 组结构（properties 资源断言）：本服务仅依赖 MySQL（无 Redis），include 必须逐字一致。 */
    @Test
    void readinessGroupStructureMatchesRealDependencies() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.properties");
        assertNotNull(url, "application.properties should be on classpath for test");
        String content = Files.readString(Paths.get(url.toURI()));

        assertTrue(content.contains("management.endpoint.health.probes.enabled=true"),
                "probes.enabled=true");
        assertTrue(content.contains(
                        "management.endpoint.health.group.readiness.include=readinessState,db"),
                "record 仅依赖 MySQL，readiness 组 include 必须逐字为 readinessState,db");
        assertTrue(!content.contains("readinessState,db,redis"),
                "record 无 Redis，不得复制他服务 include");
        assertTrue(content.contains("management.endpoint.health.show-details=never"),
                "show-details=never 维持");
    }
}
