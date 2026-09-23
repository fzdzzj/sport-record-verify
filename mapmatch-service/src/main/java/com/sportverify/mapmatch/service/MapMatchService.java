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
            List<String> candidates = fetchCandidates(block);
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
     * 均匀抽稀：等间隔保留 max 个点（首尾必保留）。
     * 均匀抽样保持轨迹空间分布不失真——离路比例是占比指标，抽稀后仍无偏。
     */
    static <T> List<T> thin(List<T> points, int max) {
        if (points.size() <= max) {
            return points;
        }
        List<T> out = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            out.add(points.get(i * (points.size() - 1) / (max - 1)));
        }
        return out;
    }

    /**
     * 一块采样点的候选边（**一次** DB 往返）：块外接矩形按查询半径外扩后 ST_DWithin 预筛，
     * 返回候选边 WKT 供块内各点在 Java 侧精算（见类注释）。
     */
    private List<String> fetchCandidates(List<MapMatchPointDTO> block) {
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
        double radiusDeg = props.getSearchRadiusMeters() / DEG_FACTOR;
        return jdbcTemplate.queryForList(CANDIDATE_SQL, String.class, minLng, minLat, maxLng, maxLat, radiusDeg);
    }

    /**
     * 单点到候选边集合的最近垂距（米）：半径封顶 + 单调截断，语义与旧逐点 SQL 查询一致。
     * 无候选边（或候选边全部超半径）时按查询半径封顶返回（记为离路，指标有界）。
     */
    private double distanceToNearestRoad(double lat, double lng, List<String> candidates) {
        double cap = props.getSearchRadiusMeters();
        int best = nearestEdgeIndex(lat, lng, candidates, cap);
        return best < 0 ? cap : pointToLineStringMeters(lat, lng, candidates.get(best));
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
     */
    static int nearestEdgeIndex(double lat, double lng, List<String> wkts, double capMeters) {
        int best = -1;
        double bestDist = capMeters;
        for (int i = 0; i < wkts.size(); i++) {
            double d = pointToLineStringMeters(lat, lng, wkts.get(i));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 点到 LINESTRING 的最小垂距（米）：逐线段投影取最小 */
    static double pointToLineStringMeters(double lat, double lng, String wkt) {
        // WKT 形如 LINESTRING(lng lat,lng lat,...)，截掉头尾后按逗号分段
        String body = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
        String[] pts = body.split(",");
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        double[] prev = null;
        for (String pt : pts) {
            String[] c = pt.trim().split("\\s+");
            // 局部等距圆柱投影：以当前点为原点把经纬度差转成米，小范围（几百米半径）误差厘米级
            double[] cur = {(Double.parseDouble(c[0]) - lng) * metersPerDegLng,
                    (Double.parseDouble(c[1]) - lat) * METERS_PER_DEG_LAT};
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
