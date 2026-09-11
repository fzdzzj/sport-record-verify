package com.sportverify.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应包装。
 *
 * <p>所有 JSON 接口统一返回 {@code {code, message, data}} 结构（审批版 §四 通用约定），
 * 成功时 code=0，失败时返回结构化错误码（见 {@link ResultCode}）。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 错误码：0 成功，非 0 见 {@link ResultCode} */
    private int code;

    /** 可读信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（无数据体） */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** 成功（携带数据体） */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 按错误码枚举构造失败结果 */
    public static <T> Result<T> failure(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /** 自定义错误码与信息（兜底场景） */
    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null);
    }
}
