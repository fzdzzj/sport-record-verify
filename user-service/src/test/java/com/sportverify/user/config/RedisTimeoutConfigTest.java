package com.sportverify.user.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Redis 客户端超时配置测试（TASK-263，G8 Redis 客户端超时矩阵）。
 *
 * <p>读取真实 application.yml，断言环境属性中 spring.data.redis.timeout
 * 存在且配置值为 3000ms（Duration 3秒）。</p>
 */
class RedisTimeoutConfigTest {

    private static final String REDIS_TIMEOUT_KEY = "spring.data.redis.timeout";

    @Test
    @DisplayName("断言 application.yml 中配置 spring.data.redis.timeout 为 3000ms")
    void redisTimeoutPropertyConfigured() throws IOException {
        MutablePropertySources propertySources = yamlSources();
        Object rawTimeout = propertySources.stream()
                .filter(ps -> ps.containsProperty(REDIS_TIMEOUT_KEY))
                .findFirst()
                .map(ps -> ps.getProperty(REDIS_TIMEOUT_KEY))
                .orElse(null);

        assertNotNull(rawTimeout, "spring.data.redis.timeout 配置项应存在于 application.yml");
        assertEquals("3000ms", rawTimeout.toString(), "spring.data.redis.timeout 原文字面量应为 3000ms");

        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        Duration duration = binder.bind(REDIS_TIMEOUT_KEY, Duration.class).orElse(null);
        assertNotNull(duration, "spring.data.redis.timeout 应成功绑定为 Duration");
        assertEquals(Duration.ofMillis(3000), duration, "Redis 超时时长应为 3000 毫秒");
    }

    private static MutablePropertySources yamlSources() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        for (PropertySource<?> source : sources) {
            propertySources.addLast(source);
        }
        return propertySources;
    }
}
