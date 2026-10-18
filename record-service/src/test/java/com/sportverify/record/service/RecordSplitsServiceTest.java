package com.sportverify.record.service;

import com.sportverify.api.record.dto.KilometerSplitDTO;
import com.sportverify.api.record.dto.RecordSplitsDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公里分段配速切片单元测试（TASK-273：千米线性插值 / 末尾余程 / 空点退化 / 越权拦截）。
 *
 * <p>纯 Mockito：Mapper 全 mock，不依赖 MySQL。插值用例沿同经线（lng=0）北向布点，
 * 同经线 Haversine 距离 = R × Δrad（asin∘sin 恒等误差 &lt; 1e-9 米），经 round2/取整后
 * 断言值逐位确定。算法本体经包可见 {@code buildRecordSplits} 直测（零 Mapper 参与）。</p>
 */
class RecordSplitsServiceTest {

    private static final long BASE_TS = 1_700_000_000_000L;

    private SportRecordMapper sportRecordMapper;
    private TrackPointMapper trackPointMapper;
    private SportRecordService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        trackPointMapper = mock(TrackPointMapper.class);
        service = new SportRecordService(sportRecordMapper, trackPointMapper,
                mock(com.sportverify.record.mapper.TrackPointArchiveMapper.class),
                mock(com.sportverify.record.mq.RecordEventProducer.class),
                mock(com.sportverify.api.verify.VerifyApi.class),
                mock(VerifyDegradeService.class),
                mock(com.sportverify.api.user.UserApi.class));
    }

    /** 同经线北向移动 meters 米后的纬度（lng 恒 0，Haversine 沿经线解析可算）。 */
    private static double latOffset(double meters) {
        return Math.toDegrees(meters / 6_371_000.0);
    }

    private static TrackPointDTO pt(int seq, double lat, long ts) {
        TrackPointDTO p = new TrackPointDTO();
        p.setSeq(seq);
        p.setLat(BigDecimal.valueOf(lat));
        p.setLng(BigDecimal.ZERO);
        p.setTs(ts);
        return p;
    }

    // ==================== 鉴权与归属（getRecordSplits 门矩阵） ====================

    /** 记录不存在 → 3001 RECORD_NOT_FOUND。 */
    @Test
    void getRecordSplits_recordNotFound_throws3001() {
        when(sportRecordMapper.selectById(9L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.getRecordSplits(100L, 9L));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    /** auth 开、caller 非归属人 → 1002 FORBIDDEN（越权拦截）。 */
    @Test
    void getRecordSplits_notOwner_throws1002() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        BizException e = assertThrows(BizException.class, () -> service.getRecordSplits(100L, 5L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    /** auth 开、caller 为归属人 → 放行（轨迹点空 → 优雅空切片）。 */
    @Test
    void getRecordSplits_owner_passesAndReturnsEmptySplits() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        when(trackPointMapper.selectList(any())).thenReturn(List.of());

        RecordSplitsDTO out = service.getRecordSplits(200L, 5L);

        assertEquals(5L, out.getRecordId());
        assertTrue(out.getSplits().isEmpty());
        assertEquals(0.0, out.getTotalDistanceMeters());
        assertFalse(out.getHasElevationData());
    }

    /** auth 关闭降级态（caller=null）→ 放行，行为与旧行为一致。 */
    @Test
    void getRecordSplits_callerNull_passes() {
        SportRecord record = new SportRecord();
        record.setId(5L);
        record.setUserId(200L);
        when(sportRecordMapper.selectById(5L)).thenReturn(record);
        when(trackPointMapper.selectList(any())).thenReturn(List.of());

        RecordSplitsDTO out = service.getRecordSplits(null, 5L);

        assertTrue(out.getSplits().isEmpty());
    }

    // ==================== 切片算法（buildRecordSplits 直测） ====================

    /** 空轨迹 → 空 splits、总里程 0、无最快最慢、无海拔。 */
    @Test
    void buildRecordSplits_emptyPoints_gracefulEmpty() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of());
        assertEquals(1L, out.getRecordId());
        assertTrue(out.getSplits().isEmpty());
        assertEquals(0.0, out.getTotalDistanceMeters());
        assertNull(out.getFastestKm());
        assertNull(out.getSlowestKm());
        assertNull(out.getTotalElapsedSeconds());
        assertEquals(0.0, out.getTotalElevationGain());
        assertEquals(0.0, out.getTotalElevationLoss());
        assertFalse(out.getHasElevationData());
    }

    /** 单点 → 累计 0 米，无阈值可跨、余程 0 不足 10m → 空 splits。 */
    @Test
    void buildRecordSplits_singlePoint_noSplits() {
        RecordSplitsDTO out = service.buildRecordSplits(1L,
                List.of(pt(0, 31.23, BASE_TS)));
        assertTrue(out.getSplits().isEmpty());
        assertEquals(0.0, out.getTotalDistanceMeters());
        assertEquals(0, out.getTotalElapsedSeconds());
        assertNull(out.getFastestKm());
    }

    /** 不足 1km（500m/100s）→ 仅一条末尾余程段（km=1），无满公里 → fastestKm/slowestKm 恒 null。 */
    @Test
    void buildRecordSplits_subKilometer_tailOnly() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of(
                pt(0, 0, BASE_TS),
                pt(1, latOffset(500), BASE_TS + 100_000)));
        assertEquals(500.0, out.getTotalDistanceMeters());
        assertEquals(100, out.getTotalElapsedSeconds());
        assertEquals(1, out.getSplits().size());
        KilometerSplitDTO tail = out.getSplits().get(0);
        assertEquals(1, tail.getKm());
        assertEquals(500.0, tail.getDistanceMeters());
        assertEquals(100, tail.getElapsedSeconds());
        assertEquals("3'20\"", tail.getPaceFormatted());
        assertEquals(5.0, tail.getAvgSpeed());
        assertFalse(tail.getIsFastest());
        assertFalse(tail.getIsSlowest());
        assertNull(out.getFastestKm());
        assertNull(out.getSlowestKm());
    }

    /** 跨 2km 线性插值（1500m/450s + 1400m/560s）：1000m 在首段 2/3 处、2000m 在次段 500/1400 处越线；900m 余程独立成段。 */
    @Test
    void buildRecordSplits_interpolatedCrossings_andTail() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of(
                pt(0, 0, BASE_TS),
                pt(1, latOffset(1500), BASE_TS + 450_000),
                pt(2, latOffset(2900), BASE_TS + 1_010_000)));
        assertEquals(2900.0, out.getTotalDistanceMeters());
        assertEquals(1010, out.getTotalElapsedSeconds());
        assertEquals(3, out.getSplits().size());

        KilometerSplitDTO km1 = out.getSplits().get(0);
        assertEquals(1, km1.getKm());
        assertEquals(1000.0, km1.getDistanceMeters());
        assertEquals(300, km1.getElapsedSeconds());
        assertEquals("5'00\"", km1.getPaceFormatted());
        assertEquals(3.33, km1.getAvgSpeed());
        assertTrue(km1.getIsFastest());
        assertFalse(km1.getIsSlowest());

        KilometerSplitDTO km2 = out.getSplits().get(1);
        assertEquals(2, km2.getKm());
        assertEquals(350, km2.getElapsedSeconds());
        assertEquals("5'50\"", km2.getPaceFormatted());
        assertEquals(2.86, km2.getAvgSpeed());
        assertFalse(km2.getIsFastest());
        assertTrue(km2.getIsSlowest());

        KilometerSplitDTO tail = out.getSplits().get(2);
        assertEquals(3, tail.getKm());
        assertEquals(900.0, tail.getDistanceMeters());
        assertEquals(360, tail.getElapsedSeconds());
        assertEquals("6'40\"", tail.getPaceFormatted());
        assertEquals(2.5, tail.getAvgSpeed());
        assertFalse(tail.getIsFastest());
        assertFalse(tail.getIsSlowest());

        assertEquals(1, out.getFastestKm());
        assertEquals(2, out.getSlowestKm());
    }

    /** 3.5km 四段（整公里恰落在点位上）：300/360/330s + 500m 余程 200s；最快第 1 公里、最慢第 2 公里。 */
    @Test
    void buildRecordSplits_multiKm_onBoundaryCrossings_tailAndExtremes() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of(
                pt(0, 0, BASE_TS),
                pt(1, latOffset(1000), BASE_TS + 300_000),
                pt(2, latOffset(2000), BASE_TS + 660_000),
                pt(3, latOffset(3000), BASE_TS + 990_000),
                pt(4, latOffset(3500), BASE_TS + 1_190_000)));
        assertEquals(3500.0, out.getTotalDistanceMeters());
        assertEquals(1190, out.getTotalElapsedSeconds());
        assertEquals(4, out.getSplits().size());
        assertEquals(300, out.getSplits().get(0).getElapsedSeconds());
        assertEquals("5'00\"", out.getSplits().get(0).getPaceFormatted());
        assertEquals(3.33, out.getSplits().get(0).getAvgSpeed());
        assertEquals(360, out.getSplits().get(1).getElapsedSeconds());
        assertEquals("6'00\"", out.getSplits().get(1).getPaceFormatted());
        assertEquals(2.78, out.getSplits().get(1).getAvgSpeed());
        assertEquals(330, out.getSplits().get(2).getElapsedSeconds());
        assertEquals("5'30\"", out.getSplits().get(2).getPaceFormatted());
        assertEquals(3.03, out.getSplits().get(2).getAvgSpeed());
        KilometerSplitDTO tail = out.getSplits().get(3);
        assertEquals(4, tail.getKm());
        assertEquals(500.0, tail.getDistanceMeters());
        assertEquals(200, tail.getElapsedSeconds());
        assertEquals("6'40\"", tail.getPaceFormatted());
        assertEquals(2.5, tail.getAvgSpeed());
        assertFalse(tail.getIsFastest());
        assertFalse(tail.getIsSlowest());
        assertTrue(out.getSplits().get(0).getIsFastest());
        assertTrue(out.getSplits().get(1).getIsSlowest());
        assertEquals(1, out.getFastestKm());
        assertEquals(2, out.getSlowestKm());
    }

    /** 余程 &lt; 10m 不单列：1005m/2010s → 仅 1 条满公里段（2000s），5m 零头并入不列。 */
    @Test
    void buildRecordSplits_tailBelow10m_dropped() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of(
                pt(0, 0, BASE_TS),
                pt(1, latOffset(1005), BASE_TS + 2_010_000)));
        assertEquals(1005.0, out.getTotalDistanceMeters());
        assertEquals(1, out.getSplits().size());
        KilometerSplitDTO km1 = out.getSplits().get(0);
        assertEquals(1, km1.getKm());
        assertEquals(1000.0, km1.getDistanceMeters());
        assertEquals(2000, km1.getElapsedSeconds());
        assertEquals("33'20\"", km1.getPaceFormatted());
        assertEquals(0.5, km1.getAvgSpeed());
        assertEquals(1, out.getFastestKm());
        assertEquals(1, out.getSlowestKm());
    }

    /** 1040m/500s：满公里 481s（8'01"）+ 40m 余程 19s（8'01"）；唯一满公里同领最快与最慢。 */
    @Test
    void buildRecordSplits_singleFullKm_fastestAndSlowestBoth() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of(
                pt(0, 0, BASE_TS),
                pt(1, latOffset(1040), BASE_TS + 500_000)));
        assertEquals(2, out.getSplits().size());
        KilometerSplitDTO km1 = out.getSplits().get(0);
        assertEquals(1000.0, km1.getDistanceMeters());
        assertEquals(481, km1.getElapsedSeconds());
        assertEquals("8'01\"", km1.getPaceFormatted());
        assertEquals(2.08, km1.getAvgSpeed());
        assertTrue(km1.getIsFastest());
        assertTrue(km1.getIsSlowest());
        KilometerSplitDTO tail = out.getSplits().get(1);
        assertEquals(2, tail.getKm());
        assertEquals(40.0, tail.getDistanceMeters());
        assertEquals(19, tail.getElapsedSeconds());
        assertEquals("8'01\"", tail.getPaceFormatted());
        assertEquals(2.08, tail.getAvgSpeed());
        assertFalse(tail.getIsFastest());
        assertFalse(tail.getIsSlowest());
        assertEquals(1, out.getFastestKm());
        assertEquals(1, out.getSlowestKm());
    }

    /** 全程零耗时（1100m 两点同 ts）：满公里耗时 0 → 配速回退 --、均速 0.0；100m 余程同样回退 --。 */
    @Test
    void buildRecordSplits_zeroElapsed_paceFallbackDashes() {
        RecordSplitsDTO out = service.buildRecordSplits(1L, List.of(
                pt(0, 0, BASE_TS),
                pt(1, latOffset(1100), BASE_TS)));
        assertEquals(2, out.getSplits().size());
        KilometerSplitDTO km1 = out.getSplits().get(0);
        assertEquals(0, km1.getElapsedSeconds());
        assertEquals("--", km1.getPaceFormatted());
        assertEquals(0.0, km1.getAvgSpeed());
        assertTrue(km1.getIsFastest());
        assertTrue(km1.getIsSlowest());
        KilometerSplitDTO tail = out.getSplits().get(1);
        assertEquals(100.0, tail.getDistanceMeters());
        assertEquals("--", tail.getPaceFormatted());
        assertEquals(0.0, tail.getAvgSpeed());
        assertEquals(1, out.getFastestKm());
        assertEquals(1, out.getSlowestKm());
    }
}
