package com.sportverify.common.exception;

import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>统一拦截三类异常：</p>
 * <ul>
 *   <li>{@link BizException}：业务异常 → 返回对应错误码与可读信息；</li>
 *   <li>参数校验异常 → 返回 400 + 校验提示；</li>
 *   <li>未预期异常 → 记日志（含堆栈），对外只返回 9999 系统错误，不泄漏内部细节。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：错误码与提示由业务侧指定。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.failure(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常（@Valid + @RequestBody / @RequestParam）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败：{}", message);
        return Result.failure(400, message);
    }

    /**
     * 非法参数（业务侧主动校验失败，如好友自申请、必填缺失）：
     * 返回 400 + 校验提示，与参数校验异常口径一致。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数：{}", e.getMessage());
        return Result.failure(400, e.getMessage());
    }

    /**
     * 兜底异常：记录完整堆栈，对外仅返回通用错误码。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.failure(ResultCode.SYSTEM_ERROR);
    }
}
