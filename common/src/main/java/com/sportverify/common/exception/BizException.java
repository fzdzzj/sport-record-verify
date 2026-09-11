package com.sportverify.common.exception;

import com.sportverify.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>业务层抛出时携带 {@link ResultCode}，由全局异常处理器统一转为响应结构，
 * 避免在 Controller 里散落 try-catch。</p>
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final int code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BizException(ResultCode resultCode, String detail) {
        super(detail);
        this.code = resultCode.getCode();
    }
}
