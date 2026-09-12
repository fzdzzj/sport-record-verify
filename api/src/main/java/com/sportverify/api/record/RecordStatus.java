package com.sportverify.api.record;

import lombok.Getter;

/**
 * 运动记录审核状态（状态机见审批版 §5.1 / 规范「校验状态机」）。
 *
 * <p>主链路：SUBMITTED → VERIFYING → PASSED / REJECTED；
 * 申诉链路：REJECTED → APPEALING → RE_PASSED / RE_CONFIRMED；
 * 降级链路：VERIFYING → MANUAL_REVIEW（校验依赖故障时的熔断降级终态，
 * 压测变更 spec「熔断降级转人工」新增，属人工介入终态，不自动迁移）。
 * 所有迁移必须走乐观锁 {@code UPDATE ... WHERE id=? AND status=? AND version=?}，
 * 影响行数为 0 时由调用方报 3003 或重试。</p>
 */
@Getter
public enum RecordStatus {

    /** 已提交（记录落库的初始态，等待进入校验） */
    SUBMITTED(0),
    /** 校验中（已发 SUBMITTED 事件，等待 verify-service 判定回调） */
    VERIFYING(1),
    /** 校验通过 */
    PASSED(2),
    /** 校验拒绝（可发起申诉） */
    REJECTED(3),
    /** 申诉中（申诉单已建，等待管理员终判） */
    APPEALING(4),
    /** 终判改判通过 */
    RE_PASSED(5),
    /** 终判维持拒绝 */
    RE_CONFIRMED(6),
    /**
     * 转人工（校验降级终态，压测变更新增）：
     * MQ 不可用降级 Feign 直调仍失败（verify 熔断）时，记录不再无限 VERIFYING，
     * 转入人工审核队列；verify 恢复后可由补偿任务重放补判。
     */
    MANUAL_REVIEW(7);

    private final int code;

    RecordStatus(int code) {
        this.code = code;
    }
}
