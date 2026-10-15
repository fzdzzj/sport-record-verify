package com.sportverify.mapmatch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 匹配算法参数默认值与绑定判别式（TASK-214）。
 *
 * <p>R3a 锁死四默认值 200/50/25/300——这组默认值是 {@link com.sportverify.mapmatch.service.MapMatchServiceTest}
 * 黄金值判别式的承重契约：searchRadiusMeters=300 决定 300/85000 半径换算与离路距离封顶口径、
 * snapThresholdMeters=25 决定 matched ratio 黄金值分裂（22.26m 吸附 / 33.40m 离路）；
 * 默认值漂移将静默使黄金判别式失真，此处即红。</p>
 *
 * <p>R3b 照本模块 DataSourcePasswordEnvBindingTest 范式：MutablePropertySources 不装
 * StandardEnvironment 系统源（隔离宿主机同名 shell 变量），Binder +
 * PropertySourcesPlaceholdersResolver 按 {@code mapmatch.match.*} 前缀绑定，配置覆盖生效。</p>
 */
class MatchPropertiesTest {

    /** R3a 默认值承重契约：200/50/25/300 + D6 新增 2000/2000（黄金值/防护判别式依赖） */
    @Test
    @DisplayName("四默认值 200/50/25/300 逐位锁定（黄金值判别式承重契约）")
    void defaults_matchGoldenValueContract() {
        MatchProperties props = new MatchProperties();

        assertEquals(200, props.getMaxSampledPoints());
        assertEquals(50, props.getPrefilterChunkPoints());
        assertEquals(25.0, props.getSnapThresholdMeters(), 0.0);
        assertEquals(300.0, props.getSearchRadiusMeters(), 0.0);
        // D6 新增查询防护参数默认值（分裂阈值 / 候选边告警上限）
        assertEquals(2000.0, props.getMaxQuerySpanMeters(), 0.0);
        assertEquals(2000, props.getMaxCandidateEdges());
    }

    /** R3b 配置覆盖：mapmatch.match.* 前缀绑定生效（无系统源隔离） */
    @Test
    @DisplayName("mapmatch.match.* 前缀绑定覆盖四默认值")
    void binderOverridesDefaultsViaPrefix() {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("test-override", Map.of(
                "mapmatch.match.max-sampled-points", "100",
                "mapmatch.match.prefilter-chunk-points", "60",
                "mapmatch.match.snap-threshold-meters", "30",
                "mapmatch.match.search-radius-meters", "850",
                "mapmatch.match.max-query-span-meters", "1500",
                "mapmatch.match.max-candidate-edges", "3000")));

        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources));
        MatchProperties props = binder.bind("mapmatch.match", Bindable.of(MatchProperties.class)).get();

        assertEquals(100, props.getMaxSampledPoints());
        assertEquals(60, props.getPrefilterChunkPoints());
        assertEquals(30.0, props.getSnapThresholdMeters(), 0.0);
        assertEquals(850.0, props.getSearchRadiusMeters(), 0.0);
        assertEquals(1500.0, props.getMaxQuerySpanMeters(), 0.0);
        assertEquals(3000, props.getMaxCandidateEdges());
    }
}
