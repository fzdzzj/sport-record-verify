package com.sportverify.api.verify;

import lombok.Getter;

/**
 * 申诉单状态（verify_db.appeal.status，规范「终判改判/终判维持拒绝」）。
 *
 * <p>仅 PENDING 可被管理员终判流转（乐观锁 WHERE status=PENDING），终判结果与
 * record 的 RE_PASSED / RE_CONFIRMED 一一对应。</p>
 */
@Getter
public enum AppealStatus {

    /** 待复核 */
    PENDING(0),
    /** 终判改判通过（对应 record.RE_PASSED） */
    RE_PASSED(1),
    /** 终判维持拒绝（对应 record.RE_CONFIRMED） */
    RE_CONFIRMED(2);

    private final int code;

    AppealStatus(int code) {
        this.code = code;
    }
}
