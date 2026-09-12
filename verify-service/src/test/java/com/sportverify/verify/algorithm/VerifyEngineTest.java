package com.sportverify.verify.algorithm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.verify.algorithm.model.VerdictResult;
import com.sportverify.verify.algorithm.rule.R1SpeedRule;
import com.sportverify.verify.algorithm.rule.R2AccelRule;
import com.sportverify.verify.algorithm.rule.R3StayRule;
import com.sportverify.verify.algorithm.rule.R4DistanceRule;
import com.sportverify.verify.config.VerifyProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验引擎验收用例（tasks.json T1-T5 / 规范「漂移预处理」「规则链判定」「判定聚合」）。
 *
 * <p>T1 真实轨迹 → PASSED；T2 匀速刷里程 → R1 HARD → REJECTED；
 * T3 漂移剔除与 driftRatio 软证据；T4 原地抖动 → R3 HARD → REJECTED；
 * T5 折返刷里程 → R4 SOFT → REJECTED（默认仅 SOFT 拒绝）。
 * 另覆盖：R2 飞点拼接、阈值覆盖（Nacos verify.rules.r1.speed）、证据 JSON 完整性。</p>
 */
class VerifyEngineTest {

    private final VerifyEngine engine = new VerifyEngine(
            new TrackPreprocessor(),
            List.of(new R1SpeedRule(), new R2AccelRule(), new R3StayRule(), new R4DistanceRule()));
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** T1：真实匀速跑步轨迹（约 3 m/s + 微小 GPS 抖动）→ 无命中 → PASSED（score 50） */
    @Test
    void t1_realTrackPassed() {
        List<TrackPointDTO> points = track(0, 0, 300, 9.0, 3.0, 0.5);
        VerdictResult r = engine.verify(points, new VerifyProperties());
        assertEquals(Verdict.PASSED, r.getVerdict());
        assertEquals(50, r.getScore());
        assertTrue(r.getHits().isEmpty(), "真实轨迹不应产生规则命中");
        assertTrue(r.isFinal());
    }

    /** T2：匀速 6 m/s 刷里程 → 滑动窗口均速 >5.5 持续 ≥10 窗口 → R1 HARD → REJECTED（score 70） */
    @Test
    void t2_uniformSpeedRejectedByR1() {
        List<TrackPointDTO> points = track(0, 0, 200, 30.0, 5.0, 0.1); // 30m/5s = 6 m/s
        VerdictResult r = engine.verify(points, new VerifyProperties());
        assertEquals(Verdict.REJECTED, r.getVerdict());
        assertEquals(70, r.getScore()); // 50 + 20×1(HARD)
        assertTrue(r.getHits().stream().anyMatch(
                h -> "R1_SPEED".equals(h.getRule()) && h.getLevel() == RuleLevel.HARD));
    }

    /** T3：漂移点占 20%（≤30%）→ 剔除后按真实轨迹判定，不产生 PREPROCESS_SUSPICIOUS 软证据 */
    @Test
    void t3_driftUnder30Percent_noSoftEvidence() {
        List<TrackPointDTO> base = track(0, 0, 100, 9.0, 3.0, 0.5);
        List<TrackPointDTO> points = injectDrift(base, 25); // 25/125 = 20%
        VerdictResult r = engine.verify(points, new VerifyProperties());
        assertEquals(25, r.getPreprocess().getDriftRemoved());
        assertEquals(0.2, r.getPreprocess().getDriftRatio(), 1e-9);
        assertFalse(r.getPreprocess().isSuspicious());
        assertFalse(r.getHits().stream().anyMatch(h -> "PREPROCESS_SUSPICIOUS".equals(h.getRule())));
        assertEquals(Verdict.PASSED, r.getVerdict());
    }

    /** T3b：漂移占比 >30% → 记软证据 PREPROCESS_SUSPICIOUS，仅 SOFT 默认 REJECTED */
    @Test
    void t3b_highDriftRatio_recordsSoftEvidence() {
        List<TrackPointDTO> base = track(0, 0, 100, 9.0, 3.0, 0.5);
        List<TrackPointDTO> points = injectDrift(base, 65); // 65/165 ≈ 39% > 30%
        VerdictResult r = engine.verify(points, new VerifyProperties());
        assertTrue(r.getPreprocess().isSuspicious());
        assertTrue(r.getHits().stream().anyMatch(
                h -> "PREPROCESS_SUSPICIOUS".equals(h.getRule()) && h.getLevel() == RuleLevel.SOFT));
        assertEquals(Verdict.REJECTED, r.getVerdict());
    }

