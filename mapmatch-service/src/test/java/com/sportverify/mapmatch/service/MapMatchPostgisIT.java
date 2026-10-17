package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.mapmatch.config.MatchProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostGIS 空间库端到端集成测试（TASK-258 R1，IT 后缀——常规 surefire 不隐式收集，
 * 显式 {@code -Dtest=MapMatchPostgisIT} 触发）。
 *
 * <p>与 {@link MapMatchServiceTest}（Mockito 打桩）互补：本类把生产 {@code CANDIDATE_SQL}
 * （反射读取，非复制粘贴）放到真实 7780 条 OSM 路网的 {@code road_db} 上执行，坐实
 * 单测打桩看不见的空间库盲区——SQL 可执行性、GIST 索引真实生效（非 Seq Scan）、
 * WKT 回传与 {@code parseWkt} 解析契约、沿路/悬浮轨迹的指标判定、D6 自适应拆块。</p>
 *
 * <p>环境感知门控：默认自适应探测 {@code 127.0.0.1:5433}（本机 sport-verify-postgis
 * 容器映射端口）与 5432，可用环境变量覆盖：{@code POSTGIS_HOST}、{@code POSTGIS_PORT}、
 * {@code POSTGIS_USER}、{@code POSTGIS_PASSWORD}、{@code POSTGIS_DB}；口令候选依次
 * 尝试（显式环境变量 → 仓库根 {@code .env} 声明值 → 镜像默认 {@code postgres}——
 * POSTGRES_PASSWORD 仅首次初始化数据卷时生效，历史卷实际口令可能与 .env 漂移）。
 * 服务不可达则 {@link Assumptions#assumeTrue} 优雅跳过（CI/离线环境全跳过，权威保障
 * 由 {@code MapMatchSqlContractTest} 承载）。</p>
 */
class MapMatchPostgisIT {

    /** 生产换算系数（与 MapMatchService.DEG_FACTOR 同值）：查询半径米转度、跨度米距折算 */
    private static final double DEG_FACTOR = 85_000.0;

    /** 本地路网切片（上海核心区）的经纬度包络，用于回传坐标的量级防御 */
    private static final double SLICE_LNG_MIN = 121.41;
    private static final double SLICE_LNG_MAX = 121.56;
    private static final double SLICE_LAT_MIN = 31.18;
    private static final double SLICE_LAT_MAX = 31.27;

    /** PostGIS 回传 WKT 的整体形态（经度 纬度 成对、逗号分隔，与 parseWkt 契约一致） */
    private static final Pattern WKT_SHAPE =
            Pattern.compile("LINESTRING\\(-?\\d+(\\.\\d+)? -?\\d+(\\.\\d+)?(,-?\\d+(\\.\\d+)? -?\\d+(\\.\\d+)?)*\\)");

    /** 门控产物：探测成功的连接（null = PostGIS 不可达，全类跳过）与反射读取的生产 SQL */
    private static DataSource dataSource;
    private static String candidateSql;

    @BeforeAll
    static void gateOnPostgisAvailability() throws ReflectiveOperationException {
        String host = envOrDefault("POSTGIS_HOST", "127.0.0.1");
        String user = envOrDefault("POSTGIS_USER", "postgres");
        String db = envOrDefault("POSTGIS_DB", "road_db");
        List<String> passwords = resolvePasswords();
        int[] ports = resolvePorts();

        DriverManager.setLoginTimeout(3);
        StringBuilder probed = new StringBuilder();
        for (int port : ports) {
            for (String password : passwords) {
                String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                probed.append(url).append(' ');
                try (Connection ignored = DriverManager.getConnection(url, user, password)) {
                    dataSource = new DriverManagerDataSource(url, user, password);
                    break;
                } catch (SQLException e) {
                    dataSource = null;
                }
            }
            if (dataSource != null) {
                break;
            }
        }
        candidateSql = candidateSqlConstant();
        Assumptions.assumeTrue(dataSource != null,
                "PostGIS 不可达（已探测 " + probed + "；可用 POSTGIS_HOST/POSTGIS_PORT/POSTGIS_PASSWORD 覆盖），跳过真实库 IT");
    }

    // ==================== 用例 1：CANDIDATE_SQL 真实可执行 + WKT 契约 ====================

    @Test
    void candidateSqlExecutesSuccessfullyOnRealPostgis() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer edges = jdbc.queryForObject("SELECT COUNT(*) FROM road_edge", Integer.class);
        assertThat(edges).as("真实 OSM 路网边数（数据集契约）").isEqualTo(7780);

        // 人民广场周边包络（minLng, minLat, maxLng, maxLat），半径 = 查询半径 300m 米转度
        List<String> wkts = jdbc.queryForList(candidateSql, String.class,
                121.4680, 31.2260, 121.4820, 31.2380, 300.0 / DEG_FACTOR);
        assertThat(wkts).as("包络内候选边").isNotEmpty();
        for (String wkt : wkts) {
            assertThat(WKT_SHAPE.matcher(wkt).matches())
                    .as("回传 WKT 形态（经度 纬度 成对）: %s", wkt).isTrue();
        }

        // 生产解析管线对真实 WKT 的消化：首顶点回代垂距应≈0（坐标系无翻转、无精度漂移）
        double[][] first = parseWkt(wkts.get(0));
        double backFeed = MapMatchService.pointToLineStringMeters(first[0][1], first[0][0], wkts.get(0));
        assertThat(backFeed).as("真实 WKT 首顶点回代垂距（米）").isLessThan(1.0);
        assertThat(first[0][0]).as("首点经度落在本地切片内").isBetween(SLICE_LNG_MIN, SLICE_LNG_MAX);
        assertThat(first[0][1]).as("首点纬度落在本地切片内").isBetween(SLICE_LAT_MIN, SLICE_LAT_MAX);
    }

    // ==================== 用例 2：GIST 空间索引真实生效 ====================

    @Test
    void gistSpatialIndexIsUsed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 立项书亲验同款查询（参数内联，EXPLAIN 不经绑定参数）：小包络 + 300m 半径
        List<String> plan = jdbc.queryForList(
                "EXPLAIN SELECT ST_AsText(geom) FROM road_edge "
                        + "WHERE ST_DWithin(geom, ST_MakeEnvelope(121.4700, 31.2300, 121.4750, 31.2350, 4326), "
                        + "300.0 / 85000.0)",
                String.class);
        String text = String.join("\n", plan);
        assertThat(text).as("执行计划").contains("idx_road_edge_geom");
        assertThat(text).as("索引扫描形态").containsPattern("(Bitmap Index Scan|Index Scan)");
        assertThat(text).as("绝不允许全表顺序扫描").doesNotContain("Seq Scan on road_edge");
    }

    // ==================== 用例 3：真实沿路轨迹高吸附率 ====================

    @Test
    void matchRealisticOnRoadTrajectory_highMatchedRatio() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 人民广场周边按长度取最长真实边（2070m 级高架/主干道），12 点沿折线等距采样
        String roadWkt = jdbc.queryForObject(
                "SELECT ST_AsText(geom) FROM road_edge "
                        + "WHERE ST_Intersects(geom, ST_MakeEnvelope(121.4680, 31.2260, 121.4820, 31.2380, 4326)) "
                        + "ORDER BY ST_Length(geom::geography) DESC LIMIT 1",
                String.class);
        List<MapMatchPointDTO> points = resampleAlong(parseWkt(roadWkt), 12);

        MapMatchService service = new MapMatchService(jdbc, new MatchProperties());
        MapMatchResultDTO r = service.match(request(points));

        assertThat(r.getSampledPoints()).isEqualTo(12);
        assertThat(r.getMatchedRatio()).as("沿路轨迹吸附率").isGreaterThan(0.8);
        assertThat(r.getOffRoadRatio()).as("沿路轨迹离路比例").isLessThan(0.2);
        assertThat(r.getAvgOffRoadDistance()).as("沿路轨迹平均偏离（米）").isLessThan(25.0);
    }

    // ==================== 用例 4：悬浮轨迹全离路 + 半径封顶 ====================

    @Test
    void matchOffRoadFloatingTrajectory_cappedAtSearchRadius() {
        // 远海伪造轨迹（菲律宾海，距上海切片上千公里）：查询半径内无任何真实边
        MatchProperties props = new MatchProperties();
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            points.add(point(i, 10.0000 + i * 0.0010, 130.0000));
        }

        MapMatchService service = new MapMatchService(new JdbcTemplate(dataSource), props);
        MapMatchResultDTO r = service.match(request(points));

        assertThat(r.getOffRoadRatio()).as("悬浮轨迹离路比例").isEqualTo(1.0);
        assertThat(r.getMatchedRatio()).as("悬浮轨迹吸附率").isZero();
        assertThat(r.getMaxOffRoadDistance()).as("最大偏离按查询半径封顶")
                .isEqualTo(props.getSearchRadiusMeters());
        assertThat(r.getAvgOffRoadDistance()).as("平均偏离按查询半径封顶")
                .isEqualTo(props.getSearchRadiusMeters());
    }

    // ==================== 用例 5：D6 自适应递归中点拆块 ====================

    @Test
    void adaptiveSplitBboxBehaviorOnRealDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 全库最长真实边（5965m 级，bbox 对角线远超默认跨度阈值 2000m）
        String roadWkt = jdbc.queryForObject(
                "SELECT ST_AsText(geom) FROM road_edge ORDER BY ST_Length(geom::geography) DESC LIMIT 1",
                String.class);
        List<MapMatchPointDTO> points = resampleAlong(parseWkt(roadWkt), 40);
        MatchProperties props = new MatchProperties();
        assertThat(bboxDiagonalMeters(points)).as("拆块前置条件：块跨度超阈值")
                .isGreaterThan(props.getMaxQuerySpanMeters());

        CountingJdbcTemplate counting = new CountingJdbcTemplate(dataSource);
        MapMatchService service = new MapMatchService(counting, props);
        MapMatchResultDTO split = service.match(request(points));
        assertThat(counting.candidateQueryCount()).as("拆块路径叶子查询次数（>1 即递归真实发生）")
                .isGreaterThanOrEqualTo(2);
        assertThat(split.getMatchedRatio()).as("拆块路径沿路吸附率").isGreaterThan(0.8);

        // 对照：跨度上限抬到无穷大 → 单块恰 1 次查询，聚合指标与拆块路径逐位一致（超集语义）
        counting.reset();
        props.setMaxQuerySpanMeters(Double.MAX_VALUE);
        MapMatchResultDTO single = service.match(request(points));
        assertThat(counting.candidateQueryCount()).as("禁用拆块后单块查询次数").isEqualTo(1);
        assertThat(single.getMatchedRatio()).isEqualTo(split.getMatchedRatio());
        assertThat(single.getOffRoadRatio()).isEqualTo(split.getOffRoadRatio());
        assertThat(single.getAvgOffRoadDistance()).isEqualTo(split.getAvgOffRoadDistance());
        assertThat(single.getMaxOffRoadDistance()).isEqualTo(split.getMaxOffRoadDistance());
    }

    // ==================== 探测与夹具工具 ====================

    /**
     * 口令候选序（依次尝试，首个连通者生效）：{@code POSTGIS_PASSWORD} 显式指定 →
     * 仓库根 {@code .env} 的 {@code POSTGRES_PASSWORD}（本地声明口令不入库）→
     * {@code postgres}（镜像默认口令兜底）。三者并存是有意的：POSTGRES_PASSWORD 仅在
     * 首次初始化数据卷时生效，历史卷的实际口令可能与 .env 声明漂移（2026-10-17 实测
     * 本机容器即为镜像默认 postgres），多候选探测是环境自适应的一部分。
     */
    private static List<String> resolvePasswords() {
        List<String> candidates = new ArrayList<>();
        String fromEnv = System.getenv("POSTGIS_PASSWORD");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            candidates.add(fromEnv);
        }
        try {
            Path dir = Paths.get("").toAbsolutePath();
            while (dir != null) {
                Path envFile = dir.resolve(".env");
                if (Files.isRegularFile(envFile)) {
                    for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                        if (line.startsWith("POSTGRES_PASSWORD=")) {
                            String declared = line.substring("POSTGRES_PASSWORD=".length()).trim();
                            if (!declared.isEmpty()) {
                                candidates.add(declared);
                            }
                            break;
                        }
                    }
                    break;
                }
                dir = dir.getParent();
            }
        } catch (Exception ignored) {
            // .env 不可读时跳过该候选，后续镜像默认口令兜底
        }
        candidates.add("postgres");
        return candidates;
    }

    /** 探测端口序：POSTGIS_PORT 显式指定则只探它；缺省先 5433（本机容器映射）后 5432 */
    private static int[] resolvePorts() {
        String fromEnv = System.getenv("POSTGIS_PORT");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return new int[]{Integer.parseInt(fromEnv.trim())};
        }
        return new int[]{5433, 5432};
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** 反射读取生产常量（防复制粘贴漂移：SQL 改了而 IT 不知道） */
    private static String candidateSqlConstant() throws ReflectiveOperationException {
        Field field = MapMatchService.class.getDeclaredField("CANDIDATE_SQL");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /** WKT → 坐标数组（镜像生产 parseWkt 的文本契约：lng lat 成对、逗号分隔折线顶点） */
    private static double[][] parseWkt(String wkt) {
        String body = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
        String[] pts = body.split(",");
        double[][] coords = new double[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            String[] c = pts[i].trim().split("\\s+");
            coords[i][0] = Double.parseDouble(c[0]);
            coords[i][1] = Double.parseDouble(c[1]);
        }
        return coords;
    }

    /** 沿折线等距重采样 n 点（含首尾）：按累计段长取目标弧长处的线性插值点 */
    private static List<MapMatchPointDTO> resampleAlong(double[][] coords, int n) {
        double[] cum = new double[coords.length];
        for (int i = 1; i < coords.length; i++) {
            cum[i] = cum[i - 1] + Math.hypot(coords[i][0] - coords[i - 1][0], coords[i][1] - coords[i - 1][1]);
        }
        double total = cum[cum.length - 1];
        assertThat(total).as("夹具折线长度（度）").isGreaterThan(0.0);

        List<MapMatchPointDTO> out = new ArrayList<>(n);
        int seg = 0;
        for (int k = 0; k < n; k++) {
            double target = total * k / (n - 1);
            while (seg < cum.length - 2 && cum[seg + 1] < target) {
                seg++;
            }
            double t = (target - cum[seg]) / (cum[seg + 1] - cum[seg]);
            double lng = coords[seg][0] + t * (coords[seg + 1][0] - coords[seg][0]);
            double lat = coords[seg][1] + t * (coords[seg + 1][1] - coords[seg][1]);
            out.add(point(k, lat, lng));
        }
        return out;
    }

    /** 点集外接矩形对角线米距（沿用生产 DEG_FACTOR 折算口径，与拆块判据同尺度） */
    private static double bboxDiagonalMeters(List<MapMatchPointDTO> points) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (MapMatchPointDTO p : points) {
            minLat = Math.min(minLat, p.getLat());
            maxLat = Math.max(maxLat, p.getLat());
            minLng = Math.min(minLng, p.getLng());
            maxLng = Math.max(maxLng, p.getLng());
        }
        return Math.hypot((maxLat - minLat) * DEG_FACTOR, (maxLng - minLng) * DEG_FACTOR);
    }

    private static MapMatchPointDTO point(int seq, double lat, double lng) {
        MapMatchPointDTO p = new MapMatchPointDTO();
        p.setSeq(seq);
        p.setLat(lat);
        p.setLng(lng);
        p.setTs(1_700_000_000_000L + seq * 1000L);
        return p;
    }

    private static MapMatchRequestDTO request(List<MapMatchPointDTO> points) {
        MapMatchRequestDTO req = new MapMatchRequestDTO();
        req.setPoints(points);
        return req;
    }

    /** 计数版 JdbcTemplate：只观测候选边预筛形态 {@code queryForList(sql, Class, args...)} 的调用次数 */
    static final class CountingJdbcTemplate extends JdbcTemplate {
        private int candidateQueryCount;

        CountingJdbcTemplate(DataSource ds) {
            super(ds);
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            candidateQueryCount++;
            return super.queryForList(sql, elementType, args);
        }

        int candidateQueryCount() {
            return candidateQueryCount;
        }

        void reset() {
            candidateQueryCount = 0;
        }
    }
}
