package com.sportverify.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网关 Sentinel 限流规则（压测变更 spec「限流与熔断验证」：网关限流对齐 5k QPS 目标；本变更
 * `add-sentinel-dynamic-rules`：规则切 Nacos 动态数据源，改阈值不重启即生效）。
 *
 * <p>规则以「路由 ID」为粒度（RESOURCE_MODE_ROUTE_ID），并落到 Nacos 的
 * {@code app.sentinel.nacos.data-id}（默认 {@code gateway-flow-rules}），经
 * {@link GatewayRuleManager#register2Property} 绑定为动态规则源：</p>
 * <ul>
 *   <li>启动即刻读 Nacos 当前规则（JSON 数组，每个元素为 {@link GatewayFlowRule}）；改动经
 *       Nacos 推送 → converter → 属性回调 → {@code GatewayRuleManager} 就地更新，无需重启；</li>
 *   <li>兜底：若 Nacos 无该 dataId（首次）/解析失败，先落一份代码默认（{@code app.gateway.rate-limit.qps}
 *       =5000，对齐审批版 §8.3「Sentinel 限流 5k QPS」基线），保证限流不缺省；
 *       解析失败时 converter 返回 null 维持上一版规则不抖动；</li>
 *   <li>阈值默认 5000；压测验证拦截链路时在 Nacos 把 count 调低（如 100），超限请求由
 *       Sentinel SCG adapter 以 HTTP 429 拒绝（见 application.yml {@code spring.cloud.sentinel.scg.fallback}），
 *       证明「改阈值 → 不重启 → 复测行为变化」。（scg adapter 的流控依赖
 *       {@code spring.cloud.alibaba.sentinel-gateway}，见 gateway-service pom）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SentinelGatewayRuleConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 业务路由 ID（与 application.yml 路由表一致） */
    private static final String[] ROUTE_IDS = {
            "route-record-service", "route-user-service", "route-verify-service"
    };

    /** 限流阈值 QPS（兜底默认 5000，对齐审批版 §8.3，仅在 Nacos 无规则或缺省时使用） */
    @Value("${app.gateway.rate-limit.qps:5000}")
    private long qps;

    /** 统计窗口（秒），默认 1s */
    @Value("${app.gateway.rate-limit.interval-seconds:1}")
    private int intervalSeconds;

    /** Nacos 数据源（变更 add-sentinel-dynamic-rules；配合已有 Nacos 8848） */
    @Value("${app.sentinel.nacos.server-addr:127.0.0.1:8848}")
    private String nacosServerAddr;

    @Value("${app.sentinel.nacos.group-id:DEFAULT_GROUP}")
    private String nacosGroupId;

    @Value("${app.sentinel.nacos.data-id:gateway-flow-rules}")
    private String nacosDataId;

    @Bean
    public CommandLineRunner loadGatewayFlowRules() {
        return args -> {
            // 兜底默认：Nacos 无该 dataId 或首次读空时，保证限流基线不缺省
            GatewayRuleManager.loadRules(defaultRules());
            log.info("Sentinel 网关限流兜底默认：routes={}, qps={}, interval={}s", (Object) ROUTE_IDS, qps, intervalSeconds);

            // 注册 Nacos 动态规则源：改 Nacos 里 count 即推送更新，不重启生效
            ReadableDataSource<String, Set<GatewayFlowRule>> ds = new NacosDataSource<>(
                    nacosServerAddr, nacosGroupId, nacosDataId, this::toGatewayFlowRules);
            GatewayRuleManager.register2Property(ds.getProperty());
            log.info("已注册 Nacos 网关流控数据源：server={} group={} dataId={}（改阈值不重启即生效）",
                    nacosServerAddr, nacosGroupId, nacosDataId);
        };
    }

    private Set<GatewayFlowRule> defaultRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();
        for (String routeId : ROUTE_IDS) {
            GatewayFlowRule rule = new GatewayFlowRule(routeId)
                    .setCount(qps)
                    .setIntervalSec(intervalSeconds)
                    .setGrade(RuleConstant.FLOW_GRADE_QPS); // QPS 流控（非并发线程数），窗口内超限即 429
            rules.add(rule);
        }
        return rules;
    }

    /** Nacos JSON 数组 → Set<GatewayFlowRule>；解析失败返回 null（保持上一版规则，不因坏配置抖断限流） */
    private Set<GatewayFlowRule> toGatewayFlowRules(String source) {
        try {
            List<GatewayFlowRule> list = MAPPER.readValue(source, new TypeReference<>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            log.warn("解析 Nacos 网关流控规则失败，保持上一版规则：{}", e.getMessage());
            return null;
        }
    }
}
