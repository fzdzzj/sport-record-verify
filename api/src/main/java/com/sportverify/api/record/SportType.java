package com.sportverify.api.record;

import lombok.Getter;

/**
 * 运动类型枚举（记录携带类型，阈值按类型分维度，见 ADR-0004 §5）。
 *
 * <p>code 与 sport_record.sport_type 列值一致（TINYINT）。校验引擎按此类型取对应
 * 阈值集；缺省回退 RUNNING（历史默认，保持旧行为），未知 code 由调用方按场景处理——
 * 提交侧拒绝（非法参数），判定侧保守回退默认。</p>
 */
@Getter
public enum SportType {

    /** 跑步（历史默认：缺省/旧数据回退此类型，阈值沿用 5.5 m/s） */
    RUNNING(1),
    /** 骑行（速度上限高于跑步，正常 6~8 m/s 不触发 R1 误判） */
    CYCLING(2),
    /** 步行（速度上限低于跑步） */
    WALKING(3);

    private final int code;

    SportType(int code) {
        this.code = code;
    }

    /**
     * 按 code 解析枚举；未知值或空返回 null（不在此处抛异常——
     * 提交接口拒绝、校验引擎保守回退，语义由调用方决定）。
     */
    public static SportType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (SportType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
