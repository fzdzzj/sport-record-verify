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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 匹配算法单测（纯几何与指标聚合逻辑，JdbcTemplate 打桩不连库）。
 *
 * <p>真实路网端到端验证（沿路 vs 悬浮）见交付说明冒烟记录；此处锁定：
 * 抽稀保点数上限、沿路轨迹指标归零、悬浮轨迹离路比例 1、WKT 垂距精度。</p>
 */
class MapMatchServiceTest {

    private JdbcTemplate jdbcTemplate;
    private MatchProperties props;
    private MapMatchService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        props = new MatchProperties();
        service = new MapMatchService(jdbcTemplate, props);
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

    @Test
    void 沿路轨迹_全部吸附_离路比例为零() {
        when(jdbcTemplate.queryForList(any(String.class), eq(String.class),
                anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(NS_ROAD));
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
                anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
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
                anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
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
}
