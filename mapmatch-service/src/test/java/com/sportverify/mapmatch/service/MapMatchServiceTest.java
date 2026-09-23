package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.mapmatch.config.MatchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匹配算法单测（纯几何与指标聚合逻辑，JdbcTemplate 打桩不连库）。
 *
 * <p>真实路网端到端验证（沿路 vs 悬浮）见交付说明冒烟记录；此处锁定：
 * 抽稀保点数上限、沿路轨迹指标归零、悬浮轨迹离路比例 1、WKT 垂距精度。</p>
 *
 * <p>轨迹级预筛判别式（TASK-133）另锁三条：① DB 往返次数 = 分块数、与采样点数无关，
 * 且旧逐点查询形态零调用；② 固定夹具下聚合指标逐位命中黄金值；③ 同一夹具下逐点
 * 「最佳边 + 垂距」逐位命中黄金值。黄金值由改前逐点实现留档，见 {@link #GOLDEN_DIST}
 * 与 {@link #GOLDEN_EDGE} 上方说明。</p>
 */
class MapMatchServiceTest {

    private JdbcTemplate jdbcTemplate;
    private MatchProperties props;
    private MapMatchService service;

    /** 轨迹级预筛查询的实参留底（每个元素 = 一次 queryForList 调用的全部实参） */
    private final List<Object[]> prefilterCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        props = new MatchProperties();
        service = new MapMatchService(jdbcTemplate, props);
        prefilterCalls.clear();
    }

    /** 一条南北向道路折线（WGS84，经度恒定，纬度递增），模拟 ST_AsText 返回 */
    private static final String NS_ROAD = "LINESTRING(121.4737000 31.2200000,121.4737000 31.2400000)";

    private static MapMatchPointDTO point(int seq, double lat, double lng) {
        MapMatchPointDTO p = new MapMatchPointDTO();
        p.setSeq(seq);
        p.setLat(lat);
        p.setLng(lng);
        p.setTs(1_700_000_000_000L + seq * 1000);
        return p;
    }

    private static MapMatchRequestDTO request(List<MapMatchPointDTO> points) {
        MapMatchRequestDTO req = new MapMatchRequestDTO();
        req.setPoints(points);
        return req;
    }

    // ==================== 轨迹级预筛判别式：夹具与观测缝 ====================

    /**
     * 夹具路网三条边（WGS84，模拟 ST_AsText 返回）。三条边各承担一种判别形态：
     * <ul>
     *   <li>{@link #E1_WEST_NEAR}：<b>近多点共享边</b>——P0-P4 都吸附到它（垂距 22.26m ≤ 25m 吸附阈值）；</li>
     *   <li>{@link #E2_EAST_FAR}：另一条共享边——P7-P11 吸附到它（垂距 33.40m &gt; 阈值，记离路）；</li>
     *   <li>{@link #E3_INTERFERE}：<b>超半径干扰边</b>——距轨迹线 356.22m &gt; 查询半径 300m，
     *       它在轨迹级候选集内出现但永不取胜（验证「超半径候选不改变结果」）。</li>
     * </ul>
     */
    private static final String E1_WEST_NEAR = "LINESTRING(121.4700000 31.2302000,121.4736000 31.2302000)";
    private static final String E2_EAST_FAR = "LINESTRING(121.4764000 31.2303000,121.4805000 31.2303000)";
    private static final String E3_INTERFERE = "LINESTRING(121.4700000 31.2332000,121.4800000 31.2332000)";

    /** 轨迹级预筛在该夹具上的返回值 = 整个夹具路网（**超集**，见 stubTrajectoryPrefilter 说明） */
    private static final List<String> NETWORK = List.of(E1_WEST_NEAR, E2_EAST_FAR, E3_INTERFERE);

    /** 夹具轨迹经度序列（显式字面量，避免 121.4700+i*0.0009 的浮点累积误差扰动黄金值） */
    private static final double[] FIXTURE_LNG = {
            121.4700, 121.4709, 121.4718, 121.4727, 121.4736, 121.4745,
            121.4754, 121.4763, 121.4772, 121.4781, 121.4790, 121.4799};

    /** 夹具轨迹：12 点沿 31.2300 等距铺开 + 第 13 点跳出（最近边 757m &gt; 半径 → 触发半径封顶） */
    private static final List<MapMatchPointDTO> FIXTURE_POINTS = fixturePoints();

    private static List<MapMatchPointDTO> fixturePoints() {
        List<MapMatchPointDTO> ps = new ArrayList<>();
        for (int i = 0; i < FIXTURE_LNG.length; i++) {
            ps.add(point(i, 31.2300, FIXTURE_LNG[i]));
        }
        ps.add(point(FIXTURE_LNG.length, 31.2400, 121.4750));
        return ps;
    }

    /**
     * 黄金值（逐点垂距，米）——**改前逐点实现留档**：以「逐点候选集 = 垂距 ≤ 查询半径的边」
     * 的保真假 DB 跑改前 {@code MapMatchService.match} 得到，实现后新实现必须逐位命中。
     *
     * <p>保真性依据：改前 SQL 的候选谓词是度数半径（{@code ST_DWithin(geom, 点, 300/85000 度)}），
     * 而 85,000 米/度 &lt; 实际每度米数，故「垂距 ≤ 300m」的边必被返回（充分性）；
     * 夹具各边与各点的垂距均不落在 (300m, 300×111320/85000 ≈ 393m] 的度数半径歧义带内，
     * 故按「垂距 ≤ 300m」取候选与真实度数谓词在**结果上**等价。</p>
     */
    private static final double[] GOLDEN_DIST = {
            22.263999999948112, 22.263999999948112, 22.263999999948112, 22.263999999948112,
            22.263999999948112, 88.51577716943375, 100.87728942688334, 34.72610204242849,
            33.39599999992217, 33.39599999992217, 33.39599999992217, 33.39599999992217,
            300.0};

    /** 黄金值（逐点最佳边，以该边 WKT 字面量为身份；null = 无候选、按半径封顶） */
    private static final String[] GOLDEN_EDGE = {
            E1_WEST_NEAR, E1_WEST_NEAR, E1_WEST_NEAR, E1_WEST_NEAR, E1_WEST_NEAR,
            E1_WEST_NEAR, E2_EAST_FAR, E2_EAST_FAR, E2_EAST_FAR, E2_EAST_FAR,
            E2_EAST_FAR, E2_EAST_FAR, null};

    /** 黄金值（聚合指标）：13 点 / 5 点吸附（P0-P4）/ 8 点离路，max 由第 13 点的封顶值决定 */
    private static final double GOLDEN_MATCHED_RATIO = 0.38461538461538464;
    private static final double GOLDEN_OFFROAD_RATIO = 0.6153846153846154;
    private static final double GOLDEN_AVG = 59.15562835678267;
    private static final double GOLDEN_MAX = 300.0;

    /**
     * 声明「轨迹级预筛」为唯一被支持的查询形态（5 参：块外接矩形 + 半径），并留底每次实参。
     *
     * <p>返回整个夹具路网是**有意取超集**：真实 {@code ST_DWithin(geom, envelope, radiusDeg)}
     * 只会回块外接矩形半径内的边，而超集证明更强——多出的边垂距必 ≥ 半径（否则它本就落在
     * 该点的逐点候选集内），被 {@code best} 初值截断吞掉，故结果不受影响。</p>
     */
    private void stubTrajectoryPrefilter(List<String> candidates) {
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(invocation -> {
                    prefilterCalls.add(invocation.getArguments());
                    return candidates;
                });
    }

    /**
     * 旧逐点形态（3 参）在本判别式中一律返回空候选。
     *
     * <p>两个作用：① 把「逐点 SQL 已从实现中删除」变成显式判据——目标态下该形态零调用
     * （见 {@link #verifyNoLegacyPointQuery}）；② 回退/变异复现时（实现退回逐点查询）该形态
     * 取不到候选，输出退化为半径封顶，判别式给的是<b>断言级红</b>而不是依赖 Mockito
     * 对 List 的默认空返回。</p>
     */
    private void stubLegacyPointQueryEmpty() {
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
    }

    /** 断言轨迹级预筛被调用恰好 count 次（往返数 = 分块数） */
    private void verifyTrajectoryPrefilter(int count) {
        verify(jdbcTemplate, times(count)).queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    /** 断言旧逐点查询形态零调用（逐点 SQL 已从实现中删除） */
    private void verifyNoLegacyPointQuery() {
        verify(jdbcTemplate, never()).queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble());
    }

    /** 取一次调用的全部数值实参（`queryForList(sql, Class, Object...)` 的变长参数形态无关） */
    private static List<Double> numericArgs(Object[] args) {
        List<Double> out = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Double d) {
                out.add(d);
            } else if (arg instanceof Object[] nested) {
                for (Object inner : nested) {
                    if (inner instanceof Double d) {
                        out.add(d);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void 沿路轨迹_全部吸附_离路比例为零() {
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(NS_ROAD));
        // 点位直接取在道路上（经度与道路一致，纬度在折线范围内），垂距≈0
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            points.add(point(i, 31.2210 + i * 0.0010, 121.4737));
        }
        MapMatchResultDTO r = service.match(request(points));
        assertThat(r.getSampledPoints()).isEqualTo(20);
        assertThat(r.getOffRoadRatio()).isZero();
        assertThat(r.getMatchedRatio()).isEqualTo(1.0);
        assertThat(r.getAvgOffRoadDistance()).isLessThan(1.0);
    }

    @Test
    void 悬浮轨迹_无候选边_离路比例为一() {
        // 黄浦江江面：查询半径内无任何道路边 → 全部按查询半径封顶计离路
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            points.add(point(i, 31.2200 + i * 0.0015, 121.5000));
        }
        MapMatchResultDTO r = service.match(request(points));
        assertThat(r.getOffRoadRatio()).isEqualTo(1.0);
        assertThat(r.getMatchedRatio()).isZero();
        assertThat(r.getMaxOffRoadDistance()).isEqualTo(props.getSearchRadiusMeters());
        assertThat(r.getAvgOffRoadDistance()).isEqualTo(props.getSearchRadiusMeters());
    }

    @Test
    void 超长轨迹_均匀抽稀到采样上限_首尾保留() {
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            points.add(point(i, 31.20 + i * 0.00001, 121.47));
        }
        MapMatchResultDTO r = service.match(request(points));
        assertThat(r.getTotalPoints()).isEqualTo(1000);
        assertThat(r.getSampledPoints()).isEqualTo(props.getMaxSampledPoints());
    }

    @Test
    void 垂距计算_点到线段_精度符合预期() {
        // 点在道路正西方向：每度经度在该纬度 ≈ 111320*cos(31.23°) ≈ 95.2km，取 0.001 度 ≈ 95.2m
        double d = MapMatchService.pointToLineStringMeters(31.2300, 121.4727, NS_ROAD);
        assertThat(d).isBetween(90.0, 100.0);
        // 端点延伸线上的点：垂足钳制到线段端点，距离按端点算而非延长线
        double d2 = MapMatchService.pointToLineStringMeters(31.2500, 121.4737, NS_ROAD);
        assertThat(d2).isGreaterThan(1000.0);
    }

    @Test
    void 空轨迹_返回全零指标_不抛异常() {
        MapMatchResultDTO r = service.match(request(List.of()));
        assertThat(r.getTotalPoints()).isZero();
        assertThat(r.getOffRoadRatio()).isZero();
        MapMatchResultDTO nullReq = service.match(null);
        assertThat(nullReq.getSampledPoints()).isZero();
    }

    @Test
    void 抽稀算法_首尾必保留_间隔均匀() {
        List<Integer> src = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> out = MapMatchService.thin(src, 6);
        assertThat(out).hasSize(6);
        assertThat(out.get(0)).isZero();
        assertThat(out.get(out.size() - 1)).isEqualTo(10);
        // 等间隔抽点：下标序列单调递增且无重复
        assertThat(out.stream().distinct().collect(Collectors.toList())).hasSameSizeAs(out);
    }

    // ==================== 红①：往返数判别 ====================

    @Test
    void 轨迹级预筛_单块_往返数与采样点数无关() {
        stubTrajectoryPrefilter(NETWORK);
        stubLegacyPointQueryEmpty();
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            points.add(point(i, 31.2210 + i * 0.0010, 121.4737));
        }

        MapMatchResultDTO r = service.match(request(points));

        // 20 点 ≤ 默认分块阈值 50 → 1 次预筛（旧实现 20 次逐点往返）
        verifyTrajectoryPrefilter(1);
        verifyNoLegacyPointQuery();
        assertThat(r.getSampledPoints()).isEqualTo(20);
        // 唯一一次预筛的参照物 = 整块外接矩形；经度恒定 → 矩形的 lng 上下界重合
        assertThat(prefilterCalls).hasSize(1);
        List<Double> args = numericArgs(prefilterCalls.get(0));
        assertThat(args).hasSize(5);
        assertThat(args.get(0)).isCloseTo(121.4737, within(1e-9));   // minLng
        assertThat(args.get(1)).isCloseTo(31.2210, within(1e-9));    // minLat
        assertThat(args.get(2)).isCloseTo(121.4737, within(1e-9));   // maxLng
        assertThat(args.get(3)).isCloseTo(31.2400, within(1e-9));    // maxLat
        assertThat(args.get(4)).isCloseTo(300.0 / 85_000.0, within(1e-12)); // 半径（米转度，沿用 DEG_FACTOR）
    }

    @Test
    void 轨迹级预筛_跨块_往返数等于分块数() {
        stubTrajectoryPrefilter(NETWORK);
        stubLegacyPointQueryEmpty();
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            points.add(point(i, 31.2200 + i * 0.00010, 121.4737));
        }

        MapMatchResultDTO r = service.match(request(points));

        // 200 点 = 采样上限整值（thin 不缩点），按默认分块阈值 50 切 4 块 → 4 次预筛（旧实现 200 次）
        assertThat(r.getSampledPoints()).isEqualTo(200);
        verifyTrajectoryPrefilter(4);
        verifyNoLegacyPointQuery();
        assertThat(prefilterCalls).hasSize(4);
        // 分块按点序连续：首块的 minLat 与末块的 maxLat 夹住整条轨迹（块间不重不漏）
        assertThat(numericArgs(prefilterCalls.get(0)).get(1)).isCloseTo(31.2200, within(1e-9));
        assertThat(numericArgs(prefilterCalls.get(3)).get(3))
                .isCloseTo(31.2200 + 199 * 0.00010, within(1e-9));
    }

    // ==================== 红②：语义等价判别 ====================

    @Test
    void 轨迹级预筛_固定夹具_聚合指标逐位命中黄金值() {
        stubTrajectoryPrefilter(NETWORK);
        stubLegacyPointQueryEmpty();

        MapMatchResultDTO r = service.match(request(FIXTURE_POINTS));

        assertThat(r.getTotalPoints()).isEqualTo(13);
        assertThat(r.getSampledPoints()).isEqualTo(13);
        assertThat(r.getMatchedRatio()).isEqualTo(GOLDEN_MATCHED_RATIO);
        assertThat(r.getOffRoadRatio()).isEqualTo(GOLDEN_OFFROAD_RATIO);
        assertThat(r.getAvgOffRoadDistance()).isCloseTo(GOLDEN_AVG, within(1e-9));
        assertThat(r.getMaxOffRoadDistance()).isEqualTo(GOLDEN_MAX);
    }

    @Test
    void 轨迹级预筛_固定夹具_逐点最佳边与垂距逐位命中黄金值() {
        double cap = props.getSearchRadiusMeters();
        for (int i = 0; i < FIXTURE_POINTS.size(); i++) {
            MapMatchPointDTO p = FIXTURE_POINTS.get(i);
            // 旧语义的逐点候选集：垂距 ≤ 查询半径的边（保真性说明见 GOLDEN_DIST 上方）
            List<String> legacy = NETWORK.stream()
                    .filter(wkt -> MapMatchService.pointToLineStringMeters(p.getLat(), p.getLng(), wkt) <= cap)
                    .toList();
            String legacyEdge = nearestEdge(p, legacy, cap);
            String chunkEdge = nearestEdge(p, NETWORK, cap);
            // ① 同点同边：块级候选集（含超半径干扰边 E3 与各点各自的远景边）与逐点候选集结论一致
            assertThat(chunkEdge).as("第 %d 点：块级候选集与逐点候选集的最佳边", i).isEqualTo(legacyEdge);
            // ② 且逐位命中改前实现留档的黄金值（best 边 + 垂距）
            assertThat(chunkEdge).as("第 %d 点最佳边（黄金值）", i).isEqualTo(GOLDEN_EDGE[i]);
            double dist = chunkEdge == null ? cap
                    : MapMatchService.pointToLineStringMeters(p.getLat(), p.getLng(), chunkEdge);
            assertThat(dist).as("第 %d 点垂距（黄金值）", i).isCloseTo(GOLDEN_DIST[i], within(1e-9));
        }
    }

    /** 从候选集取最佳边（null = 无候选或全部超半径，按查询半径封顶） */
    private static String nearestEdge(MapMatchPointDTO p, List<String> candidates, double cap) {
        int idx = MapMatchService.nearestEdgeIndex(p.getLat(), p.getLng(), candidates, cap);
        return idx < 0 ? null : candidates.get(idx);
    }
}
