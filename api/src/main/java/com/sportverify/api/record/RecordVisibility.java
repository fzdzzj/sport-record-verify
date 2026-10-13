package com.sportverify.api.record;

import lombok.Getter;

/**
 * 记录可见性枚举（记录读取面授权的前提，见 add-record-visibility，TASK-221）。
 *
 * <p>code 与 {@code sport_record.visibility} 列值一致（TINYINT，DEFAULT 0）。落库后的每条记录
 * 携带一个可见性，读取面按「读取者 caller」作逐记录授权（在服务层统一收敛，见
 * {@code SportRecordService.assertCanView} 的门矩阵）：</p>
 *
 * <ul>
 *   <li>{@link #PUBLIC}（0，默认）：任何登录读取者可读 —— 历史行被 DEFAULT 0 回填为公开（向后兼容）；</li>
 *   <li>{@link #FRIENDS}（1）：仅记录归属人与互为好友者（经 user-service {@code areFriends} 对判断）可读；</li>
 *   <li>{@link #PRIVATE}（2）：仅记录归属人可读。</li>
 * </ul>
 *
 * <p>门语义（13 组合，owner×3 放行、stranger×PUBLIC 放行、friend×FRIENDS 放行、
 * stranger×FRIENDS 403、非 owner×PRIVATE 403、caller=null 降级态放行、
 * user-service 软降级 false 时 403）：读取路径 pagePoints / getVerifyResult / 点赞三端点统一收口。
 * 新提交的 visibility 会在服务层 {@code resolveVisibility} 归一（null→PUBLIC，枚举外值拒绝）。</p>
 */
@Getter
public enum RecordVisibility {

    /** 公开（历史默认 & 新记录缺省回退）：任何登录读取者可读 */
    PUBLIC(0),
    /** 好友可见：仅归属人与互为好友者可读 */
    FRIENDS(1),
    /** 仅自己：仅归属人可读 */
    PRIVATE(2);

    private final int code;

    RecordVisibility(int code) {
        this.code = code;
    }

    /**
     * 按 code 解析枚举；未知值或空返回 null（解析为上抛右抛由调用方按场景处理——
     * 提交侧拒绝，读取侧对存量空值按 PUBLIC 兜底）。
     */
    public static RecordVisibility fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RecordVisibility v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }
}
