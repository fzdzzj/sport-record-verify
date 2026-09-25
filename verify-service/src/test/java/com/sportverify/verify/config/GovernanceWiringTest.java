package com.sportverify.verify.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.common.governance.GovernanceApiAuthFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.IOException;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TASK-136 第二阶段：治理凭证过滤器真实 yml 装配验收。
 *
 * <p>把本服务真实 application.yml（classpath 根）解析为属性源注入测试环境，
 * 使 {@code app.governance.protected-paths}（/api/appeals/**、/rules/**）按部署配置绑定后
 * 直接驱动过滤器：无令牌访问治理路径必须 403。不起完整上下文（不连 MySQL/Redis/RocketMQ）。</p>
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
    void realYmlRejectsAppealsAndRulesWithoutToken() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GovernanceApiAuthFilter.class);
            GovernanceApiAuthFilter filter = context.getBean(GovernanceApiAuthFilter.class);
            assertEquals(403, call(filter, "POST", "/api/appeals/1/review").getStatus());
            assertEquals(403, call(filter, "PATCH", "/rules/versions/1/gray").getStatus());
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
