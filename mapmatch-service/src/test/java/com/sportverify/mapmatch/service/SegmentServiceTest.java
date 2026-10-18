package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.SegmentDTO;
import com.sportverify.api.mapmatch.dto.SegmentEffortDTO;
import com.sportverify.api.mapmatch.dto.SegmentMatchRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 赛段服务单元测试（TASK-272）：赛段列表（库行映射 / 表空与库失联回退预置兜底）与
 * 两段式匹配正反例（整段命中 / SQL 候选命中 / 起锚未达终点 / 距离偏差超容差 / 零耗时 /
 * ts 缺失 / 类型过滤 / 简并请求）。Mockito 打桩 JdbcTemplate，不连任何数据库——
 * 兜底路径本身就是「未启动 PostGIS」的生产降级形态，离线单测即真实路径。
 */
class SegmentServiceTest {

    private JdbcTemplate jdbcTemplate;
    private SegmentService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new SegmentService(jdbcTemplate);
    }

    // ==================== 赛段列表（预置兜底 + 库行映射） ====================

    @Test
    void 赛段列表_表空回退预置两条上海经典赛段() {
        // 未打桩的 queryForList 返回空列表（Mockito 缺省）= segment 表为空 → 预置兜底
        List<SegmentDTO> segments = service.listSegments(null);
        assertEquals(2, segments.size());
        SegmentDTO first = segments.get(0);
        assertEquals(1L, first.getId());
        assertTrue(first.getName().contains("世纪公园"), "预置赛段一应为世纪公园内圈冲刺段");
        assertEquals(1, first.getSportType());
        assertTrue(first.getDistanceMeters() > 1100 && first.getDistanceMeters() < 1300,
                "世纪公园段标称长度应约 1.2km（haversine 实时计得），实测 " + first.getDistanceMeters());
        assertNull(first.getCreatedAt(), "预置赛段无建段时间");
        SegmentDTO second = segments.get(1);
        assertEquals(2L, second.getId());
        assertTrue(second.getName().contains("陆家嘴"), "预置赛段二应为陆家嘴滨江挑战段");
        assertEquals(2, second.getSportType());
        assertTrue(second.getDistanceMeters() > 1900 && second.getDistanceMeters() < 2100,
                "陆家嘴段标称长度应约 2.0km，实测 " + second.getDistanceMeters());
    }

    @Test
    void 赛段列表_按运动类型过滤预置兜底() {
        assertEquals(1, service.listSegments(1).size());
        assertEquals("世纪公园内圈 1.2km 冲刺段", service.listSegments(1).get(0).getName());
        assertEquals("陆家嘴滨江 2.0km 挑战段", service.listSegments(2).get(0).getName());
    }

    @Test
    void 赛段列表_库行映射字段与建段时间格式化() {
        Map<String, Object> row = Map.of(
                "id", 9L,
                "name", "滨江自定义段",
                "sport_type", 2,
                "distance_m", 800.5,
                "created_at", Timestamp.valueOf("2026-10-18 10:00:30"));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));
        List<SegmentDTO> segments = service.listSegments(null);
        assertEquals(1, segments.size());
        SegmentDTO dto = segments.get(0);
        assertEquals(9L, dto.getId());
        assertEquals("滨江自定义段", dto.getName());
        assertEquals(2, dto.getSportType());
        assertEquals(800.5, dto.getDistanceMeters());
        assertEquals("2026-10-18T10:00:30", dto.getCreatedAt());
    }

    // ==================== 两段式匹配（正例） ====================

    @Test
    void 赛段匹配_整段轨迹命中预置赛段_耗时均速齐备() {
        // 5-arg 粗筛返回空列表 = segment 表为空 → 预置兜底候选；轨迹沿世纪公园段全折线
        stubCandidateQuery(List.of());
        SegmentMatchRequestDTO request = request(55L, 100L, null, pointsAlongCenturyPark(0L, 30000L));
        List<SegmentEffortDTO> efforts = service.matchSegments(request);
        assertEquals(1, efforts.size());
        SegmentEffortDTO effort = efforts.get(0);
        assertEquals(1L, effort.getSegmentId());
        assertEquals("世纪公园内圈 1.2km 冲刺段", effort.getSegmentName());
        assertEquals(55L, effort.getRecordId());
        assertEquals(100L, effort.getUserId());
        assertEquals(120L, effort.getElapsedSeconds(), "净耗时 = P_end.ts - P_start.ts = 120s");
        assertEquals(0L, effort.getStartTime());
        assertEquals(120000L, effort.getEndTime());
        assertTrue(effort.getAvgSpeed() > 5 && effort.getAvgSpeed() < 20, "均速应为完赛距离/净耗时的米每秒量级");
        assertNull(effort.getIsPr(), "匹配产出尚未上报，isPr 应为 null（由 leaderboard 判定回填）");
        assertNull(effort.getIsKom(), "匹配产出尚未上报，isKom 应为 null");
    }

    @Test
    void 赛段匹配_空间库失联回退预置兜底仍可命中() {
        // 离线/开发机常态：JdbcTemplate 抛 DataAccessException → 预置兜底，匹配不因库缺失失败
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("postgis down"));
        SegmentMatchRequestDTO request = request(55L, 100L, null, pointsAlongCenturyPark(0L, 30000L));
        assertEquals(1, service.matchSegments(request).size());
    }

    @Test
    void 赛段匹配_SQL候选行命中_锚点半径与距离容差内() {
        // 候选行折线 ≈ 950.9m，标称 950.0：轨迹首尾锚点各偏离顶点约 19m（≤35m），
        // 累计 ≈ 913m 相对误差 ≈ 3.9%（≤25%）→ 命中
        Map<String, Object> row = Map.of(
                "id", 7L,
                "name", "测试段",
                "sport_type", 1,
                "distance_m", 950.0,
                "geom_wkt", "LINESTRING(121.55 31.219, 121.56 31.219)");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
        SegmentMatchRequestDTO request = request(55L, 100L, 1, List.of(
                point(0, 31.219, 121.5502, 0L),
                point(1, 31.219, 121.5550, 20000L),
                point(2, 31.219, 121.5597, 40000L)));
        List<SegmentEffortDTO> efforts = service.matchSegments(request);
        assertEquals(1, efforts.size());
        SegmentEffortDTO effort = efforts.get(0);
        assertEquals(7L, effort.getSegmentId());
        assertEquals("测试段", effort.getSegmentName());
        assertEquals(40L, effort.getElapsedSeconds());
        assertTrue(effort.getAvgSpeed() > 0);
    }

    // ==================== 两段式匹配（反例） ====================

    @Test
    void 赛段匹配_轨迹远离全部赛段_空列表() {
        stubCandidateQuery(List.of());
        SegmentMatchRequestDTO request = request(55L, 100L, null, List.of(
                point(0, 31.5000, 121.9000, 0L),
                point(1, 31.5010, 121.9050, 30000L)));
        assertTrue(service.matchSegments(request).isEmpty(), "远离预置赛段的轨迹不应命中");
    }

    @Test
    void 赛段匹配_仅起锚未达终点_空列表() {
        stubCandidateQuery(List.of());
        // 轨迹从起点出发只走到中段，终点 35m 锚定半径内无点
        SegmentMatchRequestDTO request = request(55L, 100L, null, List.of(
                point(0, 31.2190, 121.5500, 0L),
                point(1, 31.2190, 121.5550, 30000L),
                point(2, 31.2190, 121.5575, 60000L)));
        assertTrue(service.matchSegments(request).isEmpty());
    }

    @Test
    void 赛段匹配_累计距离偏差超容差_空列表() {
        stubCandidateQuery(List.of());
        // 两锚点齐备但中段大幅绕行（累计 ≈ 34km ≫ 标称 1.2km，偏差 ≫ 25%）→ 判假完赛
        SegmentMatchRequestDTO request = request(55L, 100L, null, List.of(
                point(0, 31.2190, 121.5500, 0L),
                point(1, 31.3000, 121.7000, 30000L),
                point(2, 31.2212, 121.5600, 60000L)));
        assertTrue(service.matchSegments(request).isEmpty());
    }

    @Test
    void 赛段匹配_锚点同时刻零耗时_空列表() {
        stubCandidateQuery(List.of());
        // 全程 ts 相同 → elapsed = 0 → 不判定（防除零与假成绩）
        SegmentMatchRequestDTO request = request(55L, 100L, null, pointsAlongCenturyPark(0L, 0L));
        assertTrue(service.matchSegments(request).isEmpty());
    }

    @Test
    void 赛段匹配_锚点缺ts_空列表() {
        stubCandidateQuery(List.of());
        // 契约上 ts 可空（路网匹配不用时间维）；赛段净耗时依赖 ts，缺失即不判定
        SegmentMatchRequestDTO request = request(55L, 100L, null, List.of(
                point(0, 31.2190, 121.5500, null),
                point(1, 31.2190, 121.5600, 60000L),
                point(2, 31.2212, 121.5600, 120000L)));
        assertTrue(service.matchSegments(request).isEmpty());
    }

    @Test
    void 赛段匹配_运动类型过滤排除候选_空列表() {
        // 候选行为骑行段（sport_type=2），请求按跑步（1）过滤 → Java 侧剔除 → 空列表
        Map<String, Object> row = Map.of(
                "id", 8L,
                "name", "骑行段",
                "sport_type", 2,
                "distance_m", 950.0,
                "geom_wkt", "LINESTRING(121.55 31.219, 121.56 31.219)");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
        SegmentMatchRequestDTO request = request(55L, 100L, 1, List.of(
                point(0, 31.219, 121.5502, 0L),
                point(1, 31.219, 121.5597, 40000L)));
        assertTrue(service.matchSegments(request).isEmpty());
    }

    @Test
    void 赛段匹配_简并请求_空列表() {
        stubCandidateQuery(List.of());
        assertTrue(service.matchSegments(null).isEmpty());
        SegmentMatchRequestDTO emptyPoints = new SegmentMatchRequestDTO();
        emptyPoints.setPoints(List.of());
        assertTrue(service.matchSegments(emptyPoints).isEmpty());
    }

    // ==================== 测试数据与打桩工具 ====================

    /** 打桩候选粗筛（5 占位符实参）返回指定行集；any(Object[].class) 整体匹配 varargs，
     *  避免重载决议选中 queryForList(String, Class, Object...)（T=Object 的错误分支） */
    private void stubCandidateQuery(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    /** 沿世纪公园预置折线（含中段插值点）的完整轨迹；ts 从 0 起步、步长 stepMillis */
    private static List<MapMatchPointDTO> pointsAlongCenturyPark(long firstTs, long stepMillis) {
        return List.of(
                point(0, 31.2190, 121.5500, firstTs),
                point(1, 31.2190, 121.5550, firstTs + stepMillis),
                point(2, 31.2190, 121.5600, firstTs + 2 * stepMillis),
                point(3, 31.2201, 121.5600, firstTs + 3 * stepMillis),
                point(4, 31.2212, 121.5600, firstTs + 4 * stepMillis));
    }

    private static SegmentMatchRequestDTO request(Long recordId, Long userId, Integer sportType,
                                                  List<MapMatchPointDTO> points) {
        SegmentMatchRequestDTO request = new SegmentMatchRequestDTO();
        request.setRecordId(recordId);
        request.setUserId(userId);
        request.setSportType(sportType);
        request.setPoints(points);
        return request;
    }

    private static MapMatchPointDTO point(int seq, double lat, double lng, Long ts) {
        MapMatchPointDTO p = new MapMatchPointDTO();
        p.setSeq(seq);
        p.setLat(lat);
        p.setLng(lng);
        p.setTs(ts);
        return p;
    }
}
