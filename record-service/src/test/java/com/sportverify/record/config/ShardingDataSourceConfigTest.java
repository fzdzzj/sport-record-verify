package com.sportverify.record.config;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * ShardingDataSourceConfig 占位符替换逻辑单元测试。
 *
 * <p>用 src/test/resources/sharding.yaml 覆盖 classpath 同名资源（纯单测，无 Spring 上下文，
 * 不影响其他用例），确定性覆盖 {@code loadShardingYaml} 的两类替换：
 * {@code ${VAR:default}} 未设环境变量时取默认值、{@code ${VAR}} 无默认值未设时替换为空串
 * （均不残留原样占位符，避免 ShardingSphere 拿到非法 ${} 字符串进入数值字段）。</p>
 */
class ShardingDataSourceConfigTest {

    private String loadYaml() {
        ShardingDataSourceConfig config = new ShardingDataSourceConfig();
        byte[] yaml = ReflectionTestUtils.invokeMethod(config, "loadShardingYaml");
        return new String(yaml, StandardCharsets.UTF_8);
    }

    /** ${VAR:default}：环境变量未设置 → 用冒号后默认值 */
    @Test
    void placeholderWithDefault_unsetUsesDefault() {
        String content = loadYaml();
        assertTrue(content.contains("12.34.56.78"), "应替换为默认值 12.34.56.78");
    }

    /** ${VAR}：无默认值且未设置 → 替换为空串，不残留原样占位符 */
    @Test
    void placeholderWithoutDefault_unsetReplacesEmpty() {
        String content = loadYaml();
        // 无默认值占位符整体被清空
        assertFalse(content.contains("${SPORTVERIFY_TEST_NODEFAULT_9F3E}"),
                "不应残留 ${VAR} 原样字符串");
        // 单向拼接处首尾的空占位符均被清掉（pre--post 保留两侧分隔符）
        assertTrue(content.contains("pre--post"), "空替换应保留两侧分隔符");
    }

    /** 整体不残留任何 ${ 占位符（正则未漏匹配） */
    @Test
    void noRawPlaceholderResidue() {
        String content = loadYaml();
        assertFalse(content.contains("${"), "不应有未替换的 ${ 占位符残留");
    }

    /** 真变量哨兵 ${SHARDING_MYSQL_HOST:127.0.0.1}：替换结果与当前 env（若设）或默认 127.0.0.1 一致，且不残留占位符 */
    @Test
    void realHostVariable_resolvesToEnvOrDefault() {
        String content = loadYaml();
        String host = System.getenv("SHARDING_MYSQL_HOST");
        String expected = (host == null || host.isEmpty()) ? "127.0.0.1" : host;
        assertTrue(content.contains("sharding-host: " + expected),
                "真实变量应替换为 env(" + host + ") 或默认 127.0.0.1，实得需与之一致");
        assertFalse(content.contains("${SHARDING_MYSQL_HOST"), "不应残留真实变量原样占位符");
    }

    /** 真变量哨兵默认分支：env 未设 → 取 127.0.0.1（宿主直跑口径零变化）；设 env → 取 env 值（容器口径覆盖） */
    @Test
    void realHostVariable_defaultOrOverrideBranch() {
        String content = loadYaml();
        String host = System.getenv("SHARDING_MYSQL_HOST");
        if (host == null || host.isEmpty()) {
            assertTrue(content.contains("sharding-host: 127.0.0.1"),
                    "未设 SHARDING_MYSQL_HOST 应保留默认值 127.0.0.1");
        } else {
            assertTrue(content.contains("sharding-host: " + host),
                    "设 SHARDING_MYSQL_HOST=" + host + " 应替换默认值");
        }
    }
}