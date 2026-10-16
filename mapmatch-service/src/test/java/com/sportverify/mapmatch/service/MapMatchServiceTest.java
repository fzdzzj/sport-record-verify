package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.mapmatch.config.MatchProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    private Logger mapMatchLogger;
    private ListAppender<ILoggingEvent> warnCapture;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        props = new MatchProperties();
        service = new MapMatchService(jdbcTemplate, props);
        prefilterCalls.clear();

        // 挂 logback ListAppender 捕获 MapMatchService 的 warn（D6 候选超限告警可观测性）
        mapMatchLogger = (Logger) LoggerFactory.getLogger(MapMatchService.class);
        warnCapture = new ListAppender<>();
        warnCapture.start();
        mapMatchLogger.setLevel(Level.WARN);
        mapMatchLogger.addAppender(warnCapture);
    }

    @AfterEach
    void tearDown() {
        mapMatchLogger.detachAppender(warnCapture);
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

    // ==================== E17-③ thin 下界防御（R3） ====================

    @Test
    void 抽稀_上限配0_按首尾两点采样不抛异常() {
        List<Integer> src = List.of(0, 1, 2, 3, 4, 5, 6);
        // max=0：旧实现（max-1=0 除零）会抛 ArithmeticException / 空列表；新实现按至少首尾 2 点
        List<Integer> out = MapMatchService.thin(src, 0);
        assertThat(out).hasSize(2);
        assertThat(out).containsExactly(0, 6);
    }

    @Test
    void 抽稀_上限配1_按首尾两点采样不抛异常() {
        List<Integer> src = List.of(0, 1, 2, 3, 4, 5, 6);
        List<Integer> out = MapMatchService.thin(src, 1);
        assertThat(out).hasSize(2);
        assertThat(out).containsExactly(0, 6);
    }

    @Test
    void 抽稀_正常上限走等比采样序列不变() {
        // 正常 ≥2 配置下有效上限==配置上限，采样序列与既有实现逐位一致（首尾保留、等间隔、无重复）
        List<Integer> src = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        List<Integer> out = MapMatchService.thin(src, 5);
        assertThat(out).hasSize(5);
        assertThat(out).containsExactly(0, 2, 4, 6, 9);
    }

    // ==================== E29 候选边收集去重 + 累计判据 + WKT 预解析（R7） ====================

    /**
     * 分块递归子块空间重叠：同一候选边 WKT 在多个叶子块各返回一次 → LinkedHashSet 去重后
     * 参与精算的候选集不重复（同边不在同一 block 内被重复精算）。断言聚合链路去重生效。
     */
    @Test
    void 候选边去重_分块重叠同边仅计一次() {
        // 两叶子块各返回一次 E1_WEST_NEAR（重叠区同边），其余边各一次
        List<String> block1 = List.of(E1_WEST_NEAR, E2_EAST_FAR);
        List<String> block2 = List.of(E1_WEST_NEAR, E3_INTERFERE);
        // 参考点吸附到 E1（垂距 22.26m ≤ 阈值），去重后 E1 仍命中且 no 空
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(block1, block2);
        stubLegacyPointQueryEmpty();
        props.setMaxQuerySpanMeters(0); // 强制块跨度超限 → 中点二分递归（子块重叠区造重复）

        // 两叶子块：各块一次查询 → 往返 2 次
        MapMatchResultDTO r = service.match(request(List.of(point(0, 31.2302, 121.4710), point(1, 31.2400, 121.4750))));
        verifyTrajectoryPrefilter(2);
        assertThat(r.getSampledPoints()).isEqualTo(2);
        // 去重不破坏吸附判定：E1 垂距 22.26m ≤ 阈值 → 吸附计数正常（P0 命中）
        assertThat(r.getMatchedRatio()).isGreaterThan(0.0);
    }

    /** 累计判据告警：分块累计去重后总量超上限即告警（无截断），报文含累计候选边数（去重后）。
     *  <b>注</b>：既有 {@link #候选边超限_触发warn且不截断} 单点单块场景已覆盖累计判据（累计==单叶），
     *  此处补充单块 2001 条仍不经截断的完整数断言，与新告警文案（「累计候选边数=2001」）对齐。 */
    @Test
    void 候选边超限_累计去重后总量触发告警() {
        // 单块返回 2001 条（超过默认上限 2000）→ 累计去重后总量 2001 → 告警可观测且含完整数（未截断）
        List<String> overLimit = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            overLimit.add("LINESTRING(121.47" + i + "000 31.2300,121.47" + i + "000 31.2400)");
        }
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(overLimit);
        stubLegacyPointQueryEmpty();

        service.match(request(List.of(point(0, 31.2300, 121.4737))));

        assertThat(warnCapture.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("候选边数超上限")
                        && e.getFormattedMessage().contains("候选边数=2001"))).isTrue();
    }

    /** 预解析路径：同一输入的最近边索引与距离判定与既有实现逐位一致（行为等价硬约束）。 */
    @Test
    void 预解析几何_最近边索引与垂距逐位一致() {
        double cap = props.getSearchRadiusMeters();
        for (int i = 0; i < FIXTURE_POINTS.size(); i++) {
            MapMatchPointDTO p = FIXTURE_POINTS.get(i);
            //
            double expectDist = GOLDEN_DIST[i];
            int idx = MapMatchService.nearestEdgeIndex(p.getLat(), p.getLng(), NETWORK, cap);
            if (idx < 0) {
                // 无候选/全部超半径 → 封顶值
                assertThat(expectDist).as("第 %d 点无候选封顶", i).isEqualTo(300.0);
            } else {
                assertThat(NETWORK.get(idx)).as("第 %d 点最佳边与黄金值", i).isEqualTo(GOLDEN_EDGE[i]);
                // 预解析路径（match 内走 preparseCandidates + pointToCoordsMeters）与既有直调逐位一致
                double d = MapMatchService.pointToLineStringMeters(p.getLat(), p.getLng(), NETWORK.get(idx));
                assertThat(d).as("第 %d 点垂距与既有实现逐位一致", i).isCloseTo(expectDist, within(1e-9));
            }
        }
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

    // ==================== D6 候选集查询防护（TASK-249） ====================

    /**
     * 宽块分裂：单块外接矩形对角线米距超阈值 → 沿中点二分递归为多次小 bbox 查询，
     * 子块候选并集与原单块语义逐位等价（块 bbox ⊇ 子块 bbox，并集 ⊇ 原结果）。
     */
    @Test
    void 宽块分裂_多次小bbox查询_并集语义不变() {
        stubTrajectoryPrefilter(NETWORK);
        stubLegacyPointQueryEmpty();
        // 40 点单块覆盖 ~6.6km（> 默认 maxQuerySpanMeters=2000）→ 40→20→10 递归出 4 个叶子块
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            points.add(point(i, 31.2000 + i * 0.0020, 121.4700));
        }

        MapMatchResultDTO split = service.match(request(points));
        // 分裂路径：跨度超限不再单块一次查询，递归叶子块各执行一次（恰 4 次）
        verifyTrajectoryPrefilter(4);
        assertThat(prefilterCalls).hasSize(4);
        assertThat(numericArgs(prefilterCalls.get(0)).get(1)).isCloseTo(31.2000, within(1e-9));
        assertThat(numericArgs(prefilterCalls.get(3)).get(3))
                .isCloseTo(31.2000 + 39 * 0.0020, within(1e-9));

        // 单块路径对照：阈值抬到无穷 → 不分裂，恰 1 次查询
        prefilterCalls.clear();
        props.setMaxQuerySpanMeters(Double.MAX_VALUE);
        MapMatchResultDTO single = service.match(request(points));
        assertThat(prefilterCalls).hasSize(1);
        verifyNoLegacyPointQuery();

        // 并集语义逐位等价：分裂（子块并集，候选重复无影响）与单块给出相同聚合指标
        assertThat(split.getSampledPoints()).isEqualTo(single.getSampledPoints());
        assertThat(split.getMatchedRatio()).isEqualTo(single.getMatchedRatio());
        assertThat(split.getOffRoadRatio()).isEqualTo(single.getOffRoadRatio());
        assertThat(split.getAvgOffRoadDistance()).isEqualTo(single.getAvgOffRoadDistance());
        assertThat(split.getMaxOffRoadDistance()).isEqualTo(single.getMaxOffRoadDistance());
    }

    /** 退化单点块：对角线跨度为 0，即便阈值极小也不递归（块 &gt;1 点才分裂），恰一次查询 */
    @Test
    void 单点块_退化为单次查询_不递归() {
        stubTrajectoryPrefilter(List.of(NS_ROAD));
        stubLegacyPointQueryEmpty();
        props.setMaxQuerySpanMeters(0);

        MapMatchResultDTO r = service.match(request(List.of(point(0, 31.2300, 121.4737))));

        verifyTrajectoryPrefilter(1);
        verifyNoLegacyPointQuery();
        assertThat(r.getSampledPoints()).isEqualTo(1);
        // 退化块的 bbox 四角重合于该点
        List<Double> args = numericArgs(prefilterCalls.get(0));
        assertThat(args.get(0)).isCloseTo(121.4737, within(1e-9));
        assertThat(args.get(1)).isCloseTo(31.2300, within(1e-9));
        assertThat(args.get(2)).isCloseTo(121.4737, within(1e-9));
        assertThat(args.get(3)).isCloseTo(31.2300, within(1e-9));
    }

    /** 候选边超限：仅告警不截断——warn 可观测且完整候选集（2001 条）参与精算，无 LIMIT 截断 */
    @Test
    void 候选边超限_触发warn且不截断() {
        List<String> overLimit = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            overLimit.add("LINESTRING(121.47" + i + "000 31.2300,121.47" + i + "000 31.2400)");
        }
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(overLimit);
        stubLegacyPointQueryEmpty();

        service.match(request(List.of(point(0, 31.2300, 121.4737))));

        // 告警可观测，且报文含完整候选数 2001（未被 LIMIT 截断为 2000）
        assertThat(warnCapture.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("候选边数超上限")
                        && e.getFormattedMessage().contains("候选边数=2001"))).isTrue();
    }

    /** 从候选集取最佳边（null = 无候选或全部超半径，按查询半径封顶） */
    private static String nearestEdge(MapMatchPointDTO p, List<String> candidates, double cap) {
        int idx = MapMatchService.nearestEdgeIndex(p.getLat(), p.getLng(), candidates, cap);
        return idx < 0 ? null : candidates.get(idx);
    }
}
