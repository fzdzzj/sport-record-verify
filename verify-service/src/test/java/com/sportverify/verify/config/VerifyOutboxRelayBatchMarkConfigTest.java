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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TASK-165 落地验证：VerifyOutboxRelay 分块批量标记默认配置与保护测试.
 */
class VerifyOutboxRelayBatchMarkConfigTest {

    @Test
    @DisplayName("1. 反射断言两个 @Value 默认值字面量完全一致")
    void testValueAnnotationLiterals() throws Exception {
        Field enabledField = VerifyOutboxRelay.class
                .getDeclaredField("relayBatchMarkEnabled");
        Value enabledValue = enabledField.getAnnotation(Value.class);
        assertNotNull(enabledValue,
                "relayBatchMarkEnabled 必须有 @Value 注解");
        assertEquals("${verify.outbox.relay-batch-mark-enabled:false}",
                enabledValue.value(),
                "relayBatchMarkEnabled 默认必须为 false");

        Field chunkSizeField = VerifyOutboxRelay.class
                .getDeclaredField("relayBatchMarkChunkSize");
        Value chunkSizeValue = chunkSizeField.getAnnotation(Value.class);
        assertNotNull(chunkSizeValue,
                "relayBatchMarkChunkSize 必须有 @Value 注解");
        assertEquals("${verify.outbox.relay-batch-mark-chunk-size:25}",
                chunkSizeValue.value(),
                "relayBatchMarkChunkSize 默认必须为 25");
    }

    @Test
    @DisplayName("2. classpath application.yml 声明 relay-batch-mark-enabled=true，chunk-size 仍不声明（走 @Value 默认 25）")
    void testClasspathApplicationYmlDeclaresBatchMarkEnabled()
            throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "application.yml",
                new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();

        Object enabled = null;
        for (PropertySource<?> source : sources) {
            enabled = source.getProperty(
                    "verify.outbox.relay-batch-mark-enabled");
            if (enabled != null) {
                break;
            }
        }
        assertNotNull(enabled,
                "application.yml 必须声明 relay-batch-mark-enabled");
        assertEquals("true", String.valueOf(enabled),
                "relay-batch-mark-enabled 必须为 true（TASK-169 落地）");

        for (PropertySource<?> source : sources) {
            assertNull(source.getProperty(
                    "verify.outbox.relay-batch-mark-chunk-size"),
                    "application.yml 不得声明 relay-batch-mark-chunk-size（走 @Value 默认 25）");
        }
    }

    @Test
    @DisplayName("3. classpath application.yml 中 ^verify: 与 ^spring: 根键各恰 1 个")
    void testApplicationYmlRootKeysCount() throws Exception {
        int verifyRootKeyCount = 0;
        int springRootKeyCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ClassPathResource("application.yml")
                                .getInputStream(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.matches("^verify:\\s*")) {
                    verifyRootKeyCount++;
                } else if (line.matches("^spring:\\s*")) {
                    springRootKeyCount++;
                }
            }
        }
        assertEquals(1, verifyRootKeyCount,
                "application.yml 中 ^verify: 根键必须恰好出现 1 次");
        assertEquals(1, springRootKeyCount,
                "application.yml 中 ^spring: 根键必须恰好出现 1 次");
    }

    @Test
    @DisplayName("4. 保护 TASK-163 落地件：relay-interval-ms 仍为 500")
    void testRelayIntervalDefaultPreserved() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "application.yml",
                new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();

        Object val = null;
        for (PropertySource<?> source : sources) {
            val = source.getProperty("verify.outbox.relay-interval-ms");
            if (val != null) {
                break;
            }
        }
        assertNotNull(val,
                "verify.outbox.relay-interval-ms 必须在 application.yml 中声明");
        assertEquals(500, Integer.parseInt(String.valueOf(val)),
                "relay-interval-ms 默认值必须仍为 500");
    }
}
