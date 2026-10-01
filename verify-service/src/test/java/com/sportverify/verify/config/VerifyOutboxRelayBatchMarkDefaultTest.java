package com.sportverify.verify.config;

import com.sportverify.verify.mq.VerifyOutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TASK-169 落地验证：verify.outbox.relay-batch-mark-enabled 默认开启的绑定测试。
 * <p>
 * 纯 JUnit 5 单元测试，不依赖 SpringBoot 上下文，不依赖中间件与网络。
 * </p>
 */
class VerifyOutboxRelayBatchMarkDefaultTest {

    @Test
    @DisplayName("1. classpath application.yml 声明 verify.outbox.relay-batch-mark-enabled=true")
    void testClasspathApplicationYmlRelayBatchMarkEnabled() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();

        Object val = null;
        for (PropertySource<?> source : sources) {
            val = source.getProperty("verify.outbox.relay-batch-mark-enabled");
            if (val != null) {
                break;
            }
        }
        assertNotNull(val, "verify.outbox.relay-batch-mark-enabled 必须在 classpath application.yml 中声明");
        assertEquals("true", String.valueOf(val), "verify.outbox.relay-batch-mark-enabled 默认值必须为 true");
    }

    @Test
    @DisplayName("2. 生产代码 VerifyOutboxRelay#relayBatchMarkEnabled 的 @Value 字面默认值仍为 false")
    void testVerifyOutboxRelayValueLiteralDefaultUnchanged() throws Exception {
        Field field = VerifyOutboxRelay.class.getDeclaredField("relayBatchMarkEnabled");
        Value value = field.getAnnotation(Value.class);
        assertNotNull(value, "relayBatchMarkEnabled 必须携带 @Value 注解");
        assertEquals("${verify.outbox.relay-batch-mark-enabled:false}", value.value(),
                "@Value 字面默认值必须逐字为 ${verify.outbox.relay-batch-mark-enabled:false}（证明生产代码未改默认值）");
    }

    @Test
    @DisplayName("3. classpath application.yml 中 ^verify: 与 ^spring: 根键各恰 1 个")
    void testApplicationYmlRootKeysCount() throws Exception {
        int verifyRootKeyCount = 0;
        int springRootKeyCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("application.yml").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.matches("^verify:\\s*")) {
                    verifyRootKeyCount++;
                } else if (line.matches("^spring:\\s*")) {
                    springRootKeyCount++;
                }
            }
        }
        assertEquals(1, verifyRootKeyCount, "classpath application.yml 中 ^verify: 根键必须恰好出现 1 次");
        assertEquals(1, springRootKeyCount, "classpath application.yml 中 ^spring: 根键必须恰好出现 1 次");
    }
}
