package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.SegmentDTO;
import com.sportverify.api.mapmatch.dto.SegmentEffortDTO;
import com.sportverify.api.mapmatch.dto.SegmentMatchRequestDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 赛段服务（TASK-272 路段挑战与赛段刷榜）：赛段列表 + 两段式完赛匹配。
 *
 * <p>算法两段式（与 {@link MapMatchService} 的路网匹配同哲学：空间索引粗筛 + Java 精算）：</p>
 * <ol>
 *   <li><b>粗筛（SQL/PostGIS）</b>：轨迹点列最小外接矩形 {@code ST_MakeEnvelope} 外扩后
 *       {@code ST_DWithin(geom, 矩形, 半径)} 走 idx_segment_geom（GIST）预筛候选赛段；</li>
 *   <li><b>精匹（Java）</b>：对每个候选赛段，在点列中<b>按时序先后</b>寻找距起点
 *       ≤ {@value #ANCHOR_TOLERANCE_METERS}m 的锚点 P_start 与其后第一个距终点
 *       ≤ {@value #ANCHOR_TOLERANCE_METERS}m 的锚点 P_end，校验区间累计距离与标称长度的
 *       相对误差 ≤ {@value #DISTANCE_TOLERANCE_RATIO}（防跳点/抄近道假完赛），
 *       净耗时 {@code elapsed_seconds = P_end.ts - P_start.ts}。</li>
 * </ol>
 *
 * <p><b>预置兜底赛段</b>：内置 2 条上海经典赛段（世纪公园内圈 1.2km 冲刺段 / 陆家嘴滨江
 * 2.0km 挑战段，标称长度由折线坐标实时 haversine 计得、自洽无漂移）。数据库 segment 表
 * 为空、或运行环境未启动 PostGIS（{@code DataAccessException}，离线单测/开发机常态）时，
 * 以兜底集合降级提供服务——列表与匹配不因空间库缺失而 500，只损失自定义赛段能力。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentService {

    /** 纬度方向每度米数（WGS84 子午线弧长近似，与 MapMatchService 同口径） */
    private static final double METERS_PER_DEG_LAT = 111_320.0;

    /** 米转度系数（比实际每度米数偏保守，粗筛有意过覆盖，精匹兜底精度） */
    private static final double DEG_FACTOR = 85_000.0;

    /** 起终点锚定半径（米）：民用 GPS 误差 + 路网偏移余量，略宽于吸附阈值 */
    static final double ANCHOR_TOLERANCE_METERS = 35;

    /** 累计距离相对误差容差：|累计 - 标称| / 标称 ≤ 25%（防跳点/抄近道假完赛） */
    static final double DISTANCE_TOLERANCE_RATIO = 0.25;

    /** 粗筛外扩半径（米）：轨迹外接矩形向外扩该距离再进 GIST 索引，吸收 GPS 抖动 */
    private static final double COARSE_RADIUS_METERS = 50;

    /** 地球半径（米，haversine 平均半径） */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /** 1 度弧长（haversine 换算用） */
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    /**
     * 候选赛段粗筛 SQL（一次轨迹级查询）：外接矩形 {@code ST_MakeEnvelope(?, ?, ?, ?, 4326)}
     * 与 geom 求距，命中 idx_segment_geom（GIST）；实参顺序 minLng、minLat、maxLng、maxLat、
     * 半径（度），与 MapMatchService.CANDIDATE_SQL 同列序契约。sportType 过滤在 Java 侧
     * （候选集本就是小集合，避免按参拼 SQL 的两套占位符）。
     */
    private static final String SEGMENT_CANDIDATE_SQL =
            "SELECT id, name, sport_type, distance_m, ST_AsText(geom) AS geom_wkt FROM segment "
                    + "WHERE ST_DWithin(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326), ?) ORDER BY id";

    /** 赛段列表查询（不含几何）：sportType 非空时追加 WHERE 过滤 */
    private static final String SEGMENT_LIST_SQL =
            "SELECT id, name, sport_type, distance_m, created_at FROM segment";

    /** 预置兜底赛段（固定稳定 ID，标称长度由折线 haversine 实时计得） */
    private static final List<double[][]> PRESET_COORDS = List.of(
            new double[][] {{121.5500, 31.2190}, {121.5600, 31.2190}, {121.5600, 31.2212}},
            new double[][] {{121.5000, 31.2400}, {121.5080, 31.2400}, {121.5080, 31.2470}, {121.5030, 31.2470}});
    private static final List<String> PRESET_NAMES = List.of("世纪公园内圈 1.2km 冲刺段", "陆家嘴滨江 2.0km 挑战段");
    private static final List<Integer> PRESET_SPORT_TYPES = List.of(1, 2);

    private final JdbcTemplate jdbcTemplate;

    // ==================== 赛段列表 ====================

    /**
     * 查询赛段列表：优先读 segment 表（sportType 非空时按类型过滤）；
     * 表为空或空间库不可用（离线单测/开发机）时回退预置兜底赛段。
     *
     * @param sportType 运动类型过滤（null = 全部）
     */
    public List<SegmentDTO> listSegments(Integer sportType) {
        List<SegmentDTO> dbSegments = querySegmentsFromDb(sportType);
        if (dbSegments != null && !dbSegments.isEmpty()) {
            return dbSegments;
        }
        List<SegmentDTO> fallback = new ArrayList<>();
        for (int i = 0; i < PRESET_COORDS.size(); i++) {
            if (sportType != null && PRESET_SPORT_TYPES.get(i) != sportType) {
                continue;
            }
            SegmentDTO dto = new SegmentDTO();
            dto.setId((long) (i + 1));
            dto.setName(PRESET_NAMES.get(i));
            dto.setSportType(PRESET_SPORT_TYPES.get(i));
            dto.setDistanceMeters(polylineMeters(PRESET_COORDS.get(i)));
            fallback.add(dto);
        }
        if (dbSegments == null) {
            log.warn("赛段表查询失败（空间库不可用），回退预置兜底赛段：sportType={}, fallbackSize={}",
                    sportType, fallback.size());
        } else {
            log.info("赛段表为空，回退预置兜底赛段：sportType={}, fallbackSize={}", sportType, fallback.size());
        }
        return fallback;
    }

    /** segment 表列表查询；空间库不可用返回 null（区分「表空」与「库失联」两种兜底成因） */
    private List<SegmentDTO> querySegmentsFromDb(Integer sportType) {
        try {
            String sql = sportType == null
                    ? SEGMENT_LIST_SQL + " ORDER BY id"
                    : SEGMENT_LIST_SQL + " WHERE sport_type = ? ORDER BY id";
            List<java.util.Map<String, Object>> rows = sportType == null
                    ? jdbcTemplate.queryForList(sql)
                    : jdbcTemplate.queryForList(sql, sportType);
            List<SegmentDTO> out = new ArrayList<>();
            for (java.util.Map<String, Object> row : rows) {
                SegmentDTO dto = new SegmentDTO();
                dto.setId(((Number) row.get("id")).longValue());
                dto.setName((String) row.get("name"));
                Object st = row.get("sport_type");
                dto.setSportType(st == null ? 1 : ((Number) st).intValue());
                dto.setDistanceMeters(((Number) row.get("distance_m")).doubleValue());
                Object ca = row.get("created_at");
                dto.setCreatedAt(ca instanceof Timestamp ? ((Timestamp) ca).toLocalDateTime().toString()
                        : (ca == null ? null : ca.toString()));
                out.add(dto);
            }
            return out;
        } catch (DataAccessException e) {
            return null;
        }
    }

    // ==================== 两段式匹配 ====================

    /**
     * 赛段完赛匹配：粗筛候选（SQL/PostGIS，库失联或表空回退预置兜底）→ 逐候选两锚精匹。
     * 轨迹点须按时间升序且携带 ts（elapsed 计算依据）；命中零个或多个赛段时返回对应条数
     * （一条轨迹可同程完赛多条赛段），无命中返回空列表（不报错，调用方按空态呈现）。
     */
    public List<SegmentEffortDTO> matchSegments(SegmentMatchRequestDTO request) {
        List<MapMatchPointDTO> points = request == null ? null : request.getPoints();
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        double[] bbox = trackBbox(points);
        List<SegmentCandidate> candidates = fetchCandidates(bbox, request.getSportType());
        List<SegmentEffortDTO> efforts = new ArrayList<>();
        for (SegmentCandidate candidate : candidates) {
            SegmentEffortDTO effort = matchOne(candidate, points, request.getRecordId(), request.getUserId());
            if (effort != null) {
                efforts.add(effort);
            }
        }
        log.info("赛段匹配完成：totalPoints={}, candidates={}, matched={}",
                points.size(), candidates.size(), efforts.size());
        return efforts;
    }

    /** 候选赛段获取：GIST 粗筛（外接矩形外扩 {@value #COARSE_RADIUS_METERS}m）；库失联/表空回退预置兜底。 */
    private List<SegmentCandidate> fetchCandidates(double[] bbox, Integer sportType) {
        List<SegmentCandidate> candidates = new ArrayList<>();
        boolean dbFailed = false;
        try {
            double radiusDeg = COARSE_RADIUS_METERS / DEG_FACTOR;
            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SEGMENT_CANDIDATE_SQL, bbox[0], bbox[1], bbox[2], bbox[3], radiusDeg);
            for (java.util.Map<String, Object> row : rows) {
                Object wkt = row.get("geom_wkt");
                if (wkt == null) {
                    continue;
                }
                SegmentCandidate c = new SegmentCandidate();
                c.id = ((Number) row.get("id")).longValue();
                c.name = (String) row.get("name");
                Object st = row.get("sport_type");
                c.sportType = st == null ? 1 : ((Number) st).intValue();
                c.distanceMeters = ((Number) row.get("distance_m")).doubleValue();
                c.coords = parseLineString(wkt.toString());
                candidates.add(c);
            }
        } catch (DataAccessException e) {
            dbFailed = true;
        }
        if (dbFailed || candidates.isEmpty()) {
            candidates.addAll(presetCandidates(bbox));
        }
        if (sportType != null) {
            candidates.removeIf(c -> c.sportType != sportType);
        }
        return candidates;
    }

    /** 预置兜底候选：折线包围盒与轨迹外扩包围盒相交（纯 Java 包围盒粗筛，语义对齐 SQL 粗筛） */
    private static List<SegmentCandidate> presetCandidates(double[] bbox) {
        List<SegmentCandidate> out = new ArrayList<>();
        double marginDeg = COARSE_RADIUS_METERS / DEG_FACTOR;
        for (int i = 0; i < PRESET_COORDS.size(); i++) {
            double[] sb = coordsBbox(PRESET_COORDS.get(i));
            boolean intersect = sb[0] <= bbox[2] + marginDeg && sb[2] >= bbox[0] - marginDeg
                    && sb[1] <= bbox[3] + marginDeg && sb[3] >= bbox[1] - marginDeg;
            if (!intersect) {
                continue;
            }
            SegmentCandidate c = new SegmentCandidate();
            c.id = (long) (i + 1);
            c.name = PRESET_NAMES.get(i);
            c.sportType = PRESET_SPORT_TYPES.get(i);
            c.distanceMeters = polylineMeters(PRESET_COORDS.get(i));
            c.coords = PRESET_COORDS.get(i);
            out.add(c);
        }
        return out;
    }

    /**
     * 单赛段两锚精匹：时序寻找 P_start（距起点 ≤35m）与其后首个 P_end（距终点 ≤35m），
     * 校验累计距离相对误差 ≤25% 后计净耗时；任一条件不满足返回 null（不命中）。
     * recordId/userId 由请求上下文透传（命中成果供 leaderboard 上报直接使用）。
     */
    private SegmentEffortDTO matchOne(SegmentCandidate candidate, List<MapMatchPointDTO> points,
                                      Long recordId, Long userId) {
        double[] start = candidate.coords[0];
        double[] end = candidate.coords[candidate.coords.length - 1];
        int si = -1;
        int sj = -1;
        for (int i = 0; i < points.size(); i++) {
            MapMatchPointDTO p = points.get(i);
            if (p.getLat() == null || p.getLng() == null) {
                continue;
            }
            if (si < 0) {
                if (haversineMeters(p.getLat(), p.getLng(), start[1], start[0]) <= ANCHOR_TOLERANCE_METERS) {
                    si = i;
                }
                continue;
            }
            // P_end 必须严格晚于 P_start（时序约束），且首次命中即取（净耗时最小口径）
            if (sj < 0
                    && haversineMeters(p.getLat(), p.getLng(), end[1], end[0]) <= ANCHOR_TOLERANCE_METERS) {
                sj = i;
                break;
            }
        }
        if (si < 0 || sj < 0 || si >= sj) {
            return null;
        }
        MapMatchPointDTO ps = points.get(si);
        MapMatchPointDTO pe = points.get(sj);
        if (ps.getTs() == null || pe.getTs() == null) {
            // 契约上 ts 可空（路网匹配不用时间维）；赛段净耗时依赖 ts，缺失即不判定
            log.debug("赛段匹配锚点缺失 ts，跳过该候选：segmentId={}", candidate.id);
            return null;
        }
        long elapsedSeconds = Math.round((pe.getTs() - ps.getTs()) / 1000.0);
        if (elapsedSeconds <= 0) {
            return null;
        }
        double cumulative = 0;
        for (int k = si; k < sj; k++) {
            MapMatchPointDTO a = points.get(k);
            MapMatchPointDTO b = points.get(k + 1);
            if (a.getLat() == null || a.getLng() == null || b.getLat() == null || b.getLng() == null) {
                continue;
            }
            cumulative += haversineMeters(a.getLat(), a.getLng(), b.getLat(), b.getLng());
        }
        if (candidate.distanceMeters <= 0
                || Math.abs(cumulative - candidate.distanceMeters) / candidate.distanceMeters
                > DISTANCE_TOLERANCE_RATIO) {
            return null;
        }
        SegmentEffortDTO effort = new SegmentEffortDTO();
        effort.setSegmentId(candidate.id);
        effort.setSegmentName(candidate.name);
        effort.setRecordId(recordId);
        effort.setUserId(userId);
        effort.setElapsedSeconds(elapsedSeconds);
        effort.setStartTime(ps.getTs());
        effort.setEndTime(pe.getTs());
        effort.setAvgSpeed(cumulative / elapsedSeconds);
        return effort;
    }

    // ==================== 几何与解析工具 ====================

    /** haversine 球面距离（米）：小范围（城市内）与局部平面投影误差厘米级，够两锚与累计距离用 */
    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat2 - lat1) * DEG_TO_RAD;
        double dLng = (lng2 - lng1) * DEG_TO_RAD;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * DEG_TO_RAD) * Math.cos(lat2 * DEG_TO_RAD)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** 折线长度（米）：逐段 haversine 累加（预置赛段标称长度的唯一权威来源，自洽无漂移） */
    static double polylineMeters(double[][] coords) {
        double sum = 0;
        for (int i = 1; i < coords.length; i++) {
            sum += haversineMeters(coords[i - 1][1], coords[i - 1][0], coords[i][1], coords[i][0]);
        }
        return sum;
    }

    /** 点列最小外接矩形 {minLng, minLat, maxLng, maxLat}（跳过非法坐标点） */
    private static double[] trackBbox(List<MapMatchPointDTO> points) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (MapMatchPointDTO p : points) {
            if (p.getLat() == null || p.getLng() == null) {
                continue;
            }
            minLat = Math.min(minLat, p.getLat());
            maxLat = Math.max(maxLat, p.getLat());
            minLng = Math.min(minLng, p.getLng());
            maxLng = Math.max(maxLng, p.getLng());
        }
        return new double[] {minLng, minLat, maxLng, maxLat};
    }

    /** 坐标数组最小外接矩形 {minLng, minLat, maxLng, maxLat} */
    private static double[] coordsBbox(double[][] coords) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (double[] c : coords) {
            minLat = Math.min(minLat, c[1]);
            maxLat = Math.max(maxLat, c[1]);
            minLng = Math.min(minLng, c[0]);
            maxLng = Math.max(maxLng, c[0]);
        }
        return new double[] {minLng, minLat, maxLng, maxLat};
    }

    /** WKT 解析：把 LINESTRING 拆成逐点 {lng, lat} 坐标数组（仅文本解析，不含投影） */
    static double[][] parseLineString(String wkt) {
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

    /** 候选赛段（精匹入参）：库行或预置赛段的统一内存形态 */
    private static class SegmentCandidate {
        private long id;
        private String name;
        private int sportType;
        private double distanceMeters;
        private double[][] coords;
    }
}
