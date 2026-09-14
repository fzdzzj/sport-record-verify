package com.sportverify.user.auth.dto;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证请求 DTO 入参校验单测（DTO 注解契约）。
 *
 * <p>纯 jakarta.validation.Validator（hibernate-validator 实现）驱动，不依赖 Spring 上下文；
 * 与仓库既有纯 Mockito 单测风格一致。锁死契约：手机号格式 / 密码长度违规均产生约束违规
 * （控制器层 @Valid 失败即映射 400 且错误信息含字段名，见 GlobalExceptionHandler）。</p>
 */
class AuthRequestDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static LoginRequestDTO validLogin() {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setPhone("13800138000");
        dto.setPassword("secret123");
        return dto;
    }

    @Test
    void validLogin_passes() {
        assertTrue(validator.validate(validLogin()).isEmpty(), "合法登录请求不应产生约束违规");
    }

    @Test
    void phone_tooLong_violates() {
        LoginRequestDTO dto = validLogin();
        dto.setPhone("138001380001"); // 12 位，超 11 位

        Set<ConstraintViolation<LoginRequestDTO>> violations = validator.validate(dto);

        assertEquals(1, violations.size());
        ConstraintViolation<LoginRequestDTO> violation = violations.iterator().next();
        assertEquals("phone", violation.getPropertyPath().toString());
        assertTrue(violation.getMessage().contains("手机号"));
    }

    @Test
    void phone_malformed_violates() {
        LoginRequestDTO dto = validLogin();
        dto.setPhone("12345678901"); // 不以 1[3-9] 开头

        Set<ConstraintViolation<LoginRequestDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("phone")
                        && v.getMessage().contains("手机号格式不正确")));
    }

    @Test
    void password_tooShort_violates() {
        LoginRequestDTO dto = validLogin();
        dto.setPassword("12345"); // 5 位，低于下限 6

        Set<ConstraintViolation<LoginRequestDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("password")
                        && v.getMessage().contains("密码长度")));
    }

    @Test
    void blankPhone_violates() {
        LoginRequestDTO dto = validLogin();
        dto.setPhone("   ");

        Set<ConstraintViolation<LoginRequestDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("phone")
                        && v.getMessage().contains("手机号不能为空")));
    }

    @Test
    void register_nickname_tooLong_violates() {
        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setPhone("13800138000");
        dto.setPassword("secret123");
        dto.setNickname("n".repeat(31));

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("nickname")
                        && v.getMessage().contains("昵称")));
    }
}
