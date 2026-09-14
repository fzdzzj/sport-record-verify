package com.sportverify.record.validation;

import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RecordSubmitDTO 入参校验单测（DTO 注解契约）。
 *
 * <p>纯 jakarta.validation.Validator（hibernate-validator 实现）驱动，不依赖 Spring 上下文；
 * 与仓库既有纯 Mockito 单测风格一致。锁死三条契约：非法 sportType、空轨迹点、
 * requestId 超长均产生约束违规（控制器层 @Valid 失败即映射 400，见 GlobalExceptionHandler）。</p>
 */
class RecordSubmitDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static RecordSubmitDTO validSubmit() {
        RecordSubmitDTO dto = new RecordSubmitDTO();
        dto.setRequestId("req-001");
        dto.setSportType(1);
        dto.setPoints(List.of(validPoint(1)));
        return dto;
    }

    private static TrackPointDTO validPoint(int seq) {
        TrackPointDTO point = new TrackPointDTO();
        point.setSeq(seq);
        point.setLat(new BigDecimal("31.2304"));
        point.setLng(new BigDecimal("121.4737"));
        point.setTs(1700000000000L);
        return point;
    }

    @Test
    void validSubmit_passes() {
        assertTrue(validator.validate(validSubmit()).isEmpty(), "合法提交不应产生约束违规");
    }

    @Test
    void sportType_outOfRange_violates() {
        RecordSubmitDTO dto = validSubmit();
        dto.setSportType(4);

        Set<ConstraintViolation<RecordSubmitDTO>> violations = validator.validate(dto);

        assertEquals(1, violations.size());
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("sportType")
                        && v.getMessage().contains("运动类型非法")));
    }

    @Test
    void sportType_null_violates() {
        RecordSubmitDTO dto = validSubmit();
        dto.setSportType(null);

        Set<ConstraintViolation<RecordSubmitDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("sportType")
                        && v.getMessage().contains("不能为空")));
    }

    @Test
    void emptyPoints_violates() {
        RecordSubmitDTO dto = validSubmit();
        dto.setPoints(List.of());

        Set<ConstraintViolation<RecordSubmitDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("points")
                        && v.getMessage().contains("轨迹点不能为空")));
    }

    @Test
    void requestId_tooLong_violates() {
        RecordSubmitDTO dto = validSubmit();
        dto.setRequestId("r".repeat(65));

        Set<ConstraintViolation<RecordSubmitDTO>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("requestId")
                        && v.getMessage().contains("不能超过")));
    }

    @Test
    void invalidPoint_latOutOfRange_cascades() {
        RecordSubmitDTO dto = validSubmit();
        TrackPointDTO bad = validPoint(1);
        bad.setLat(new BigDecimal("91"));
        dto.setPoints(List.of(bad));

        Set<ConstraintViolation<RecordSubmitDTO>> violations = validator.validate(dto);

        Set<String> paths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(paths.contains("points[0].lat"), "级联校验应命中嵌套轨迹点的纬度字段");
    }
}
