package com.sportverify.verify.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.sportverify.verify.config.TwoLevelCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 规则读取二级缓存服务（Caffeine → Redis → DB 回填两级，规范「二级缓存读路径」）。
 *
 * <p>只服务「规则快照/灰度路由」这一类低频变更读路径：读多写少、变更靠监听精准失效，
 * 值得为它搭 Redis 中间层（跨实例共享，热点规则不用每实例各查一次库）。判定结果缓存
 * 仍维持单层 Caffeine（见 CacheConfig）——它有状态机幂等兜底，不需要也不该被二级放大。</p>
 *
 * <p>读路径：{@code Caffeine 本地 → Redis 跨实例 → DB(rule_version) → 回填两级}。三层各司其职：</p>
 * <ul>
 *   <li>本地 Caffeine：最快的热路径，命中不访问 Redis/DB；</li>
 *   <li>Redis 中间层：跨实例共享，本实例 miss 可命中他实例已回填的副本，减少打库；</li>
 *   <li>DB 兜底：两级都 miss 才查库，并在两级回填（含空值哨兵防穿透）。</li>
 * </ul>
 *
 * <p>三防护贯穿（穿透=空值哨兵、击穿=互斥重建、雪崩=TTL 抖动）。Redis 任意操作异常一律
 * 降级为「只走 Caffeine + DB」不报错——缓存是性能优化，不得因 Redis 抖动阻断校验主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCacheService {

    /** 空值哨兵占位：Caffeine 存该常量标识「已确认 DB 无数据」，与「未缓存」（null）区分 */
    private static final Object EMPTY = new Object() {
        @Override
        public String toString() {
            return "__rule_cache_empty__";
        }
    };

    /** Redis 空值哨兵载体：Redis 层整体存 JSON，空值用固定字面量标识（防穿透） */
    private static final String EMPTY_JSON = "\"__rule_cache_empty__\"";

    /** 互斥重建锁前缀：{@code lock:rule-rebuild:{redisKey}}（对齐规范「互斥重建」） */
    private static final String LOCK_PREFIX = "lock:rule-rebuild:";

    private final Cache<String, Object> caffeineCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final TwoLevelCacheProperties props;

    /**
     * 二级读路径统一入口：Caffeine → Redis → DB 回填两级。
     *
     * @param cacheKey 业务 key（如 verify:rules:gray / verify:rules:v{v}）
     * @param type     返回值类型（用于 Redis 反序列化）
     * @param dbLoader 回源 DB 的加载器（缓存未命中才调用）
     * @return 命中值；DB 确认无数据返回 {@link Optional#empty()}（空值哨兵）
     */
    public <T> Optional<T> get(String cacheKey, Class<T> type, Supplier<T> dbLoader) {
        // 总开关关闭 → 直接走「Caffeine + DB」单层快路径，Redis 层整体跳过（运维兜底）
        if (!props.isEnabled()) {
            return readThroughLocal(cacheKey, type, dbLoader);
        }
        // 1 本地 Caffeine 命中：最快路径，不访问 Redis 与 DB（规范 Scenario「本地命中」）
        Object local = caffeineCache.getIfPresent(cacheKey);
        if (local == EMPTY) {
            return Optional.empty();
        }
        if (local != null) {
            return Optional.of(type.cast(local));
        }
        // 2 Redis 命中：回填本地后返回（规范 Scenario「本地未命中 Redis 命中」）
        Object redis = readRedis(cacheKey, type);
        if (redis == EMPTY) {
            caffeineCache.put(cacheKey, EMPTY);
            return Optional.empty();
        }
        if (redis != null) {
            caffeineCache.put(cacheKey, redis);
            return Optional.of(type.cast(redis));
        }
        // 3 两级未命中 → 回源 DB 并回填两级（规范 Scenario「两级未命中回源」）
        return rebuildFromDb(cacheKey, type, dbLoader);
    }

    /**
     * 精准失效单个 key：Caffeine 本地 + Redis 跨实例都清掉（变更监听/管理操作调用），
     * 下次读取自动回源到最新值；Redis 删除失败退化为 TTL 兜底收敛，不阻塞管理链路。
     */
    public void invalidate(String cacheKey) {
        caffeineCache.invalidate(cacheKey);
        try {
            stringRedisTemplate.delete(redisKey(cacheKey));
            log.info("规则缓存已精准失效：key={}", cacheKey);
        } catch (Exception e) {
            log.warn("Redis 失效删除失败，退化为 TTL 收敛：key={}", cacheKey, e);
        }
    }

    /** 仅走本地 Caffeine + DB 的降级快路径（总开关关闭 / 未拿到互斥锁时复用） */
    private <T> Optional<T> readThroughLocal(String cacheKey, Class<T> type, Supplier<T> dbLoader) {
        Object local = caffeineCache.getIfPresent(cacheKey);
        if (local == EMPTY) {
            return Optional.empty();
        }
        if (local != null) {
            return Optional.of(type.cast(local));
        }
        T loaded = dbLoader.get();
        // 降级路径也回填本地 Caffeine（含空值哨兵），至少省掉后续重复打库
        if (loaded != null) {
            caffeineCache.put(cacheKey, loaded);
        } else {
            caffeineCache.put(cacheKey, EMPTY);
        }
        return toOptional(type, loaded);
    }

    /** 两级未命中回源 DB 并回填，为进入互斥重建留的钩子 */
    private <T> Optional<T> rebuildFromDb(String cacheKey, Class<T> type, Supplier<T> dbLoader) {
        return loadAndBackfill(cacheKey, type, dbLoader);
    }

    /** 查库回填两级：DB 有值回填两级，无值回填空值哨兵（防穿透） */
    private <T> Optional<T> loadAndBackfill(String cacheKey, Class<T> type, Supplier<T> dbLoader) {
        T loaded = dbLoader.get();
        // 先回填本地（即使 Redis 写失败，本实例后续也能命中）
        caffeineCache.put(cacheKey, loaded != null ? loaded : EMPTY);
        try {
            String json = loaded != null ? objectMapper.writeValueAsString(loaded) : EMPTY_JSON;
            stringRedisTemplate.opsForValue().set(redisKey(cacheKey), json, baseTtl(type, loaded));
        } catch (Exception e) {
            log.warn("Redis 回填失败，仅本地缓存生效：key={}", cacheKey, e);
        }
        return toOptional(type, loaded);
    }

    /** 读 Redis：异常一律视为 miss 并降级，不阻断校验主链路 */
    @SuppressWarnings("unchecked")
    private <T> Object readRedis(String cacheKey, Class<T> type) {
        try {
            String json = stringRedisTemplate.opsForValue().get(redisKey(cacheKey));
            if (json == null) {
                return null;
            }
            if (EMPTY_JSON.equals(json)) {
                return EMPTY; // 空值哨兵：确认无数据
            }
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Redis 读取规则失败，降级走 DB 回源：key={}", cacheKey, e);
            return null;
        }
    }

    /** Redis 过期时间：DB 有值用基础 TTL（防雪崩抖动在阶段二叠加），空值用短 TTL 防穿透 */
    private Duration baseTtl(Class<?> type, Object loaded) {
        return loaded == null ? props.getEmptyTtl() : props.getTtl();
    }

    private <T> Optional<T> toOptional(Class<T> type, T loaded) {
        return loaded == null ? Optional.empty() : Optional.of(type.cast(loaded));
    }

    /** 拼装的 Redis 完整 key：keyPrefix + 业务 key（记录层区分业务域） */
    private String redisKey(String cacheKey) {
        return props.getKeyPrefix() + cacheKey;
    }
}