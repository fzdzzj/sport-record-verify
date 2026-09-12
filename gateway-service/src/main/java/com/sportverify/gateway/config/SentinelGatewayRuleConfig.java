package com.sportverify.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * 网关 Sentinel 限流规则（压测变更 spec「限流与熔断验证」：网关限流对齐 5k QPS 目标）。
 *
 * <p>规则以「路由 ID」为粒度（RESOURCE_MODE_ROUTE_ID），对 /record、/user、/verify
 * 三条业务路由统一限 QPS；阈值经 {@code app.gateway.rate-limit.qps} 配置——</p>
 * <ul>
 *   <li>默认 5000（审批版 §8.3「Sentinel 限流 5k QPS」配置基线）；</li>
 *   <li>压测验证拦截链路时用低阈值启动（如 {@code --app.gateway.rate-limit.qps=100}），
 *       超限请求由 Sentinel SCG adapter 以 HTTP 429 拒绝（见 application.yml
 *       {@code spring.cloud.sentinel.scg.fallback}），证明限流生效；</li>
 *   <li>规则纯内存加载（无 Dashboard/数据源依赖），重启生效，后续可平滑切换为
 *       Nacos 动态数据源。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SentinelGatewayRuleConfig {

    /** 业务路由 ID（与 application.yml 路由表一致） */
    private static final String[] ROUTE_IDS = {
            "route-record-service", "route-user-service", "route-verify-service"
    };

    /** 限流阈值 QPS（默认 5000，对齐审批版 §8.3；压测验证时调低） */
    @Value("${app.gateway.rate-limit.qps:5000}")
    private long qps;

    /** 统计窗口（秒），默认 1s */
    @Value("${app.gateway.rate-limit.interval-seconds:1}")
    private int intervalSeconds;

    @Bean
    public CommandLineRunner loadGatewayFlowRules() {
        return args -> {
            Set<GatewayFlowRule> rules = new HashSet<>();
            for (String routeId : ROUTE_IDS) {
                GatewayFlowRule rule = new GatewayFlowRule(routeId)
                        .setCount(qps)
                        .setIntervalSec(intervalSeconds)
                        .setGrade(RuleConstant.FLOW_GRADE_QPS); // QPS 流控（非并发线程数），窗口内超限即 429
                rules.add(rule);
            }
            GatewayRuleManager.loadRules(rules);
            log.info("Sentinel 网关限流规则已加载：routes={}, qps={}, interval={}s", (Object) ROUTE_IDS, qps, intervalSeconds);
        };
    }
}
