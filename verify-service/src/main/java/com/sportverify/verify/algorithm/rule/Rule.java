package com.sportverify.verify.algorithm.rule;

import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;

import java.util.List;

/**
 * 校验规则接口（规则链 R1-R5，规范「规则链判定」；R5 为首个远程规则，见 ADR-0006）。
 *
 * <p>每命中一条规则返回一个 {@link RuleHit}（含级别与证据明细），
 * 规则链收集全部命中后进入判定聚合；阈值来自 {@code verify.rules.*}（Nacos 可配）。</p>
 *
 * <p>阈值分类型维度（见 ADR-0004 §5）：R1-R4 使用引擎按 sportType 解析后的
 * {@link VerifyProperties.Rules.RuleThreshold}；R5 与运动类型弱相关维持通用，
 * 使用全局 {@link VerifyProperties.Rules}（含 R5 配置）。</p>
 */
public interface Rule {

    /** 规则编码（写入 rule_hits JSON）：R1_SPEED / R2_ACCEL / R3_STAY / R4_DISTANCE / R5_OFFROAD */
    String code();

    /** 命中级别：HARD（R1/R3，R5 极端偏离动态升级）/ SOFT（R2/R4/R5 默认） */
    RuleLevel level();

    /**
     * 对有效轨迹点集执行判定。
     *
     * @param points    预处理后的有效点（speed/dtSeconds 已计算）
     * @param rules     全局阈值配置（R5 等通用规则使用；R1-R4 请改用 typeThreshold）
     * @param typeThreshold 按运动类型解析后的 R1-R4 阈值集（分类型维度，见 ADR-0004 §5）
     * @return 命中证据；未命中返回 null
     */
    RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules,
                     VerifyProperties.Rules.RuleThreshold typeThreshold);
}
