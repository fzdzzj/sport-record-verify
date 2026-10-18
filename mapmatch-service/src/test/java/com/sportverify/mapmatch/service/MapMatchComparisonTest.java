package com.sportverify.mapmatch.service;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.TrackComparisonDTO;
import com.sportverify.mapmatch.config.MatchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轨迹智能纠偏对比单测（TASK-274：直道吸附 / 大漂移点保留 / 亚米噪声不计纠偏 /
 * 空输入防御 / 抽稀协同，JdbcTemplate 打桩不连库）。
 *
 * <p>夹具为一条南北向道路折线（经度恒定），吸附垂足有解析解：垂足纬度=原始点
 * 纬度、垂足经度=道路经度（局部平面垂足 x 分量为 0 → 反算回原经度），可逐位断言。</p>
 */
class MapMatchComparisonTest {

    /** 一条南北向道路折线（WGS84，经度恒定，纬度递增），模拟 ST_AsText 返回 */
    private static final String NS_ROAD =
            "LINESTRING(121.4737000 31.2200000,121.4737000 31.2400000)";

    /** 道路经度（垂足吸附目标值） */
    private static final double ROAD_LNG = 121.4737;

    private JdbcTemplate jdbcTemplate;
    private MatchProperties props;
    private MapMatchService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        props = new MatchProperties();
        service = new MapMatchService(jdbcTemplate, props);
    }

    private void stubRoad(List<String> candidates) {
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(candidates);
    }

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

    // ==================== 直道吸附（spec R2） ====================

    /** 路旁 0.0001°（约 9.5m ≤ 25m 阈值）的点：吸附至道路经度、计入纠偏、垂足纬度保持原值 */
    @Test
    void 直道旁点_吸附至道路经度_垂足纬度保持原值() {
        stubRoad(List.of(NS_ROAD));
        MapMatchPointDTO p = point(0, 31.2250, 121.4736);
        TrackComparisonDTO r = service.compareTrack(request(List.of(p)));

        assertThat(r.getTotalPoints()).isEqualTo(1);
        assertThat(r.getMatchedRatio()).isEqualTo(1.0);
        assertThat(r.getOffRoadRatio()).isZero();
        assertThat(r.getCorrectedCount()).isEqualTo(1);
        assertThat(r.getSnappedPoints()).hasSize(1);
        MapMatchPointDTO snapped = r.getSnappedPoints().get(0);
        assertThat(snapped.getLng()).isCloseTo(ROAD_LNG, within(1e-9));
        assertThat(snapped.getLat()).isCloseTo(31.2250, within(1e-9));
        // 契约保持：seq/ts 原样回带
        assertThat(snapped.getSeq()).isZero();
        assertThat(snapped.getTs()).isEqualTo(1_700_000_000_000L);
        // 偏离指标（1 位小数）：约 9.5m
        assertThat(r.getMaxOffRoadDistance()).isBetween(9.0, 10.0);
        assertThat(r.getAvgOffRoadDistance()).isBetween(9.0, 10.0);
    }

    /** 点列沿道路东西两侧交错：全部吸附、纠偏计数逐点累加 */
    @Test
    void 点列两侧交错_全部吸附_纠偏计数逐点累加() {
        stubRoad(List.of(NS_ROAD));
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double offset = (i % 2 == 0 ? 1 : -1) * 0.0002;
            points.add(point(i, 31.2210 + i * 0.0010, ROAD_LNG + offset));
        }
        TrackComparisonDTO r = service.compareTrack(request(points));

        assertThat(r.getTotalPoints()).isEqualTo(6);
        assertThat(r.getMatchedRatio()).isEqualTo(1.0);
        assertThat(r.getCorrectedCount()).isEqualTo(6);
        assertThat(r.getSnappedPoints()).allSatisfy(s ->
                assertThat(s.getLng()).isCloseTo(ROAD_LNG, within(1e-9)));
    }

    // ==================== 大漂移点保留（spec R2） ====================

    /** 大漂移点（约 950m ≫ 25m 阈值）：保持原经纬度、计离路、不计吸附与纠偏 */
    @Test
    void 大漂移点_保持原坐标_计离路不计纠偏() {
        stubRoad(List.of(NS_ROAD));
        MapMatchPointDTO near = point(0, 31.2250, 121.4736);
        MapMatchPointDTO drift = point(1, 31.2250, 121.4637);
        TrackComparisonDTO r = service.compareTrack(request(List.of(near, drift)));

        assertThat(r.getTotalPoints()).isEqualTo(2);
        assertThat(r.getMatchedRatio()).isEqualTo(0.5);
        assertThat(r.getOffRoadRatio()).isEqualTo(0.5);
        assertThat(r.getCorrectedCount()).isEqualTo(1);
        MapMatchPointDTO snappedDrift = r.getSnappedPoints().get(1);
        assertThat(snappedDrift.getLat()).isEqualTo(31.2250);
        assertThat(snappedDrift.getLng()).isEqualTo(121.4637);
        // 大漂移点按查询半径封顶计入 max
        assertThat(r.getMaxOffRoadDistance()).isEqualTo(props.getSearchRadiusMeters());
    }

    /** 垂距超阈值但未到查询半径：保持原坐标，max 取真实垂距而非封顶值 */
    @Test
    void 超阈值未封顶_保持原坐标_max取真实垂距() {
        stubRoad(List.of(NS_ROAD));
        // 0.002° ≈ 190m > 25m 阈值、< 300m 半径
        MapMatchPointDTO mid = point(0, 31.2250, ROAD_LNG - 0.002);
        TrackComparisonDTO r = service.compareTrack(request(List.of(mid)));

        assertThat(r.getMatchedRatio()).isZero();
        assertThat(r.getOffRoadRatio()).isEqualTo(1.0);
        assertThat(r.getCorrectedCount()).isZero();
        assertThat(r.getSnappedPoints().get(0).getLng()).isEqualTo(ROAD_LNG - 0.002);
        assertThat(r.getMaxOffRoadDistance()).isBetween(180.0, 200.0);
    }

    // ==================== 亚米噪声不计纠偏 ====================

    /** 亚米级吸附（0.000005° ≈ 0.48m < 1.0m）：照常采纳投影坐标但不计纠偏次数 */
    @Test
    void 亚米吸附_坐标照常采纳_不计纠偏次数() {
        stubRoad(List.of(NS_ROAD));
        MapMatchPointDTO p = point(0, 31.2250, ROAD_LNG - 0.000005);
        TrackComparisonDTO r = service.compareTrack(request(List.of(p)));

        assertThat(r.getMatchedRatio()).isEqualTo(1.0);
        assertThat(r.getCorrectedCount()).isZero();
        assertThat(r.getSnappedPoints().get(0).getLng())
                .isCloseTo(ROAD_LNG, within(1e-9));
    }

    // ==================== 空输入防御（spec R2） ====================

    /** 空轨迹 / null 请求：返回空点集与全零指标，不抛异常 */
    @Test
    void 空输入_返回空点集与全零指标() {
        TrackComparisonDTO empty = service.compareTrack(request(List.of()));
        assertThat(empty.getSnappedPoints()).isEmpty();
        assertThat(empty.getCorrectedCount()).isZero();
        assertThat(empty.getTotalPoints()).isZero();
        assertThat(empty.getMatchedRatio()).isZero();
        assertThat(empty.getOffRoadRatio()).isZero();
        assertThat(empty.getAvgOffRoadDistance()).isZero();
        assertThat(empty.getMaxOffRoadDistance()).isZero();

        TrackComparisonDTO nullReq = service.compareTrack(null);
        assertThat(nullReq.getSnappedPoints()).isEmpty();
        assertThat(nullReq.getCorrectedCount()).isZero();
        assertThat(nullReq.getTotalPoints()).isZero();
    }

    // ==================== 抽稀与指标协同 ====================

    /** 超长轨迹：复用 thin 抽稀，采样计算总点数封顶 maxSampledPoints */
    @Test
    void 超长轨迹_复用抽稀_总点数封顶采样上限() {
        stubRoad(List.of());
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            points.add(point(i, 31.20 + i * 0.00001, ROAD_LNG));
        }
        TrackComparisonDTO r = service.compareTrack(request(points));

        assertThat(r.getTotalPoints()).isEqualTo(props.getMaxSampledPoints());
        assertThat(r.getSnappedPoints()).hasSize(props.getMaxSampledPoints());
        // 江面无候选边：全部按查询半径封顶计离路
        assertThat(r.getOffRoadRatio()).isEqualTo(1.0);
        assertThat(r.getMaxOffRoadDistance()).isEqualTo(props.getSearchRadiusMeters());
    }
}
