package com.sportverify.leaderboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.common.governance.GovernanceApiAuthFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TASK-136 第二阶段：治理凭证过滤器真实 yml 装配验收（精确保护边界）。
 *
 * <p>把本服务真实 application.yml（classpath 根）解析为属性源注入测试环境，
 * 绑定 {@code app.governance.protected-paths: /api/leaderboard/daily}（精确匹配）后驱动过滤器：
 * 无令牌访问日报 403；总榜 GET /api/leaderboard 不得被误伤。不起完整上下文。</p>
 */
class GovernanceWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                YamlPropertiesFactoryBean yml = new YamlPropertiesFactoryBean();
                yml.setResources(new ClassPathResource("application.yml"));
                context.getEnvironment().getPropertySources().addFirst(
                        new PropertiesPropertySource("realApplicationYml", Objects.requireNonNull(yml.getObject())));
            })
            .withBean(ObjectMapper.class)
            .withUserConfiguration(GovernanceApiAuthFilter.class);

    @Test
    void realYmlRejectsDailyWithoutToken() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GovernanceApiAuthFilter.class);
            GovernanceApiAuthFilter filter = context.getBean(GovernanceApiAuthFilter.class);
            assertEquals(403, call(filter, "GET", "/api/leaderboard/daily").getStatus());
        });
    }

    @Test
    void realYmlLeavesOverallLeaderboardUnprotected() {
        runner.run(context -> {
            GovernanceApiAuthFilter filter = context.getBean(GovernanceApiAuthFilter.class);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/leaderboard");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertNotNull(chain.getRequest(), "总榜请求应放行到后续链路，不被治理过滤器拦截");
            assertEquals(200, response.getStatus());
        });
    }

    private static MockHttpServletResponse call(GovernanceApiAuthFilter filter, String method, String uri) {
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            return response;
        } catch (ServletException | IOException e) {
            throw new IllegalStateException("过滤器执行失败: " + uri, e);
        }
    }
}
