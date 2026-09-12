package com.sportverify.api.verify;

import lombok.Getter;

/**
 * 校验判定结果（verification_result.verdict，规范「判定聚合」）。
 *
 * <p>与 record 状态机解耦：verdict 只表达「校验结论」，record 状态由乐观锁回调迁移。</p>
 */
@Getter
public enum Verdict {

    /** 校验中（占位，判定未完成） */
    VERIFYING(0),
    /** 通过（无规则命中，或仅 SOFT 命中且配置宽松策略） */
    PASSED(1),
    /** 拒绝（命中 HARD，或仅 SOFT 命中且默认拒绝策略） */
    REJECTED(2);

    private final int code;

    Verdict(int code) {
        this.code = code;
    }

    /** 是否已终判（VERIFYING 之外均为终判，可安全返回/缓存） */
    public boolean isFinal() {
        return this != VERIFYING;
    }

    /** 按存储码还原枚举 */
    public static Verdict fromCode(int code) {
        for (Verdict v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知判定码：" + code);
    }
}
