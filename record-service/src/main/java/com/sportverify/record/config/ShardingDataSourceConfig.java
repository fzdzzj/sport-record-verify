package com.sportverify.record.config;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * ShardingSphere 代理数据源配置（规范「轨迹分片存储」）。
 *
 * <p>ShardingSphere 5.4.0+ 移除了 spring-boot-starter 模块（见 ADR-0001），
 * {@code spring.shardingsphere.*} 属性不再被解析；正确做法是用
 * shardingsphere-jdbc-core 自带的 YAML API 直接构建代理 DataSource。</p>
 *
 * <ul>
 *   <li>track_point：按 {@code user_id % 16} 路由到 track_point_0..15（逻辑表 → 16 张物理表）；</li>
 *   <li>sport_record 等未声明分片的表经 {@code !SINGLE} 声明落默认数据源单表
 *       （规范「记录本身不分片」）；</li>
 *   <li>本 Bean 是全工程唯一 DataSource，MyBatis-Plus 分页插件自动绑定代理
 *       （规范「分页插件正确绑定 ShardingSphere 代理数据源」）。</li>
 * </ul>
 */
@Configuration
public class ShardingDataSourceConfig {

    /** sharding.yaml 位于 classpath 根（与 application.yml 同级） */
    private static final String SHARDING_YAML = "sharding.yaml";

    /**
     * 构建 ShardingSphere 代理 DataSource。
     *
     * <p>{@code createDataSource(byte[])} 将 YAML 反序列化为规则上下文并创建 JDBC 代理；
     * 底层 Hikari 连接池懒初始化（sharding.yaml 中 initializationFailTimeout=-1），
     * MySQL 未启动时服务仍可启动，首次访问才失败（与骨架容错策略一致）。</p>
     */
    @Bean
    public DataSource dataSource() throws Exception {
        // ShardingSphere YAML 不解析 ${} 占位符，故在读取时做环境变量替换（见 loadShardingYaml）
        byte[] yaml = loadShardingYaml();
        return YamlShardingSphereDataSourceFactory.createDataSource(yaml);
    }

    /**
     * 读取 sharding.yaml，并把全部 {@code ${ENV:default}} 占位符替换为环境变量值。
     *
     * <p>ShardingSphere 的 YAML 引擎不解析 ${} 占位（Spring 的 PropertyResolver 也
     * 不参与该流程），故在读取阶段手动完成。已用于：MYSQL_PASSWORD（密码）、
     * MYSQL_PORT（宿主机已有 MySQL 占 3306 时换端口）、MYSQL_POOL_SIZE
     * （压测「连接池调优案例」注入口径）。未设置的环境变量取冒号后默认值。</p>
     */
    private byte[] loadShardingYaml() throws IOException {
        byte[] raw;
        try (var in = new ClassPathResource(SHARDING_YAML).getInputStream()) {
            raw = in.readAllBytes();
        }
        String content = new String(raw, StandardCharsets.UTF_8);
        // ${VAR:default} 带默认值
        content = java.util.regex.Pattern
                .compile("\\$\\{([A-Z0-9_]+):([^}]*)\\}")
                .matcher(content)
                .replaceAll(match -> {
                    String v = System.getenv(match.group(1));
                    return v == null || v.isEmpty() ? match.group(2) : java.util.regex.Matcher.quoteReplacement(v);
                });
        // ${VAR} 无默认值：设置则替换，未设置替换为空串（避免原样字符串进入数值字段）
        content = java.util.regex.Pattern
                .compile("\\$\\{([A-Z0-9_]+)\\}")
                .matcher(content)
                .replaceAll(match -> {
                    String v = System.getenv(match.group(1));
                    return v == null ? "" : java.util.regex.Matcher.quoteReplacement(v);
                });
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
