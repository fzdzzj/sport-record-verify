package com.sportverify.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sentinel 网关限流兜底路由「配置 ↔ 代码」一致性断言（TASK-124）。
 *
 * <p>{@code SentinelGatewayRuleConfig.ROUTE_IDS} 是 Nacos 无规则时代的限流兜底默认，
 * javadoc 声称「与 application.yml 路由表一致」——但声明不机械生效：yml 加一条路由而
 * ROUTE_IDS 漏配，代码照样全绿，该路由在 Nacos 首启/清空场景下无限流兜底。</p>
 *
 * <p>本类承 {@code LeaderboardDailyAdminOnlyTest}「yml 灌入、防硬编码漂移」先例：
 * 从 classpath 的 {@code application.yml} 提取路由表的真实路由 ID（判别式数据不硬编码），
 * 与 {@code defaultRules()} 实际产物双向比对——yml 加路由兜底漏配、或兜底多配 yml 已删，
 * 两条断言当场红，缺失/多余清单直接打在断言消息里。</p>
 */
class SentinelRouteCoverageTest {

    /** 路由表条目形如 "- id: route-xxx-service"；本 yml 中 "- id:" 仅 spring.cloud.gateway.routes 使用 */
    private static final Pattern ROUTE_ID = Pattern.compile("(?m)^\\s*-\\s*id:\\s*(\\S+)\\s*$");

    /** classpath application.yml 路由表的真实路由 ID（有序，便于缺失清单按表序展示） */
    private static List<String> ymlRouteIds() throws IOException {
        String yml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return ROUTE_ID.matcher(yml).results()
                .map(m -> m.group(1))
                .collect(Collectors.toList());
    }

    /** 兜底默认规则集的实际路由覆盖（走 defaultRules() 本体，不读 ROUTE_IDS 镜像） */
    private static Set<String> fallbackRouteIds() {
        Set<GatewayFlowRule> rules = ReflectionTestUtils.invokeMethod(
                new SentinelGatewayRuleConfig(), "defaultRules");
        assertTrue(rules != null && !rules.isEmpty(), "defaultRules() 不得为空：兜底基线不缺省");
        return rules.stream().map(GatewayFlowRule::getResource).collect(Collectors.toSet());
    }

    @Test
    void fallbackRulesCoverEveryRouteInYml() throws IOException {
        List<String> ymlRoutes = ymlRouteIds();
        // 判别式自检：提取必须锚到路由表而非其它 "- id:" 结构（全部路由按惯例以 route- 命名）
        assertFalse(ymlRoutes.isEmpty(), "未从 application.yml 提取到任何路由 ID，判别式锚点失效");
        assertTrue(ymlRoutes.stream().allMatch(id -> id.startsWith("route-")),
                "提取到的非 route- 前缀条目说明 yml 出现新的 '- id:' 结构，判别式需同步："
                        + ymlRoutes.stream().filter(id -> !id.startsWith("route-")).toList());

        Set<String> fallback = fallbackRouteIds();
        Set<String> missing = new TreeSet<>(ymlRoutes);
        missing.removeAll(fallback);
        assertTrue(missing.isEmpty(),
                "Sentinel 兜底路由缺失（Nacos 无规则时这些路由无限流兜底），缺失=" + missing
                        + "，兜底实际覆盖=" + new TreeSet<>(fallback)
                        + "，yml 路由表=" + ymlRoutes);
    }

    @Test
    void fallbackRulesContainNoRouteBeyondYml() throws IOException {
        Set<String> ymlRoutes = new TreeSet<>(ymlRouteIds());
        Set<String> fallback = new TreeSet<>(fallbackRouteIds());
        Set<String> extra = new TreeSet<>(fallback);
        extra.removeAll(ymlRoutes);
        assertTrue(extra.isEmpty(),
                "兜底路由超出 yml 路由表（javadoc 声称『与 application.yml 路由表一致』），"
                        + "多余=" + extra + "，yml 路由表=" + ymlRoutes);
    }
}
