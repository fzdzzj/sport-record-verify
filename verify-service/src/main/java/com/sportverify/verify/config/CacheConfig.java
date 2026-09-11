package com.sportverify.verify.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存配置（Caffeine）。
 *
 * <p>骨架阶段仅注册缓存实例；后续校验阈值读取按「Nacos 配置 + Caffeine 缓存」
 * （审批版 §5.2/§7.4）策略接入，改配置 60s 内生效。</p>
 */
@Configuration
public class CacheConfig {

    /**
     * 阈值/规则本地缓存：容量上限 1 万条，写入 1 分钟后过期（配合 Nacos 灰度刷新）。
     */
    @Bean
    public Cache<String, Object> caffeineCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .recordStats()
                .build();
    }
}
