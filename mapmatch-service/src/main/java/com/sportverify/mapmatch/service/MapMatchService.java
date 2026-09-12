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
 *   <li>候选边预筛（SQL/PostGIS）：{@code ST_DWithin(geom, 点, 半径)} 走 GIST 索引，
 *       R-Tree 包围盒剪枝把全表 7 千+ 边裁到个位数十候选；</li>
 *   <li>精确垂距（Java）：候选边 WKT 逐线段做点到线段投影（局部等距圆柱投影转米），
 *       取最小垂距为该点离路距离。</li>
 * </ol>
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

        // 逐点最近边投影：matched=垂距≤吸附阈值；avg/max 汇总供证据明细
        int matched = 0;
        double sumDist = 0;
        double maxDist = 0;
        for (MapMatchPointDTO p : sampled) {
            double dist = distanceToNearestRoad(p.getLat(), p.getLng());
            sumDist += dist;
            maxDist = Math.max(maxDist, dist);
            if (dist <= props.getSnapThresholdMeters()) {
                matched++;
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
     * 单点到最近道路边的垂距（米）。
     * 无候选边时按查询半径封顶返回（记为离路，指标有界）。
     */
    private double distanceToNearestRoad(double lat, double lng) {
        double radiusDeg = props.getSearchRadiusMeters() / DEG_FACTOR;
        List<String> wkts = jdbcTemplate.queryForList(
                // GIST 索引预筛：ST_DWithin 的包围盒扩展可命中 idx_road_edge_geom；
                // ST_AsText 回传 WKT，几何计算放 Java 侧（见类注释）
                "SELECT ST_AsText(geom) FROM road_edge "
                        + "WHERE ST_DWithin(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)",
                String.class, lng, lat, radiusDeg);
        double best = props.getSearchRadiusMeters();
        for (String wkt : wkts) {
            double d = pointToLineStringMeters(lat, lng, wkt);
            if (d < best) {
                best = d;
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
