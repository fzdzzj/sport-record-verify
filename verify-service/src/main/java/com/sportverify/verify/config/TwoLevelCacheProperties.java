package com.sportverify.verify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 规则读取二级缓存配置（cache.two-level.*）。
 *
 * <p>二级缓存只覆盖「规则快照/灰度路由」低频变更读路径（读多写少），
 * 承载在 Redis（跨实例共享）+ Caffeine（本实例）两层之上；「判定结果」缓存
 * 维持单层 Caffeine（有状态机幂等兜底，无需二级，见 RuleVersionService 与
 * CacheConfig 的边界注释）。</p>
 *
 * <p>为什么原子都做短 TTL：规则变更低频，秒级一致性靠「精准失效（变更监听
 * invalidate + Redis DEL）」，TTL 只做兜底上界，不承担实时一致性的主责。</p>
 */
@Data
@ConfigurationProperties(prefix = "cache.two-level")
public class TwoLevelCacheProperties {

    /** 二级缓存总开关（默认开）：关闭后退化回「Caffeine + DB」单层快路径，Redis 层整体跳过 */
    private boolean enabled = true;

    /** Redis 层基础 TTL（默认 60s）：兜底收敛上界，与「回滚 ≤60s」一致 */
    private Duration ttl = Duration.ofSeconds(60);

    /** 随机抖动幅度 ±jitter（默认 ±10s）：防雪崩——批量写入时 TTL 错峰，避免同刻过期打库 */
    private Duration jitter = Duration.ofSeconds(10);

    /** 空值哨兵 TTL（默认 5s）：DB 查不到的 key 缓存空值短 TTL 防穿透，短时效容忍低 */
    private Duration emptyTtl = Duration.ofSeconds(5);

    /** 互斥重建锁等待超时（默认 3s）：缓存失效并发重建时，争锁最久等 3s，避免阻塞校验主链路 */
    private Duration lockWait = Duration.ofSeconds(3);

    /** 互斥重建锁租期（默认 10s）：持锁回源 DB 的最长耗时，超时自动释放防死锁 */
    private Duration lockLease = Duration.ofSeconds(10);

    /** Redis key 前缀：与业务 key（verify:rules:*）拼装成记录的完整 Redis key */
    private String keyPrefix = "verify:rule:cache:";
}