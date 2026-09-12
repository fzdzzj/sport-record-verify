package com.sportverify.common.result;

import lombok.Getter;

/**
 * 统一错误码枚举（对应审批版 §4.8 错误码表）。
 *
 * <p>分段约定：</p>
 * <ul>
 *   <li>0：成功</li>
 *   <li>1xx：认证/授权（token、越权）</li>
 *   <li>2xx：用户域（注册、登录）</li>
 *   <li>3xx：记录域（不存在、无权、状态、幂等）</li>
 *   <li>4xx：校验服务降级</li>
 *   <li>5xx：好友域</li>
 *   <li>6xx：点赞域</li>
 *   <li>9999：系统兜底错误</li>
 * </ul>
 */
@Getter
public enum ResultCode {

    /** 成功 */
    SUCCESS(0, "success"),

    // ===== 1xx 认证/授权 =====
    /** token 无效或过期 */
    UNAUTHORIZED(1001, "token 无效或过期"),
    /** 无权限（越权访问他人资源） */
    FORBIDDEN(1002, "无权限访问"),

    // ===== 2xx 用户域 =====
    /** 手机号已注册 */
    PHONE_ALREADY_REGISTERED(2001, "手机号已注册"),
    /** 用户不存在 */
    USER_NOT_FOUND(2002, "用户不存在"),

    // ===== 3xx 记录域 =====
    /** 记录不存在 */
    RECORD_NOT_FOUND(3001, "记录不存在"),
    /** 无权访问该记录 */
    RECORD_ACCESS_DENIED(3002, "无权访问该记录"),
    /** 状态不允许该操作（乐观锁冲突） */
    RECORD_STATUS_INVALID(3003, "状态不允许该操作"),
    /** 幂等冲突：重复提交，返回原结果 */
    IDEMPOTENT_CONFLICT(3004, "重复提交，返回原结果"),
    /** 申诉不存在 */
    APPEAL_NOT_FOUND(3005, "申诉不存在"),
    /** 申诉状态不允许该操作（非 PENDING 终判冲突） */
    APPEAL_STATUS_INVALID(3006, "申诉状态不允许该操作"),

    // ===== 4xx 校验服务 =====
    /** 校验服务不可用（已降级转人工） */
    VERIFY_SERVICE_UNAVAILABLE(4001, "校验服务暂不可用，请稍后重试"),
    /** 规则版本不存在 */
    RULE_VERSION_NOT_FOUND(4002, "规则版本不存在"),
    /** 规则版本状态不允许该操作（仅 GRAY 可调比例/全量、并发状态迁移冲突） */
    RULE_VERSION_STATUS_INVALID(4003, "规则版本状态不允许该操作"),
    /** 规则版本冲突（版本号重复 / 已有灰度版本在采样 / 灰度比例越界） */
    RULE_VERSION_CONFLICT(4004, "规则版本冲突"),

    // ===== 5xx 好友域 =====
    /** 好友重复申请或已存在关系 */
    FRIEND_REQUEST_EXISTS(5001, "重复申请或已存在关系"),
    /** 关系不存在 */
    FRIEND_RELATION_NOT_FOUND(5002, "关系不存在"),

    // ===== 6xx 点赞域 =====
    /** 记录未通过校验，不可点赞 */
    RECORD_NOT_PASSED(6001, "记录未通过校验，不可点赞"),

    // ===== 系统兜底 =====
    /** 未预期异常（对外不泄漏堆栈细节） */
    SYSTEM_ERROR(9999, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
