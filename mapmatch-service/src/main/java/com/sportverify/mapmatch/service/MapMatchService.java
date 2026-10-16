package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.mapmatch.config.MatchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 道路拓扑匹配服务（最近边投影 + 偏离度计算，见 ADR-0006）。
 *
 * <p>算法两段式：</p>
 * <ol>
 *   <li><b>候选边预筛（SQL/PostGIS，按轨迹分块一次）</b>：取一块采样点的最小外接矩形
 *       {@code ST_MakeEnvelope}，按查询半径外扩后 {@code ST_DWithin(geom, 矩形, 半径)} 走
 *       GIST 索引，R-Tree 包围盒剪枝把全表 7 千+ 边裁到十数条候选；</li>
 *   <li><b>精确垂距（Java，块内逐点复用同一候选集）</b>：候选边 WKT 逐线段做点到线段投影
 *       （局部等距圆柱投影转米），取最小垂距为该点离路距离。</li>
 * </ol>
 * <p>为什么把预筛参照物从「单个点」放宽到「整块的外接矩形」：逐点预筛的 DB 往返次数 =
 * 采样点数（上界 {@code max-sampled-points}=200，且这段在 R5 同步关键路径上）；
 * 而按矩形预筛的结果依集合包含关系是逐点结果的<b>超集</b>——外接矩形包含块内任一点，
 * 故「点 → 矩形」的平面距离 ≤ 「点 → 任一点」；超集多出的边若垂距小于查询半径，
 * 则它本就落在该点的度数半径内、必属逐点结果，与「多出」矛盾。于是多出的边垂距必 ≥ 半径，
 * 被半径封顶的单调截断吞掉，<b>判定结果逐位不变</b>（形式化说明见 {@link #nearestEdgeIndex}）。
 * 采样点超过 {@code prefilter-chunk-points} 时按点序分块，往返数收敛为该阈值决定的常数上界。</p>
 * <p>为什么不用 PostGIS 直接算距离：垂距要的是「米」而路网存的是 WGS84 度，
 * ST_Distance(geography) 每点触发大地线计算且不好复用预筛结果；两段式把索引能力
 * 和简单可控的平面数学各自用在刀刃上，也便于单测覆盖几何精度。</p>
 * <p>HMM（隐马尔可夫）全局匹配是进阶项：最近边投影逐点独立吸附，在密集路网
 * 交叉口的吸附归属可能抖动，但对「离路比例」这一粗粒度判定指标足够，先跑通闭环
 * （提案既定决策，HMM 留作算法升级路径）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapMatchService {

    /** 纬度方向每度米数（WGS84 子午线弧长近似，全球变化 <0.5%，误差可忽略） */
    private static final double METERS_PER_DEG_LAT = 111_320.0;

    /**
     * ST_DWithin 半径的米转度系数：按 85,000 米/度折算（比实际经度向 ~95km/度、
     * 纬度向 ~111km/度都偏保守），预筛有意过覆盖——宁可多筛几条候选边，
     * 也不因半径换算误差漏掉真实最近边；精确性由 Java 侧垂距计算兜底
     */
    private static final double DEG_FACTOR = 85_000.0;

    /**
     * 候选边预筛 SQL（一块采样点一次）：块外接矩形 {@code ST_MakeEnvelope(?, ?, ?, ?, 4326)}
     * 与 geom 求距，包围盒扩展仍命中 idx_road_edge_geom（GIST）；
     * ST_AsText 回传 WKT，几何计算放 Java 侧（见类注释）。
     * 实参顺序：minLng、minLat、maxLng、maxLat、半径（度）。单点块的外接矩形退化，
     * ST_DWithin 按包围盒语义照常处理。
     */
    private static final String CANDIDATE_SQL =
            "SELECT ST_AsText(geom) FROM road_edge "
                    + "WHERE ST_DWithin(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326), ?)";

    private final JdbcTemplate jdbcTemplate;
    private final MatchProperties props;

    /**
     * 对轨迹做路网匹配并计算偏离指标。
     *
     * @param request 轨迹点（按时间升序；空轨迹返回全零指标，不报错——R5 侧按点数阈值自行跳过）
     */
    public MapMatchResultDTO match(MapMatchRequestDTO request) {
        List<MapMatchPointDTO> raw = request == null ? null : request.getPoints();
        if (raw == null || raw.isEmpty()) {
            log.warn("匹配请求无轨迹点，返回全零指标");
            return new MapMatchResultDTO(0, 0, 0, 0, 0, 0);
        }

        List<MapMatchPointDTO> sampled = thin(raw, props.getMaxSampledPoints());

        // 轨迹级预筛 + 逐点垂距：按点序分块，每块一次 bbox 预筛取回候选边，块内各点在 Java 侧
        // 复用同一候选集算垂距。DB 往返数 = 分块数（≤ ceil(采样点 / 分块阈值)），与采样点数无关
        int matched = 0;
        double sumDist = 0;
        double maxDist = 0;
        int chunkSize = Math.max(1, props.getPrefilterChunkPoints());
        for (int start = 0; start < sampled.size(); start += chunkSize) {
            List<MapMatchPointDTO> block = sampled.subList(start, Math.min(sampled.size(), start + chunkSize));
            List<String> candidateWkts = fetchCandidates(block);
            // E29：候选 WKT 进入点匹配前一次性解析为坐标数组（每边一次），块内各点复用，
            // 消除 O(点数×边数) 次重复 substring/split/parseDouble（告警不截断现状保留）
            double[][][] candidates = preparseCandidates(candidateWkts);
            for (MapMatchPointDTO p : block) {
                double dist = distanceToNearestRoad(p.getLat(), p.getLng(), candidates);
                sumDist += dist;
                maxDist = Math.max(maxDist, dist);
                if (dist <= props.getSnapThresholdMeters()) {
                    matched++;
                }
            }
        }

        int n = sampled.size();
        MapMatchResultDTO result = new MapMatchResultDTO(
                (double) matched / n,
                (double) (n - matched) / n,
                sumDist / n,
                maxDist,
                n,
                raw.size());
        log.info("匹配完成：total={}, sampled={}, matchedRatio={}, offRoadRatio={}, avgOffRoad={}m, maxOffRoad={}m",
                raw.size(), n, result.getMatchedRatio(), result.getOffRoadRatio(),
                String.format("%.1f", result.getAvgOffRoadDistance()), String.format("%.1f", result.getMaxOffRoadDistance()));
        return result;
    }

    /**
     * 均匀抽稀：等间隔保留 effectiveMax 个点（首尾必保留）。
     * 均匀抽样保持轨迹空间分布不失真——离路比例是占比指标，抽稀后仍无偏。
     * <p>E17-③ 下界防御：{@code effectiveMax = Math.max(2, max)}——maxSampledPoints 配 0/1 时
     * 不再退化为空列表（循环不执行 → n=0 → 0/0 NaN fail-open）或 {@code max-1=0} 除零
     * ArithmeticException，按至少首尾 2 点采样；正常 ≥2 配置行为逐位不变（effectiveMax==max，
     * 既有采样序列不变）。</p>
     */
    static <T> List<T> thin(List<T> points, int max) {
        int effectiveMax = Math.max(2, max);
        if (points.size() <= effectiveMax) {
            return points;
        }
        List<T> out = new ArrayList<>(effectiveMax);
        for (int i = 0; i < effectiveMax; i++) {
            out.add(points.get(i * (points.size() - 1) / (effectiveMax - 1)));
        }
        return out;
    }

    /**
     * 一块采样点的候选边（**一次或多次** DB 往返）：块外接矩形按查询半径外扩后 ST_DWithin 预筛，
     * 返回候选边 WKT 供块内各点在 Java 侧精算（见类注释）。
     *
     * <p>D6 自适应防护：块外接矩形对角线米距（沿用 {@link #DEG_FACTOR} 换算）超
     * {@code maxQuerySpanMeters} 且块内 &gt;1 点 → 沿点序中点二分递归，子块独立预筛后并集。
     * 块 bbox ⊇ 子块 bbox，故子块结果并集 ⊇ 原块结果，逐点判定结果逐位不变（见
     * {@link #nearestEdgeIndex} 超集论证）；防马拉松等长轨迹单块（默认 50 点）外接矩形
     * 覆盖整条走廊，把 ST_DWithin 候选边巨量全拉进 JVM。</p>
     */
    private List<String> fetchCandidates(List<MapMatchPointDTO> block) {
        // E29：集合容器由 List 改 LinkedHashSet——子块空间重叠区同边 WKT 去重（保序），
        // 既消除候选集重复（同边在同一 block 内不再被多次精算），也保证块级候选集仍为各点
        // 逐点候选集的超集（去重不改变合法性）；最终以不可变列表返回
        LinkedHashSet<String> acc = new LinkedHashSet<>();
        collectCandidates(block, acc);
        if (acc.size() > props.getMaxCandidateEdges()) {
            // 仅告警观测，严禁 LIMIT 截断（截断丢真近邻边 → 假离路 → R5 误判）；
            // E29：判据由单叶 candidates.size() 改累计去重后总量（告警不截断现状保留）
            log.warn("候选边数超上限（仅告警不截断，防丢真近邻边）：累计候选边数={}, 上限={}",
                    acc.size(), props.getMaxCandidateEdges());
        }
        return List.copyOf(acc);
    }

    /** 自适应分块递归：块跨度超阈值即中点二分，叶子块执行一次 bbox 预筛并累加候选边。 */
    private void collectCandidates(List<MapMatchPointDTO> block, LinkedHashSet<String> acc) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (MapMatchPointDTO p : block) {
            minLat = Math.min(minLat, p.getLat());
            maxLat = Math.max(maxLat, p.getLat());
            minLng = Math.min(minLng, p.getLng());
            maxLng = Math.max(maxLng, p.getLng());
        }
        if (block.size() > 1
                && diagonalSpanMeters(minLat, minLng, maxLat, maxLng) > props.getMaxQuerySpanMeters()) {
            // 跨度超限：沿点序中点二分（子块 bbox ⊇ 原块内各点），递归至叶子块
            int mid = block.size() / 2;
            collectCandidates(block.subList(0, mid), acc);
            collectCandidates(block.subList(mid, block.size()), acc);
            return;
        }
        double radiusDeg = props.getSearchRadiusMeters() / DEG_FACTOR;
        List<String> candidates = jdbcTemplate.queryForList(CANDIDATE_SQL, String.class,
                minLng, minLat, maxLng, maxLat, radiusDeg);
        if (candidates != null) {
            acc.addAll(candidates);
        }
    }

    /** 块外接矩形对角线米距（米）：经纬度跨度均沿用 {@link #DEG_FACTOR} 折算后取欧氏对角长。 */
    private static double diagonalSpanMeters(double minLat, double minLng, double maxLat, double maxLng) {
        double latSpanMeters = (maxLat - minLat) * DEG_FACTOR;
        double lngSpanMeters = (maxLng - minLng) * DEG_FACTOR;
        return Math.hypot(latSpanMeters, lngSpanMeters);
    }

    /**
     * 单点到候选边集合的最近垂距（米）：半径封顶 + 单调截断，语义与旧逐点 SQL 查询一致。
     * 无候选边（或候选边全部超半径）时按查询半径封顶返回（记为离路，指标有界）。
     * E29：入参为预解析坐标数组（见 {@link #preparseCandidates}），距离计算复用解析结果。
     */
    private double distanceToNearestRoad(double lat, double lng, double[][][] candidates) {
        double cap = props.getSearchRadiusMeters();
        int best = nearestEdgeIndex(lat, lng, candidates, cap);
        return best < 0 ? cap : pointToCoordsMeters(lat, lng, candidates[best]);
    }

    /**
     * 候选边中与给定点垂距最小者的下标；无候选边或全部 ≥ capMeters 时返回 -1。
     *
     * <p>这里承载「超半径候选不改变结果」的语义不变性：初值取 {@code capMeters} 且只在
     * {@code d < best} 时收敛（单调截断），故任何垂距 ≥ cap 的候选边都不可能被选中。
     * 轨迹级预筛返回的是逐点预筛结果的<b>超集</b>（块外接矩形 ⊇ 块内任一点，故块级
     * 候选集 ⊇ 逐点候选集），且超集里多出的边若垂距 < cap，则其度数距离
     * {@code d / 85,000 < cap / 85,000 = 半径(度)}（DEG_FACTOR 取值小于实际每度米数，
     * 故这是保守下界），说明它本就落在该点的度数半径内、必属逐点结果——与「多出」矛盾。
     * 于是多出的边垂距必 ≥ cap，被截断吞掉，两实现输出逐位一致。</p>
     *
     * <p>E29：本匹配循环对预解析坐标数组逐点计算（每边解析仅一次，见
     * {@link #preparseCandidates}）；{@link #nearestEdgeIndex(double, double, List, double)}
     * 为既有直调形态（测试/兼容入口），内部先整体解析再委托本方法，输出逐位一致。</p>
     */
    private static int nearestEdgeIndex(double lat, double lng, double[][][] coords, double capMeters) {
        int best = -1;
        double bestDist = capMeters;
        for (int i = 0; i < coords.length; i++) {
            double d = pointToCoordsMeters(lat, lng, coords[i]);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 既有直调形态（测试/兼容）：入参为 WKT，内部整体预解析后委托坐标数组版本，行为逐位一致。 */
    static int nearestEdgeIndex(double lat, double lng, List<String> wkts, double capMeters) {
        return nearestEdgeIndex(lat, lng, preparseCandidates(wkts), capMeters);
    }

    /** E29：把候选 WKT 一次性解析为坐标数组（每边一次），供块内各点复用。 */
    private static double[][][] preparseCandidates(List<String> wkts) {
        double[][][] parsed = new double[wkts.size()][][];
        for (int i = 0; i < wkts.size(); i++) {
            parsed[i] = parseWkt(wkts.get(i));
        }
        return parsed;
    }

    /** WKT 解析：把 LINESTRING 拆成逐点经纬度坐标数组（lng/lat），仅文本解析，不含投影。 */
    private static double[][] parseWkt(String wkt) {
        // WKT 形如 LINESTRING(lng lat,lng lat,...)，截掉头尾后按逗号分段
        String body = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
        String[] pts = body.split(",");
        double[][] coords = new double[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            String[] c = pts[i].trim().split("\\s+");
            coords[i][0] = Double.parseDouble(c[0]); // lng
            coords[i][1] = Double.parseDouble(c[1]); // lat
        }
        return coords;
    }

    /** 点到 LINESTRING 的最小垂距（米）：逐线段投影取最小（解析一次后复用坐标数组） */
    static double pointToLineStringMeters(double lat, double lng, String wkt) {
        return pointToCoordsMeters(lat, lng, parseWkt(wkt));
    }

    /** 点到预解析坐标数组的最小垂距（米）：复用同一次解析结果，逐线段投影取最小 */
    private static double pointToCoordsMeters(double lat, double lng, double[][] coords) {
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        double[] prev = null;
        for (int i = 0; i < coords.length; i++) {
            // 局部等距圆柱投影：以当前点为原点把经纬度差转成米，小范围（几百米半径）误差厘米级
            double[] cur = {(coords[i][0] - lng) * metersPerDegLng,
                    (coords[i][1] - lat) * METERS_PER_DEG_LAT};
            if (prev != null) {
                best = Math.min(best, pointToSegmentMeters(cur[0], cur[1], prev[0], prev[1]));
            }
            prev = cur;
        }
        return best;
    }

    /** 点(0,0) 到线段(prev→cur) 的垂距（米，局部平面坐标系内）：垂足落在线段外时取端点距 */
    private static double pointToSegmentMeters(double cx, double cy, double ax, double ay) {
        double dx = cx - ax, dy = cy - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {                    // 线段退化为点
            return Math.hypot(ax, ay);
        }
        // t∈[0,1] 为垂足在线段上的投影参数，clamp 到端点
        double t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lenSq));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }
}
