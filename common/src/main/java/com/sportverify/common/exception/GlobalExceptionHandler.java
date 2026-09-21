package com.sportverify.common.exception;

import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器。
 *
 * <p>统一拦截六类异常：</p>
 * <ul>
 *   <li>{@link BizException}：业务异常 → 返回对应错误码与可读信息；</li>
 *   <li>参数校验异常（@Valid + @RequestBody / @RequestParam）→ 400 + 校验提示；</li>
 *   <li>HTTP 契约异常（畸形 JSON / 类型不匹配 / 缺参 / 404 / 405）→ 400/404/405 + 结构化错误体；</li>
 *   <li>数据库异常（唯一键冲突 / 事务系统异常）→ 409/500 + 友好提示；</li>
 *   <li>非法参数（业务侧主动校验失败）→ 400；</li>
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
     * 参数校验异常（@Valid + @RequestBody / @RequestParam）：400 + 首个字段校验提示。
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
     * 请求体不可读（畸形 JSON / 类型不匹配）：原会落入 Exception 兜底返回 500，
     * 属调用方入参问题，统一映射 400 + 结构化错误体。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.failure(400, "请求体格式错误或 JSON 解析失败");
    }

    /**
     * 路径/查询参数类型不匹配（如 {@code /records/abc/verify-result}，id 期望 Long）→ 400。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配：name={}, value={}", e.getName(), e.getValue());
        return Result.failure(400, "参数类型不匹配：" + e.getName());
    }

    /**
     * 缺少必填查询参数（@RequestParam 未提供且无默认值）→ 400。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数：{}", e.getParameterName());
        return Result.failure(400, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 接口不存在 → 404。
     *
     * <p>注意：DispatcherServlet 仅在开启
     * {@code spring.mvc.throw-exception-if-no-handler-found=true} 时抛出本异常；
     * 当前未开启，未知路径仍走容器默认 404。保留处理器使契约在开启该开关后即刻生效。</p>
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoHandlerFoundException e) {
        log.warn("接口不存在：{} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.failure(404, "接口不存在");
    }

    /**
     * 请求方法不支持（路径存在但方法不符）→ 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{}", e.getMethod());
        return Result.failure(405, "请求方法不支持：" + e.getMethod());
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
     * 主键冲突异常（唯一键重复）→ 409。
     *
     * <p>常见于插入重复数据（如重复提交相同记录），返回友好提示而非堆栈。</p>
     */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("主键冲突：{}", e.getMessage());
        return Result.failure(409, "数据已存在，请勿重复提交");
    }

    /**
     * 事务系统异常 → 500。
     *
     * <p>包括连接超时、死锁检测、事务超时等数据库层事务问题，返回通用错误码并记录完整堆栈。</p>
     */
    @ExceptionHandler(TransactionSystemException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleTransactionSystem(TransactionSystemException e) {
        log.error("事务系统异常", e);
        return Result.failure(ResultCode.SYSTEM_ERROR);
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
