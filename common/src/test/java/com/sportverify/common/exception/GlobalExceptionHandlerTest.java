package com.sportverify.common.exception;

import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlobalExceptionHandler 单元测试（HTTP 错误契约）。
 *
 * <p>直接构造各类异常调用处理器方法，断言返回的 {@link Result} 结构（code/message）
 * 与 @ResponseStatus 映射。畸形 JSON / 参数类型不匹配 / 缺参此前会落入 Exception 兜底
 * 返回 500，本测试锁死它们统一映射 400。</p>
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    // ==================== @Valid 校验失败 → 400 + 字段名 ====================

    @Test
    void methodArgumentNotValid_returns400WithFieldName() {
        MethodParameter parameter = parameter("handle", Long.class);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "dto");
        binding.addError(new FieldError("dto", "phone", "手机号不能为空"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

        Result<Void> result = handler.handleMethodArgumentNotValid(ex);

        assertEquals(400, result.getCode());
        assertEquals("phone 手机号不能为空", result.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, responseStatus("handleMethodArgumentNotValid",
                MethodArgumentNotValidException.class).value());
    }

    // ==================== 畸形 JSON → 400（不再 500） ====================

    @Test
    void unreadableBody_returns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error: unterminated");

        Result<Void> result = handler.handleHttpMessageNotReadable(ex);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("请求体格式错误"));
        assertEquals(HttpStatus.BAD_REQUEST, responseStatus("handleHttpMessageNotReadable",
                HttpMessageNotReadableException.class).value());
    }

    // ==================== 路径/查询参数类型不匹配 → 400 ====================

    @Test
    void typeMismatch_returns400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", parameter("handle", Long.class), null);

        Result<Void> result = handler.handleTypeMismatch(ex);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("id"));
        assertEquals(HttpStatus.BAD_REQUEST, responseStatus("handleTypeMismatch",
                MethodArgumentTypeMismatchException.class).value());
    }

    // ==================== 缺必填查询参数 → 400 ====================

    @Test
    void missingParam_returns400() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("userId", "Long");

        Result<Void> result = handler.handleMissingParam(ex);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("userId"));
        assertEquals(HttpStatus.BAD_REQUEST, responseStatus("handleMissingParam",
                MissingServletRequestParameterException.class).value());
    }

    // ==================== 404 / 405 ====================

    @Test
    void noHandler_returns404() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/api/unknown", null);

        Result<Void> result = handler.handleNotFound(ex);

        assertEquals(404, result.getCode());
        assertEquals(HttpStatus.NOT_FOUND, responseStatus("handleNotFound",
                NoHandlerFoundException.class).value());
    }

    @Test
    void methodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("PUT");

        Result<Void> result = handler.handleMethodNotSupported(ex);

        assertEquals(405, result.getCode());
        assertTrue(result.getMessage().contains("PUT"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, responseStatus("handleMethodNotSupported",
                HttpRequestMethodNotSupportedException.class).value());
    }

    // ==================== 既有行为不回归 ====================

    @Test
    void bizException_preservesCode() {
        BizException ex = new BizException(ResultCode.RECORD_NOT_FOUND);

        Result<Void> result = handler.handleBizException(ex);

        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), result.getCode());
        assertEquals(ResultCode.RECORD_NOT_FOUND.getMessage(), result.getMessage());
    }

    @Test
    void illegalArgument_returns400() {
        Result<Void> result = handler.handleIllegalArgument(new IllegalArgumentException("非法参数"));

        assertEquals(400, result.getCode());
    }

    @Test
    void genericException_returnsSystemError() {
        Result<Void> result = handler.handleException(new RuntimeException("boom"));

        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), result.getCode());
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unused")
    private static final class Sample {
        public void handle(Long id) {
        }
    }

    private static MethodParameter parameter(String methodName, Class<?> paramType) {
        try {
            return new MethodParameter(Sample.class.getDeclaredMethod(methodName, paramType), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatus responseStatus(String methodName, Class<?>... paramTypes) {
        try {
            Method m = GlobalExceptionHandler.class.getMethod(methodName, paramTypes);
            ResponseStatus annotation = m.getAnnotation(ResponseStatus.class);
            if (annotation == null) {
                throw new IllegalStateException(methodName + " 缺少 @ResponseStatus");
            }
            return annotation;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
