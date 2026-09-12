package com.sportverify.verify.entity;

import lombok.Getter;

/**
 * 规则版本状态（verify_db.rule_version.status，规范「规则版本化」「全量发布」）。
 *
 * <p>生命周期：GRAY（灰度采样中）→ ACTIVE（全量基线）/ RETIRED（退役，终态）。
 * 状态迁移由 UPDATE ... WHERE 乐观条件防并发（见 RuleVersionMapper），
 * 同一时刻至多一个 ACTIVE——全量发布时旧 ACTIVE/遗留 GRAY 一并置 RETIRED，
 * 保证「基线」指向唯一版本，避免采样路由出现二义性。</p>
 */
@Getter
public enum RuleVersionStatus {

    /** 灰度中：按 gray_ratio 对 userId%100 命中的用户生效 */
    GRAY(0),
    /** 全量生效：作为基线规则集对所有用户生效 */
    ACTIVE(1),
    /** 已退役（终态）：不再被采样，也不再作为基线 */
    RETIRED(2);

    private final int code;

    RuleVersionStatus(int code) {
        this.code = code;
    }
}
