package com.sportverify.verify.consumer;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.sportverify.verify.service.RuleCacheService;
import com.sportverify.verify.service.RuleVersionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Nacos 规则配置变更监听（规范「Nacos 变更精准失效」）。
 *
 * <p>为什么需要：规则/灰度配置存在 Nacos（verify-service.yml），该配置可能被运维直接修改，
 * 绕过版本生命周期操作——此时既有广播（只由管理操作触发）不会发失效事件，若只靠 TTL 兜底
 * 其余实例最多落后 60s。注册 Nacos 配置监听后，配置一变即精准清「Caffeine + Redis」两级，
 * 秒级回源到最新值，TTL 仅做最后兜底。</p>
 *
 * <p>失效对象与版本变更一致：灰度/基线两把路由 key（版本快照落库后不可变，不在失效范围）。
 * 注册失败只告警不阻断——Nacos 不可用/配置不存在时退化为 TTL 收敛，不新增故障面。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleNacosConfigListener {

    /** 需监听配置变更的 Nacos dataId（与 {@code spring.config.import: optional:nacos:verify-service.yml} 一致） */
    private static final String RULE_CONFIG_DATAID = "verify-service.yml";

    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private final NacosConfigManager nacosConfigManager;
    private final RuleCacheService ruleCacheService;

    /** 注册配置监听：任何配置变更（含规则阈值/灰度参数）都精准失效路由两级缓存 */
    @PostConstruct
    public void register() {
        try {
            nacosConfigManager.getConfigService().addListener(RULE_CONFIG_DATAID, DEFAULT_GROUP,
                    new Listener() {
                        @Override
                        public Executor getExecutor() {
                            return null; // 默认线程池执行回调
                        }

                        @Override
                        public void receiveConfigInfo(String configInfo) {
                            invalidateRuleCaches();
                        }
                    });
            log.info("Nacos 规则配置变更监听已注册：dataId={}, group={}", RULE_CONFIG_DATAID, DEFAULT_GROUP);
        } catch (Exception e) {
            // Nacos 不可用/配置表不存在：监听注册失败，退化为 TTL 兜底收敛，不阻断校验主链路
            log.warn("Nacos 配置监听注册失败，退化为 TTL 收敛：dataId={}", RULE_CONFIG_DATAID, e);
        }
    }

    /** 配置变更 → 精准失效路由两级缓存（本地 Caffeine + 跨实例 Redis DEL） */
    private void invalidateRuleCaches() {
        ruleCacheService.invalidate(RuleVersionService.KEY_GRAY_ROUTE);
        ruleCacheService.invalidate(RuleVersionService.KEY_ACTIVE_ROUTE);
        log.info("Nacos 配置变更，规则路由两级缓存已精准失效");
    }
}