package com.sportverify.verify.algorithm;

import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.verify.algorithm.model.PreprocessResult;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.algorithm.model.VerdictResult;
import com.sportverify.verify.algorithm.rule.Rule;
import com.sportverify.verify.config.VerifyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验引擎（审批版 §5.2 / 规范「规则链判定」「判定聚合」）。
 *
 * <p>流水线：预处理漂移过滤 → 规则链 R1-R5 按序执行（收集全部命中）→ 判定聚合 + 评分。
 * R1-R4 为本地计算，R5 远程调 mapmatch-service 做路网匹配（不可用时降级不命中，见 ADR-0006）。</p>
 *
 * <p>阈值按运动类型分维度（规范「按类型判定」，见 ADR-0004 §5）：判定前按记录的
 * sportType 解析对应 R1-R4 阈值集（缺省/未知回退 RUNNING，行为与历史一致）；
 * R5 与类型弱相关维持通用。灰度路由（userId%100 选版本）与本维度正交——先取版本，版本内再按类型取阈值。</p>
 *
 * <p>判定聚合（规范「判定聚合」）：</p>
 * <ul>
 *   <li>命中任意 HARD → REJECTED；</li>
 *   <li>仅命中 SOFT → 默认 REJECTED（verify.policy.soft-only-reject 可配为 PASSED）；</li>
 *   <li>无命中 → PASSED；</li>
 *   <li>score = 50 + 20×HARD数 + 10×SOFT数（封顶 100）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyEngine {

    /** 预处理漂移过滤 */
    private final TrackPreprocessor preprocessor;

    /** 规则链（R1-R5，@Order 保证按序执行；PREPROCESS_SUSPICIOUS 由引擎先于规则链汇总） */
    private final List<Rule> rules;

    /**
     * 执行完整校验（运动类型缺省按 RUNNING，保持旧调用方/测试兼容）。
     *
     * @param dtoPoints 原始轨迹点
     * @param props     阈值配置（verify.rules.*，Nacos 可配；每次实时读取）
     */
    public VerdictResult verify(List<TrackPointDTO> dtoPoints, VerifyProperties props) {
        return verify(dtoPoints, props, SportType.RUNNING);
    }

    /**
     * 执行完整校验（按运动类型取阈值）。
     *
     * @param dtoPoints 原始轨迹点
     * @param props     阈值配置（verify.rules.*，Nacos 可配；每次实时读取）
     * @param sportType 运动类型（决定 R1-R4 阈值集；null/未知由 resolveThreshold 保守回退 RUNNING）
     */
    public VerdictResult verify(List<TrackPointDTO> dtoPoints, VerifyProperties props, SportType sportType) {
        // Step1 预处理：漂移过滤（原始数组保留待审计）
        PreprocessResult pre = preprocessor.preprocess(dtoPoints, props.getRules().getVDrift());

        // Step2 按运动类型解析 R1-R4 阈值集（缺省回退 RUNNING，见 ADR-0004 §5）
        VerifyProperties.Rules.RuleThreshold threshold = props.getRules().threshold(sportType);

        // Step3 规则链：先汇总预处理软证据，再按序执行 R1-R4，收集全部命中
        List<RuleHit> hits = new ArrayList<>();
        if (pre.isSuspicious()) {
            // 规范场景「高漂移比例记软证据」：driftRatio > 30% 记 PREPROCESS_SUSPICIOUS
            hits.add(new RuleHit("PREPROCESS_SUSPICIOUS", RuleLevel.SOFT,
                    String.format("drift ratio %.1f%% > 30%%", pre.getDriftRatio() * 100)));
        }
        for (Rule rule : rules) {
            RuleHit hit = rule.evaluate(pre.getValidPoints(), props.getRules(), threshold);
            if (hit != null) {
                hits.add(hit);
            }
        }

        // Step4 判定聚合 + 评分（规范「判定聚合」）
        long hardCount = hits.stream().filter(h -> h.getLevel() == RuleLevel.HARD).count();
        long softCount = hits.stream().filter(h -> h.getLevel() == RuleLevel.SOFT).count();
        Verdict verdict;
        if (hardCount > 0) {
            verdict = Verdict.REJECTED;                                  // 命中 HARD → 拒绝
        } else if (softCount > 0) {
            verdict = props.getPolicy().isSoftOnlyReject()
                    ? Verdict.REJECTED                                   // 仅 SOFT → 默认拒绝
                    : Verdict.PASSED;                                    // 宽松策略 → 通过（带可疑标记）
        } else {
            verdict = Verdict.PASSED;                                    // 无命中 → 通过
        }
        int score = Math.min(100, 50 + (int) (20 * hardCount + 10 * softCount));

        VerdictResult result = VerdictResult.builder()
                .verdict(verdict)
                .score(score)
                .hits(hits)
                .preprocess(pre)
                .build();
        log.info("判定完成：sportType={}, verdict={}, score={}, hits={}",
                sportType == null ? "RUNNING(缺省)" : sportType.name(), verdict, score, hits.size());
        return result;
    }
}
