package com.sportverify.user.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 数据源口令环境变量绑定判别式测试（TASK-190，findings F02）。
 *
 * <p>纯 JVM 确定性，不启动 Spring 上下文：读真实 application.yml，经 YamlPropertySourceLoader
 * 装入 MutablePropertySources，再由 Binder + PropertySourcesPlaceholdersResolver 绑定
 * DataSourceProperties。两个用例分别守护：</p>
 * <ol>
 *   <li>注入环境变量后 {@code spring.datasource.password} 的占位符被解析为注入值——
 *       若某次改动把占位符退回字面量，字面量不理会环境变量，此处即红；</li>
 *   <li>仅装 yaml 源（不含任何系统环境源，隔离宿主机同名 shell 变量）时回退文件声明的默认口令。</li>
 * </ol>
 *
 * <p>构造要点：Binder 必须带 PropertySourcesPlaceholdersResolver，否则占位符按字面量绑定，
 * 用例一将出假绿。</p>
 */
class DataSourcePasswordEnvBindingTest {

    private static final String PASSWORD_KEY = "MYSQL_ROOT_PASSWORD";
    private static final String INJECTED = "it-injected-credential";
    private static final String FALLBACK = "root";

    @Test
    @DisplayName("环境变量注入时口令解析为注入值（占位符回归字面量即红）")
    void credentialInjectedFromEnvironment() throws IOException {
        MutablePropertySources propertySources = yamlSources();
        propertySources.addFirst(new MapPropertySource("it-injected", Map.of(PASSWORD_KEY, INJECTED)));

        DataSourceProperties properties = bind(propertySources);
        assertEquals(INJECTED, properties.getPassword(),
                "口令应经占位符解析为注入值；绑定出字面量说明占位符未生效");
    }

    @Test
    @DisplayName("未注入环境变量时回退默认口令")
    void localFallbackDefaultWithoutInjection() throws IOException {
        MutablePropertySources propertySources = yamlSources();

        DataSourceProperties properties = bind(propertySources);
        assertEquals(FALLBACK, properties.getPassword(), "未注入时应回退 application.yml 声明的默认口令");
    }

    private static MutablePropertySources yamlSources() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        for (PropertySource<?> source : sources) {
            propertySources.addLast(source);
        }
        return propertySources;
    }

    private static DataSourceProperties bind(MutablePropertySources propertySources) {
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources));
        DataSourceProperties properties = binder.bind("spring.datasource",
                Bindable.of(DataSourceProperties.class)).get();
        assertNotNull(properties, "DataSourceProperties 绑定结果不应为空");
        return properties;
    }
}
