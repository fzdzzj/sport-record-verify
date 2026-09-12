package com.sportverify.verify.consumer;

import com.github.benmanes.caffeine.cache.Cache;
import com.sportverify.verify.service.RuleVersionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * 规则缓存失效广播订阅者（Redis pub/sub，跨实例秒级收敛）。
 *
 * <p>为什么需要广播：灰度比例/版本状态缓存在各实例本地 Caffeine（1min TTL），
 * 管理操作只落在其中一个实例——无广播时其余实例要等 TTL 过期才收敛，
 * 回滚达不到「秒级」。Redis 订阅发布把失效事件扇出到全部实例，回滚即时生效；
 * Redis 不可用时监听静默、发布侧仅告警，全网自动退化为 TTL 收敛，不产生新故障面。</p>
 *
 * <p>失效对象只有灰度/基线两把路由 key（版本快照 key 落库后不可变，无需失效）；
 * 发布方在事务提交后才发（否则订阅方重查到旧状态回填缓存，见 RuleVersionService）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleCacheInvalidationListener {

    private final RedissonClient redissonClient;
    private final Cache<String, Object> caffeineCache;

    /** 注册订阅：收到失效广播即清本实例路由缓存（含发布方自身，重复失效无害） */
    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(RuleVersionService.RULE_CACHE_TOPIC, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, versionId) -> {
            caffeineCache.invalidate(RuleVersionService.KEY_GRAY_ROUTE);
            caffeineCache.invalidate(RuleVersionService.KEY_ACTIVE_ROUTE);
            log.info("收到规则缓存失效广播，本地路由缓存已清：versionId={}", versionId);
        });
        log.info("规则缓存失效广播订阅已注册：topic={}", RuleVersionService.RULE_CACHE_TOPIC);
    }
}
