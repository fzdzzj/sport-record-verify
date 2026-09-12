package com.sportverify.verify.algorithm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.verify.algorithm.model.PreprocessResult;
import com.sportverify.verify.algorithm.model.VerdictResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 证据 JSON 构建（规范「证据 JSON 完整」）。
 *
 * <p>结构（审批版 §5.2 定稿）：</p>
 * <pre>
 * {
 *   "verdict": "REJECTED",
 *   "score": 82,
 *   "hits": [{"rule": "R1_SPEED", "level": "HARD", "detail": "..."}],
 *   "preprocess": {"pointsTotal": 320, "driftRemoved": 5, "driftRatio": 0.016}
 * }
 * </pre>
 */
public final class EvidenceJsonBuilder {

    private EvidenceJsonBuilder() {
    }

    /**
     * 将判定结果序列化为证据 JSON（写入 verification_result.rule_hits）。
     */
    public static String build(VerdictResult result, ObjectMapper objectMapper) throws JsonProcessingException {
        PreprocessResult pre = result.getPreprocess();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("verdict", result.getVerdict().name());
        evidence.put("score", result.getScore());
        evidence.put("hits", result.getHits());

        Map<String, Object> preprocess = new LinkedHashMap<>();
        preprocess.put("pointsTotal", pre.getPointsTotal());
        preprocess.put("driftRemoved", pre.getDriftRemoved());
        preprocess.put("driftRatio", pre.getDriftRatio());
        evidence.put("preprocess", preprocess);
        return objectMapper.writeValueAsString(evidence);
    }
}