    /** T4：原地抖动 ≥5min 且停留占比 >40% → R3 HARD → REJECTED（score 70） */
    @Test
    void t4_stayTrackRejectedByR3() {
        List<TrackPointDTO> points = stayThenMoveTrack();
        VerdictResult r = engine.verify(points, new VerifyProperties());
        assertTrue(r.getHits().stream().anyMatch(
                h -> "R3_STAY".equals(h.getRule()) && h.getLevel() == RuleLevel.HARD));
        assertEquals(Verdict.REJECTED, r.getVerdict());
        assertEquals(70, r.getScore());
    }

    /** T5：折返刷里程（累计/直线 >3.0）→ R4 SOFT → REJECTED（score 60） */
    @Test
    void t5_returnTrackRejectedByR4() {
        VerdictResult r = engine.verify(returnTrack(), new VerifyProperties());
        assertTrue(r.getHits().stream().anyMatch(
                h -> "R4_DISTANCE".equals(h.getRule()) && h.getLevel() == RuleLevel.SOFT));
        assertEquals(Verdict.REJECTED, r.getVerdict());
        assertEquals(60, r.getScore()); // 50 + 10×1(SOFT)
    }

    /** R2 场景：飞点拼接（Δv/Δt>3 出现 ≥3 次）→ R2 SOFT → 默认 REJECTED */
    @Test
    void r2_accelSpikesRejected() {
        List<TrackPointDTO> points = new ArrayList<>();
        long base = System.currentTimeMillis() - 10_000L;
        double lat = 0;
        for (int i = 0; i < 10; i++) {
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(i);
            lat += (i + 1) * 4.0 / 111320.0; // 速度依次 4,8,12,16... m/s（dt=1s → a=4 m/s²）
            p.setLat(BigDecimal.valueOf(lat));
            p.setLng(BigDecimal.ZERO);
            p.setTs(base + i * 1000L);
            points.add(p);
        }
        VerdictResult r = engine.verify(points, new VerifyProperties());
        assertTrue(r.getHits().stream().anyMatch(
                h -> "R2_ACCEL".equals(h.getRule()) && h.getLevel() == RuleLevel.SOFT));
        assertEquals(Verdict.REJECTED, r.getVerdict());
    }

    /** 判定聚合：仅 SOFT 命中且配置宽松策略 → PASSED（带可疑命中记录） */
    @Test
    void softOnlyHits_canBeConfiguredToPass() {
        VerifyProperties props = new VerifyProperties();
        props.getPolicy().setSoftOnlyReject(false); // 宽松开关（后续灰度变更的配置项）
        VerdictResult r = engine.verify(returnTrack(), props);
        assertEquals(Verdict.PASSED, r.getVerdict());
        assertFalse(r.getHits().isEmpty(), "宽松策略下仍应记录可疑命中");
    }

    /** 阈值覆盖（规范「覆盖阈值生效」）：verify.rules.r1.speed=6.0 后 5.8 m/s 不再命中 R1 */
    @Test
    void r1ThresholdOverrideTakesEffect() {
        List<TrackPointDTO> points = track(0, 0, 200, 29.0, 5.0, 0.1); // 5.8 m/s
        assertEquals(Verdict.REJECTED, engine.verify(points, new VerifyProperties()).getVerdict());
        VerifyProperties overridden = new VerifyProperties();
        overridden.getRules().getR1().setSpeed(6.0);
        VerdictResult r = engine.verify(points, overridden);
        assertEquals(Verdict.PASSED, r.getVerdict());
        assertFalse(r.getHits().stream().anyMatch(h -> "R1_SPEED".equals(h.getRule())));
    }

    /** 证据 JSON 完整（规范「证据 JSON 完整」）：verdict/score/hits[rule+level+detail]/preprocess */
    @Test
    void evidenceJsonContainsAllSections() throws Exception {
        List<TrackPointDTO> points = track(0, 0, 200, 30.0, 5.0, 0.1);
        VerdictResult r = engine.verify(points, new VerifyProperties());
        String json = EvidenceJsonBuilder.build(r, objectMapper);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("REJECTED", node.get("verdict").asText());
        assertEquals(70, node.get("score").asInt());
        JsonNode hits = node.get("hits");
        assertTrue(hits.isArray() && hits.size() >= 1);
        assertEquals("R1_SPEED", hits.get(0).get("rule").asText());
        assertEquals("HARD", hits.get(0).get("level").asText());
        assertTrue(hits.get(0).hasNonNull("detail"));
        assertTrue(node.get("preprocess").has("pointsTotal"));
        assertTrue(node.get("preprocess").has("driftRemoved"));
        assertTrue(node.get("preprocess").has("driftRatio"));
    }

