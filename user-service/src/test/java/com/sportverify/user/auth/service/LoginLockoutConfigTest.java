package com.sportverify.user.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 登录锁定兜底阈值资源配置断言（E15，TASK-256）。
 *
 * <p>纯 JVM 确定性、不启动 Spring 上下文：经 {@link YamlPropertySourceLoader} 解析
 * classpath {@code application.yml} 属性树，再由 {@link Binder} 绑定
 * {@code app.auth.lock.threshold}，断言其为全账号兜底阈值 20（TASK-193 语义）——
 * 若某次改动把 yml 显式覆盖打回旧值 5（覆盖掉 {@code AuthService} 的代码默认 20，
 * 造成 F11 账号 DoS 回归），此判别式即红。</p>
 *
 * <p>与 {@code AuthService} 语义对齐：既不覆盖也不断言 {@code ip-threshold}（单
 * (phone,ip) 组合阈值，yml 未显式登记、由代码默认 5 生效）。</p>
 */
class LoginLockoutConfigTest {

    @Test
    void lockThreshold_isFallbackAcrossIp_20() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : loaded) {
            sources.addLast(source);
        }

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        Integer threshold = binder.bind("app.auth.lock.threshold", Integer.class).orElse(null);

        // 全账号兜底阈值须为 20（TASK-193 既有语义与 AuthService 代码默认对齐）；5 为旧单维度口径回归
        assertEquals(20, threshold,
                "app.auth.lock.threshold 应保持全账号兜底阈值 20，yml 覆盖回退 5 即 F11 账号 DoS 回归");
    }
}
