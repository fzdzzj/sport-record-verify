package com.sportverify.verify.algorithm.model;

import com.sportverify.api.verify.Verdict;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 判定结果（规范「判定聚合」）。
 *
 * <p>verdict 见 {@link Verdict}；score = 50 + 20×HARD数 + 10×SOFT数（0-100）；
 * hits 为全部命中规则证据；preprocess 为预处理统计。</p>
 */
@Data
@Builder
public class VerdictResult {

    /** 记录ID（回填） */
    private Long recordId;

    /** 判定结论 */
    private Verdict verdict;

    /** 综合得分（0-100） */
    private int score;

    /** 命中规则证据列表（含 PREPROCESS_SUSPICIOUS 软证据） */
    private List<RuleHit> hits;

    /** 预处理统计 */
    private PreprocessResult preprocess;

    /** 是否已终判（VERIFYING 之外均可安全返回/缓存） */
    public boolean isFinal() {
        return verdict != null && verdict.isFinal();
    }
}
