package com.sportverify.mapmatch.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选边预筛 SQL 与路网表结构的纯 JVM 契约测试（TASK-258 R2）。
 *
 * <p>离线/CI 口径下的权威防线：不连任何数据库，反射读取 {@link MapMatchService} 的
 * {@code CANDIDATE_SQL} 常量值逐字锁定（表名 {@code road_edge}、空间函数
 * {@code ST_DWithin} / {@code ST_MakeEnvelope} / {@code ST_AsText}、SRID 4326 字面量、
 * 五个占位符），并读取生产源码与 {@code scripts/mapmatch/road_schema.sql} 断言
 * 实参列序 {@code (minLng, minLat, maxLng, maxLat, radiusDeg)} 与
 * {@code idx_road_edge_geom} GIST 索引定义。真实库端到端行为由
 * {@code MapMatchPostgisIT}（需 Docker PostGIS，缺环境时跳过）承载，两级互补。</p>
 *
 * <p>契约对应的语义：ST_MakeEnvelope 参数序 = (xmin, ymin, xmax, ymax)，经度在前
 * 纬度在后，与 {@code collectCandidates} 的绑定序 {@code minLng, minLat, maxLng,
 * maxLat, radiusDeg} 严格对齐——列序翻转会把经纬度 swapped 的矩形送进空间索引，
 * 单测打桩看不见，只有本契约与真实库 IT 能拦住。</p>
 */
class MapMatchSqlContractTest {

    /** 候选边预筛 SQL 的逐字期望值（与 MapMatchService.CANDIDATE_SQL 编译期常量逐位比对） */
    private static final String EXPECTED_CANDIDATE_SQL =
            "SELECT ST_AsText(geom) FROM road_edge "
                    + "WHERE ST_DWithin(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326), ?)";

    /** 绑定序契约：占位符实参必须按（minLng、minLat、maxLng、maxLat、半径度数）顺序传入 */
    private static final String EXPECTED_BINDING_ORDER = "minLng, minLat, maxLng, maxLat, radiusDeg";

    /** road_schema.sql 中的关键 DDL 片段（逐字锁定，含缩进无关的稳定子串） */
    private static final List<String> EXPECTED_SCHEMA_FRAGMENTS = List.of(
            "CREATE EXTENSION IF NOT EXISTS postgis",
            "CREATE TABLE IF NOT EXISTS road_edge",
            "GEOMETRY(LINESTRING, 4326) NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_road_edge_geom ON road_edge USING GIST (geom)");

    /**
     * 赛段表（TASK-272）DDL 契约片段（逐字锁定）：表名 segment、运动类型缺省 RUNNING、
     * 标称长度列、空间折线类型与 GIST 索引——两段式匹配（SQL 粗筛 + Java 精匹）的库端锚点。
     */
    private static final List<String> EXPECTED_SEGMENT_FRAGMENTS = List.of(
            "CREATE TABLE IF NOT EXISTS segment",
            "sport_type   SMALLINT     NOT NULL DEFAULT 1",
            "distance_m   DOUBLE PRECISION NOT NULL",
            "GEOMETRY(LINESTRING, 4326) NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_segment_geom ON segment USING GIST (geom)");

    @Test
    void 候选SQL与路网表契约_空间函数SRID列序与GIST索引全锁定() throws IOException, ReflectiveOperationException {
        // ---- 生产常量逐字锁定：表名 / 空间函数 / SRID 4326 / 占位符形态 ----
        String candidateSql = candidateSqlConstant();
        assertEquals(EXPECTED_CANDIDATE_SQL, candidateSql,
                "CANDIDATE_SQL 必须与契约逐字一致（表名 road_edge、ST_AsText/ST_DWithin/ST_MakeEnvelope、SRID 4326）");
        assertEquals(5, countPlaceholders(candidateSql),
                "CANDIDATE_SQL 必须恰 5 个占位符，与 5 元组实参列序对齐");
        // 反向防御：预筛阶段禁止把距离计算推回数据库（垂距的米制精算契约在 Java 侧，见类注释）
        assertFalse(candidateSql.contains("ST_Distance"), "预筛 SQL 不得使用 ST_Distance（Java 侧垂距精算契约）");
        assertFalse(candidateSql.contains("ST_GeomFromText"), "预筛 SQL 不得依赖 WKT 反解析入参");

        // ---- 生产源码：常量声明与绑定列序（minLng, minLat, maxLng, maxLat, radiusDeg）----
        String serviceSource = readRepoFile("mapmatch-service/src/main/java"
                + "/com/sportverify/mapmatch/service/MapMatchService.java");
        assertTrue(serviceSource.contains("private static final String CANDIDATE_SQL"),
                "MapMatchService 必须保留具名常量 CANDIDATE_SQL（供 IT 反射与契约锚定）");
        assertTrue(serviceSource.contains(EXPECTED_BINDING_ORDER),
                "占位符绑定序必须是 " + EXPECTED_BINDING_ORDER
                        + "（ST_MakeEnvelope 的 xmin/ymin/xmax/ymax 语义，经度在前纬度在后）");

        // ---- 路网表结构：扩展 / 表名 / 空间类型 SRID / GIST 索引定义 ----
        String schemaDdl = readRepoFile("scripts/mapmatch/road_schema.sql");
        for (String fragment : EXPECTED_SCHEMA_FRAGMENTS) {
            assertTrue(schemaDdl.contains(fragment),
                    "road_schema.sql 必须包含契约片段: " + fragment);
        }
        assertFalse(schemaDdl.contains("USING BTREE (geom)"),
                "geom 列必须走 GIST 空间索引，不得退化为 BTREE（无法加速 ST_DWithin）");
    }

    /**
     * 赛段表契约（TASK-272）：表名 / 运动类型缺省 / 标称长度 / 空间折线 SRID / GIST 索引逐字锁定。
     * 与 road_edge 同款防线——粗筛 SQL 打桩看不见库端结构，只有本契约与真实库 IT 能拦住
     * DDL 漂移（如索引误写成 BTREE、SRID 缺失导致 ST_DWithin 全表扫）。
     */
    @Test
    void 赛段表契约_segment表结构与GIST空间索引全锁定() throws IOException {
        String schemaDdl = readRepoFile("scripts/mapmatch/road_schema.sql");
        for (String fragment : EXPECTED_SEGMENT_FRAGMENTS) {
            assertTrue(schemaDdl.contains(fragment),
                    "road_schema.sql 必须包含赛段契约片段: " + fragment);
        }
        assertFalse(schemaDdl.contains("USING BTREE (geom)"),
                "segment.geom 列必须走 GIST 空间索引，不得退化为 BTREE（无法加速 ST_DWithin）");
    }

    /** 反射读取生产常量（不连库）：绑定编译产物，改 SQL 不改测试即红 */
    private static String candidateSqlConstant() throws ReflectiveOperationException {
        Field field = MapMatchService.class.getDeclaredField("CANDIDATE_SQL");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    /** 自模块工作目录向上查找仓库相对路径文件（IDE 在模块目录 / Maven 在仓库根均可命中） */
    private static String readRepoFile(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 " + relativePath + "（自 " + Paths.get("").toAbsolutePath() + " 向上查找）");
    }
}
