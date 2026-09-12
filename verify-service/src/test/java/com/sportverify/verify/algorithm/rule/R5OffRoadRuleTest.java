package com.sportverify.verify.algorithm.rule;

import com.sportverify.api.mapmatch.MapMatchApi;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.TrackPreprocessor;
import com.sportverify.verify.algorithm.VerifyEngine;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.algorithm.model.VerdictResult;
import com.sportverify.verify.config.VerifyProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 离路规则单测（MapMatchApi 手写桩不连网，规范场景「悬浮轨迹命中 R5 /
 * 真实轨迹不命中 / 服务不可用降级 / R5 软硬证据参与聚合」）。
 *
 * <p>引擎级用例直接组装 VerifyEngine + R5，验证 R5 证据并入 hits 后
 * 走既有「HARD 即拒 / SOFT 计分」聚合（规范「判定聚合兼容」）。</p>
 */
class R5OffRoadRuleTest {

    /** 桩：按预设 offRoadRatio 应答，或抛异常模拟 mapmatch 不可用 */
    private static MapMatchApi stubApi(double offRoadRatio, RuntimeException failure) {
        return new MapMatchApi() {
            @Override
            public com.sportverify.common.result.Result<MapMatchResultDTO> match(MapMatchRequestDTO request) {
                if (failure != null) {
                    throw failure;
                }
                int n = request.getPoints().size();
                return com.sportverify.common.result.Result.success(new MapMatchResultDTO(
                        1 - offRoadRatio, offRoadRatio,
                        offRoadRatio * 150, offRoadRatio * 200, n, n));
            }
        };
    }

    /** 造 n 个间隔 1s 的有效点：点距 0.00005°≈5.6m/s（低于漂移阈值 20m/s，预处理全部保留） */
    private static List<TrackPointDTO> trackPoints(int n) {
        List<TrackPointDTO> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TrackPointDTO p = new TrackPointDTO();
            p.setSeq(i);
            p.setLat(java.math.BigDecimal.valueOf(31.2300 + i * 0.00005));
            p.setLng(java.math.BigDecimal.valueOf(121.4737));
            p.setTs(1_700_000_000_000L + i * 1000L);
            out.add(p);
        }
        return out;
    }

    private static VerifyProperties.Rules rules() {
        return new VerifyProperties().getRules();
    }

    @Test
    void 点数不足minPoints_跳过R5() {
        R5OffRoadRule rule = new R5OffRoadRule(stubApi(0.9, null));
        RuleHit hit = rule.evaluate(toEnginePoints(trackPoints(4)), rules());
        assertThat(hit).isNull();
    }

    @Test
    void 真实轨迹_低离路比例_不命中() {
        R5OffRoadRule rule = new R5OffRoadRule(stubApi(0.05, null));
        assertThat(rule.evaluate(toEnginePoints(trackPoints(20)), rules())).isNull();
    }

    @Test
    void 中度离路_超SOFT阈值_软证据() {
        R5OffRoadRule rule = new R5OffRoadRule(stubApi(0.6, null));
        RuleHit hit = rule.evaluate(toEnginePoints(trackPoints(20)), rules());
        assertThat(hit).isNotNull();
        assertThat(hit.getRule()).isEqualTo("R5_OFFROAD");
        assertThat(hit.getLevel()).isEqualTo(RuleLevel.SOFT);
        assertThat(hit.getDetail()).contains("0.60");
    }

    @Test
    void 极端离路_超HARD阈值_升级硬证据() {
        R5OffRoadRule rule = new R5OffRoadRule(stubApi(0.85, null));
        RuleHit hit = rule.evaluate(toEnginePoints(trackPoints(20)), rules());
        assertThat(hit).isNotNull();
        assertThat(hit.getLevel()).isEqualTo(RuleLevel.HARD);
    }

    @Test
    void mapmatch不可用_降级不命中_不抛异常() {
        R5OffRoadRule rule = new R5OffRoadRule(stubApi(0, new RuntimeException("匹配服务熔断降级")));
        assertThat(rule.evaluate(toEnginePoints(trackPoints(20)), rules())).isNull();
    }

    @Test
    void 引擎聚合_R5软证据参与SOFT计分_softOnly默认拒绝() {
        R5OffRoadRule r5 = new R5OffRoadRule(stubApi(0.6, null));
        VerifyEngine engine = new VerifyEngine(new TrackPreprocessor(), List.of(r5));
        VerdictResult result = engine.verify(trackPoints(20), new VerifyProperties());
        assertThat(result.getHits()).extracting(RuleHit::getRule).containsExactly("R5_OFFROAD");
        assertThat(result.getVerdict()).isEqualTo(com.sportverify.api.verify.Verdict.REJECTED); // soft-only-reject 默认 true
        assertThat(result.getScore()).isEqualTo(60); // 50 + 10×1 个 SOFT
    }

    @Test
    void 引擎聚合_R5硬证据即拒() {
        R5OffRoadRule r5 = new R5OffRoadRule(stubApi(0.9, null));
        VerifyEngine engine = new VerifyEngine(new TrackPreprocessor(), List.of(r5));
        VerdictResult result = engine.verify(trackPoints(20), new VerifyProperties());
        assertThat(result.getVerdict()).isEqualTo(com.sportverify.api.verify.Verdict.REJECTED);
        assertThat(result.getScore()).isEqualTo(70); // 50 + 20×1 个 HARD
    }

    /** DTO 轨迹 → 引擎内部点模型（走真实预处理，speed/dt 逐点计算） */
    private static List<com.sportverify.verify.algorithm.model.Point> toEnginePoints(List<TrackPointDTO> dto) {
        return new TrackPreprocessor().preprocess(dto, 20).getValidPoints();
    }
}
