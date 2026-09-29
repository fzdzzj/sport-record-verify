package com.sportverify.verify.config;

import com.sportverify.verify.mq.VerifyOutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TASK-163 落地验证：verify.outbox.relay-interval-ms 默认值绑定测试。
 * <p>
 * 纯 JUnit 5 单元测试，不依赖 SpringBoot 上下文，不依赖中间件与网络。
 * </p>
 */
class VerifyOutboxRelayIntervalDefaultTest {

    @Test
    @DisplayName("1. classpath application.yml 声明 verify.outbox.relay-interval-ms=500")
    void testClasspathApplicationYmlRelayIntervalDefault() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();

        Object val = null;
        for (PropertySource<?> source : sources) {
            val = source.getProperty("verify.outbox.relay-interval-ms");
            if (val != null) {
                break;
            }
        }
        assertNotNull(val, "verify.outbox.relay-interval-ms 必须在 classpath application.yml 中声明");
        assertEquals(500, Integer.parseInt(String.valueOf(val)), "verify.outbox.relay-interval-ms 默认值必须为 500");
    }

    @Test
    @DisplayName("2. 生产代码 VerifyOutboxRelay#relay 注解默认值仍保持 5000/10000 逐字不变")
    void testVerifyOutboxRelayScheduledAnnotationDefaultsUnchanged() throws Exception {
        Method relayMethod = VerifyOutboxRelay.class.getMethod("relay");
        Scheduled scheduled = relayMethod.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "VerifyOutboxRelay#relay 必须携带 @Scheduled 注解");

        assertEquals("${verify.outbox.relay-interval-ms:5000}", scheduled.fixedDelayString(),
                "@Scheduled.fixedDelayString 必须逐字为 ${verify.outbox.relay-interval-ms:5000}");
        assertEquals("${verify.outbox.relay-initial-delay-ms:10000}", scheduled.initialDelayString(),
                "@Scheduled.initialDelayString 必须逐字为 ${verify.outbox.relay-initial-delay-ms:10000}");
    }

    @Test
    @DisplayName("3. classpath application.yml 中 ^verify: 根键恰好出现一次")
    void testApplicationYmlHasExactlyOneVerifyRootKey() throws Exception {
        int verifyRootKeyCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("application.yml").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.matches("^verify:\\s*")) {
                    verifyRootKeyCount++;
                }
            }
        }
        assertEquals(1, verifyRootKeyCount, "classpath application.yml 中 ^verify: 根键必须恰好出现 1 次（防止重复根键静默覆盖）");
    }
}
