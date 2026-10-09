package com.sportverify.record.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.cloud.openfeign.support.FeignHttpClientProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Feign 传输层连接池配置判别式测试（TASK-189，findings F07）。
 * <p>
 * 纯 JVM 确定性，不启动 Spring 上下文：
 * 1. 验证 feign.hc5.ApacheHttp5Client 在类路径上（由 api 模块传递引入，误删或误换坐标即红）；
 * 2. 读真实 application.properties 配置文件，经 Spring Binder 绑定 FeignHttpClientProperties，
 *    断言连接池参数（maxConnections=200, maxConnectionsPerRoute=50, timeToLive=300s）；
 * 3. 断言 spring.cloud.openfeign.httpclient.hc5.enabled 显式配置为 true。
 */
class FeignConnectionPoolConfigTest {

    @Test
    void hc5ClientOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("feign.hc5.ApacheHttp5Client"),
                "feign.hc5.ApacheHttp5Client 应在类路径上，由 api 模块传递引入");
    }

    @Test
    void poolParametersBoundFromRealConfigFile() throws IOException {
        Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        MutablePropertySources propertySources = new MutablePropertySources();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> map = (Map) props;
        propertySources.addLast(new MapPropertySource("application.properties", map));

        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        FeignHttpClientProperties properties = binder.bind("spring.cloud.openfeign.httpclient",
                Bindable.of(FeignHttpClientProperties.class)).get();

        assertNotNull(properties, "FeignHttpClientProperties 绑定结果不应为空");
        assertEquals(200, properties.getMaxConnections(), "maxConnections 应为 200");
        assertEquals(50, properties.getMaxConnectionsPerRoute(), "maxConnectionsPerRoute 应为 50");
        assertEquals(300L, properties.getTimeToLive(), "timeToLive 应为 300");
        assertEquals(TimeUnit.SECONDS, properties.getTimeToLiveUnit(), "timeToLiveUnit 应为 SECONDS");
    }

    @Test
    void hc5EnabledKeyPresent() throws IOException {
        Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        MutablePropertySources propertySources = new MutablePropertySources();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> map = (Map) props;
        propertySources.addLast(new MapPropertySource("application.properties", map));

        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);
        assertEquals("true", resolver.getProperty("spring.cloud.openfeign.httpclient.hc5.enabled"),
                "spring.cloud.openfeign.httpclient.hc5.enabled 键值应为 true");
    }
}
