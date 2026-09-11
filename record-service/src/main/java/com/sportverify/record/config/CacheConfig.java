package com.sportverify.record.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存配置（Caffeine）。
 *
 * <p>骨架阶段仅注册缓存实例；后续变更按「Caffeine + Redis 二级缓存」策略
 * （审批版 §7.3）接入榜单与阈值读取，此处为读侧热数据预留。</p>
 */
@Configuration
public class CacheConfig {

    /**
     * 通用本地缓存：容量上限 1 万条，写入 5 分钟后过期，开启命中统计便于压测观察。
     */
    @Bean
    public Cache<String, Object> caffeineCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
    }
}
