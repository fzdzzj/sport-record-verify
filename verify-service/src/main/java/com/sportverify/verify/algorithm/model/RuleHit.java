package com.sportverify.verify.algorithm.model;

import com.sportverify.verify.algorithm.RuleLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则命中证据（规范「规则链判定」「证据 JSON 完整」）。
 *
 * <p>序列化进 verification_result.rule_hits 的 hits 数组，
 * 结构：{rule, level, detail}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleHit {

    /** 规则编码：R1_SPEED / R2_ACCEL / R3_STAY / R4_DISTANCE / R5_OFFROAD / PREPROCESS_SUSPICIOUS */
    private String rule;

    /** 级别：HARD / SOFT */
    private RuleLevel level;

    /** 证据明细（窗口均值、起止点序号、占比等） */
    private String detail;
}
