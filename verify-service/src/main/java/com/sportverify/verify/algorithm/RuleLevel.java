package com.sportverify.verify.algorithm;

/**
 * 规则命中级别（审批版 §5.2 / 规范「规则链判定」）。
 *
 * <p>HARD：命中任意一条即 REJECTED（R1 速度、R3 停留）；
 * SOFT：默认同样 REJECTED，可配置为 PASSED 带可疑标记（R2 加速度、R4 距离、漂移软证据）。</p>
 */
public enum RuleLevel {
    HARD,
    SOFT
}
