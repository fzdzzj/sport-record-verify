package com.sportverify.record.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主 sharding.yaml 默认值判别式（TASK-139，规范「分片 SQL 展示默认关闭」）。
 *
 * <p>注意classpath 上的 sharding.yaml 在测试期会被 src/test/resources 同名资源覆盖
 * （{@link ShardingDataSourceConfigTest} 依赖该覆盖测占位符替换），因此本测试直接按
 * 文件路径读取模块主资源，验证生产默认：{@code sql-show} 必须是
 * {@code ${SS_SQL_SHOW:false}} 占位符（默认关闭），不得写字面量 true。</p>
 */
class MainShardingYamlDefaultsTest {

    private String readMainShardingYaml() throws IOException {
        Path inModule = Path.of("src/main/resources/sharding.yaml");
        Path inRepo = Path.of("record-service/src/main/resources/sharding.yaml");
        Path path = Files.exists(inModule) ? inModule : inRepo;
        assertTrue(Files.exists(path),
                "找不到主 sharding.yaml（候选：" + inModule + " / " + inRepo + "）");
        return Files.readString(path);
    }

    /** 默认关闭：sql-show 必须是 ${SS_SQL_SHOW:false}，未设环境变量时解析为 false */
    @Test
    void sqlShow_defaultsToFalseViaPlaceholder() throws IOException {
        String yaml = readMainShardingYaml();
        assertTrue(yaml.contains("sql-show: ${SS_SQL_SHOW:false}"),
                "主 sharding.yaml 的 sql-show 必须是 ${SS_SQL_SHOW:false}（默认关），实得："
                        + extractSqlShowLine(yaml));
    }

    /** 不得把字面量 true 写进仓库默认值 */
    @Test
    void sqlShow_literalTrueForbidden() throws IOException {
        String yaml = readMainShardingYaml();
        assertFalse(Pattern.compile("sql-show:\\s*true").matcher(yaml).find(),
                "主 sharding.yaml 不得把 sql-show 写成字面量 true，实得：" + extractSqlShowLine(yaml));
    }

    private String extractSqlShowLine(String yaml) {
        return java.util.Arrays.stream(yaml.split("\r?\n"))
                .filter(line -> line.contains("sql-show"))
                .findFirst().orElse("(none)");
    }

    /**
     * TASK-140：内层池类必须是带物理连接获取计时的包装类（诊断位置）——
     * ShardingSphere 按 dataSourceClassName 反射建池，回退成裸 HikariDataSource
     * 会让 connWait 段静默失效（无样本但服务照常运行），故以文件判别式锁住接线。
     */
    @Test
    void dataSourceClassName_wiredToTimingWrapper() throws IOException {
        String yaml = readMainShardingYaml();
        assertTrue(yaml.contains("dataSourceClassName: com.sportverify.record.config.TimingHikariDataSource"),
                "主 sharding.yaml 的池类必须指向 TimingHikariDataSource（connWait 计时位置），"
                        + "实得：" + extractLine(yaml, "dataSourceClassName"));
    }

    private String extractLine(String yaml, String keyword) {
        return java.util.Arrays.stream(yaml.split("\r?\n"))
                .filter(line -> line.contains(keyword))
                .findFirst().orElse("(none)");
    }
}
