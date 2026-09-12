package com.sportverify.verify.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.sportverify.verify.config.RulesSnapshotCodec;
import com.sportverify.verify.config.VerifyProperties;
import com.sportverify.verify.entity.RuleVersion;
import com.sportverify.verify.mapper.RuleVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 规则灰度路由服务（规范「灰度采样路由」「规则快照隔离」，审批版 §7.4）。
 *
 * <p>路由规则：{@code floorMod(userId,100) < gray_ratio} 走灰度版本库内快照，
 * 否则走基线（ACTIVE 版本快照，尚无版本时回退 Nacos 实时配置）。
 * 同一 userId 的采样键恒定，分支不随请求时序/实例抖动。</p>
 *
 * <p>读路径全部经 Caffeine（写入 1 分钟过期，即规范「≤60s」上限）：
 * 路由行两把 key（灰度/基线）+ 每版本快照 key（{@code verify:rules:v{version}}），
 * 命中即不查库。版本生命周期操作（调比例/全量）负责失效路由 key——本实例立即生效，
 * 其余实例靠 TTL 收敛，这是回滚延迟的上界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleVersionService {

    /** 灰度路由缓存 key：缓存「当前采样中灰度版本」整行（含 gray_ratio），回滚/调比例后必须失效 */
    private static final String KEY_GRAY_ROUTE = "verify:rules:gray";

    /** 基线路由缓存 key：缓存「当前 ACTIVE 版本」整行，全量发布后必须失效 */
    private static final String KEY_ACTIVE_ROUTE = "verify:rules:active";

    /** 版本快照缓存 key 前缀：key 含版本号（rules:v{version}），版本间快照互不混淆 */
    private static final String KEY_SNAPSHOT_PREFIX = "verify:rules:v";

    /** 「无路由」哨兵：Cache 不缓存 null，空结果放哨兵，避免无版本阶段每次判定都查库 */
    private static final String NO_ROUTE = "-";

    private final RuleVersionMapper ruleVersionMapper;
    private final Cache<String, Object> caffeineCache;
    private final VerifyProperties verifyProperties;

    /**
     * 取 userId 当前应用的规则集（校验入口每次判定调用）：
     * <ol>
     *   <li>存在采样中的灰度版本且 floorMod(userId,100) &lt; gray_ratio → 库内灰度快照；</li>
     *   <li>否则基线：ACTIVE 版本快照；尚无版本 → Nacos 实时配置。</li>
     * </ol>
     *
     * <p>userId 为空（历史数据无归属）时防御性走基线，不让灰度路由阻断判定。</p>
     */
    public VerifyProperties getActiveRulesForUser(Long userId) {
        if (userId != null) {
            RuleVersion gray = cachedRoute(KEY_GRAY_ROUTE, ruleVersionMapper::selectSamplingGray);
            if (gray != null && gray.getGrayRatio() != null
                    && Math.floorMod(userId, 100) < gray.getGrayRatio()) {
                return snapshotRules(gray);
            }
        }
        return baselineRules();
    }

    // ===== 私有工具 =====

    /**
     * 基线规则集：全量 ACTIVE 版本快照（「新版本变为基线」，全量后仍执行已验证的规则，
     * 不受 Nacos 瞬时变更影响）；尚无版本时回退 Nacos 实时配置（阈值可配的原路径）。
     */
    private VerifyProperties baselineRules() {
        RuleVersion active = cachedRoute(KEY_ACTIVE_ROUTE, ruleVersionMapper::selectActive);
        return active == null ? verifyProperties : snapshotRules(active);
    }

    /**
     * 版本快照 → 规则执行对象。缓存 key 含 version：快照落库后不可变，
     * 命中即不查库、不重复反序列化。快照损坏不阻断校验主流程：
     * 记错误日志（可观测告警点）并降级 Nacos 实时配置。
     */
    private VerifyProperties snapshotRules(RuleVersion version) {
        String key = KEY_SNAPSHOT_PREFIX + version.getVersion();
        Object cached = caffeineCache.getIfPresent(key);
        if (cached instanceof VerifyProperties props) {
            return props;
        }
        try {
            VerifyProperties props = RulesSnapshotCodec.fromJson(version.getRulesJson());
            caffeineCache.put(key, props);
            return props;
        } catch (Exception e) {
            log.error("规则快照解析失败，降级 Nacos 实时配置：version={}", version.getVersion(), e);
            return verifyProperties;
        }
    }

    /**
     * 路由行缓存读取（灰度/基线各一把 key）：miss 查库回填，空结果放 NO_ROUTE 哨兵。
     * 注意缓存的行含 gray_ratio 快照——生命周期操作改库后必须失效对应 key，
     * 否则本实例在 TTL 内仍按旧比例采样。
     */
    private RuleVersion cachedRoute(String key, Supplier<RuleVersion> loader) {
        Object cached = caffeineCache.getIfPresent(key);
        if (cached instanceof RuleVersion rv) {
            return rv;
        }
        if (cached != null) {
            return null; // NO_ROUTE 哨兵：确认无路由
        }
        RuleVersion loaded = loader.get();
        caffeineCache.put(key, loaded != null ? loaded : NO_ROUTE);
        return loaded;
    }
}
