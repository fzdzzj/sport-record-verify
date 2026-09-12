package com.sportverify.user.enums;

import lombok.Getter;

/**
 * 好友申请状态机（审批版 §6.1 / 规范差异「申请状态机」）。
 *
 * <p>合法流转：PENDING → ACCEPTED / REJECTED / CANCELLED，仅 PENDING 可流转
 * （流转用乐观语义 {@code UPDATE ... WHERE status=PENDING}，影响 0 行报 5002）。
 * 落库为 TINYINT 码，对外 DTO 下发枚举名（见 FriendRequestDTO.status）。</p>
 */
@Getter
public enum FriendRequestStatus {

    /** 待处理 */
    PENDING(0),
    /** 已同意（同时写入 friendship） */
    ACCEPTED(1),
    /** 已拒绝 */
    REJECTED(2),
    /** 已取消 */
    CANCELLED(3);

    private final int code;

    FriendRequestStatus(int code) {
        this.code = code;
    }

    /**
     * 码 → 枚举（未知码返回 null，避免抛异常）。
     */
    public static FriendRequestStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (FriendRequestStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * 码 → 枚举名（对外下发，与审批版 §4.1 示例 {@code status:"PENDING"} 一致）。
     */
    public static String nameOf(Integer code) {
        FriendRequestStatus status = fromCode(code);
        return status == null ? null : status.name();
    }
}
