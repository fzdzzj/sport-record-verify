package com.sportverify.verify.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存配置（Caffeine）。
 *
 * <p>共享单实例缓存（写入 1 分钟后过期），承载三类 key：</p>
 * <ul>
 *   <li>{@code verify:result:{recordId}}：判定结果缓存（压测 P95 达标关键：命中缓存不重算）；</li>
 *   <li>{@code verify:rules:gray} / {@code verify:rules:active}：灰度/基线路由行；</li>
 *   <li>{@code verify:rules:v{version}}：版本规则快照。</li>
 * </ul>
 * <p>1 分钟 TTL 即规范「回滚 ≤60s 生效」的上界：生命周期操作只失效本实例缓存，
 * 其余实例靠 TTL 收敛。</p>
 */
@Configuration
public class CacheConfig {

    /**
     * 共享本地缓存：容量上限 1 万条，写入 1 分钟后过期（配合灰度回滚/全量收敛）。
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