    // ===== 轨迹构造工具 =====

    /** 生成匀速直线轨迹（向北），stepMeters 为每 dt 秒的位移 */
    private static List<TrackPointDTO> track(double startLat, double startLng, int points,
                                             double stepMeters, double dtSeconds, double jitterMeters) {
        Random rnd = new Random(42);
        long baseTs = System.currentTimeMillis() - points * (long) (dtSeconds * 1000);
        List<TrackPointDTO> list = new ArrayList<>(points);
        double lngPerMeter = 1.0 / (111320.0 * Math.cos(Math.toRadians(startLat)));
        for (int i = 0; i < points; i++) {
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(i);
            double jLat = (rnd.nextDouble() - 0.5) * 2 * jitterMeters / 111320.0;
            double jLng = (rnd.nextDouble() - 0.5) * 2 * jitterMeters * lngPerMeter;
            p.setLat(BigDecimal.valueOf(startLat + i * stepMeters / 111320.0 + jLat));
            p.setLng(BigDecimal.valueOf(startLng + jLng));
            p.setTs(baseTs + i * (long) (dtSeconds * 1000));
            list.add(p);
        }
        return list;
    }

    /** 注入漂移点：位置跳变约 555m（Δt=1s → v≈555 m/s >> V_DRIFT=20），追加到轨迹末尾 */
    private static List<TrackPointDTO> injectDrift(List<TrackPointDTO> base, int driftCount) {
        List<TrackPointDTO> all = new ArrayList<>(base);
        long lastTs = base.get(base.size() - 1).getTs();
        double lastLat = base.get(base.size() - 1).getLat().doubleValue();
        for (int i = 0; i < driftCount; i++) {
            TrackPointDTO d = new TrackPointDTO();
            d.setSeq(base.size() + i);
            d.setLat(BigDecimal.valueOf(lastLat + 0.005));
            d.setLng(base.get(0).getLng());
            d.setTs(lastTs + 1000L);
            all.add(d);
        }
        return all;
    }

    /** 停留 + 移动轨迹：前 420s 原地抖动（位移 <5m），后 180s 以 2 m/s 向北（停留占比 ≈70%） */
    private static List<TrackPointDTO> stayThenMoveTrack() {
        List<TrackPointDTO> points = new ArrayList<>();
        Random rnd = new Random(7);
        long base = System.currentTimeMillis() - 600_000L;
        for (int i = 0; i < 420; i++) { // 停留段：每秒一点，±0.05m 抖动
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(i);
            double j = (rnd.nextDouble() - 0.5) * 0.1 / 111320.0;
            p.setLat(BigDecimal.valueOf(39.9 + j));
            p.setLng(BigDecimal.valueOf(116.4 + j));
            p.setTs(base + i * 1000L);
            points.add(p);
        }
        double lat = 39.9;
        for (int i = 0; i < 180; i++) { // 移动段：2 m/s
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(420 + i);
            lat += 2.0 / 111320.0;
            p.setLat(BigDecimal.valueOf(lat));
            p.setLng(BigDecimal.valueOf(116.4));
            p.setTs(base + (420 + i) * 1000L);
            points.add(p);
        }
        return points;
    }

    /** 折返轨迹：向北 1000m 再折返向南 1000m（累计 2000m，起终点直线≈0） */
    private static List<TrackPointDTO> returnTrack() {
        List<TrackPointDTO> points = new ArrayList<>();
        long base = System.currentTimeMillis() - 400_000L;
        for (int i = 0; i < 200; i++) { // 去程 5 m/s
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(i);
            p.setLat(BigDecimal.valueOf(i * 5.0 / 111320.0));
            p.setLng(BigDecimal.ZERO);
            p.setTs(base + i * 1000L);
            points.add(p);
        }
        for (int i = 0; i < 200; i++) { // 返程
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(200 + i);
            p.setLat(BigDecimal.valueOf((200 - i) * 5.0 / 111320.0));
            p.setLng(BigDecimal.ZERO);
            p.setTs(base + (200 + i) * 1000L);
            points.add(p);
        }
        return points;
    }
}
