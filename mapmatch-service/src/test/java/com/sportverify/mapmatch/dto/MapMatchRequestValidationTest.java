package com.sportverify.mapmatch.dto;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匹配请求 DTO 入参校验单测（mapmatch-api 契约，TASK-214）。
 *
 * <p>纯 jakarta.validation.Validator（hibernate-validator 实现）驱动，不依赖 Spring 上下文，
 * 照 user-service AuthRequestDtoValidationTest 范式。锁死契约：points 必填且上限 20000
 * （防超大请求体），@Valid 级联到 MapMatchPointDTO 的 seq/lat/lng 必填与经纬度边界。
 * 控制器层 @Valid 在手工构造单测下不触发，入参校验语义由本类承载（控制器单测只测委托与包装）。</p>
 */
class MapMatchRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static MapMatchPointDTO point(int seq, double lat, double lng) {
        MapMatchPointDTO p = new MapMatchPointDTO();
        p.setSeq(seq);
        p.setLat(lat);
        p.setLng(lng);
        return p;
    }

    private static MapMatchRequestDTO request(List<MapMatchPointDTO> points) {
        MapMatchRequestDTO req = new MapMatchRequestDTO();
        req.setPoints(points);
        return req;
    }

    /** R1a 空 points → @NotEmpty 恰 1 条违规（propertyPath="points"），@Size 不误伤 */
    @Test
    void emptyPoints_violatesNotEmpty() {
        Set<ConstraintViolation<MapMatchRequestDTO>> violations =
                validator.validate(request(new ArrayList<>()));

        assertEquals(1, violations.size());
        ConstraintViolation<MapMatchRequestDTO> violation = violations.iterator().next();
        assertEquals("points", violation.getPropertyPath().toString());
        assertEquals("轨迹点不能为空", violation.getMessage());
    }

    /** R1b 上限边界：恰 20000 点合法，零违规 */
    @Test
    void points_at20000Boundary_valid() {
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            points.add(point(i, 31.22, 121.47));
        }

        assertTrue(validator.validate(request(points)).isEmpty(), "20000 点恰在上限内，不应产生违规");
    }

    /** R1b 上限边界：20001 点 → @Size(max=20000) 恰 1 条违规 */
    @Test
    void points_over20000Boundary_violatesSize() {
        List<MapMatchPointDTO> points = new ArrayList<>();
        for (int i = 0; i < 20001; i++) {
            points.add(point(i, 31.22, 121.47));
        }

        Set<ConstraintViolation<MapMatchRequestDTO>> violations = validator.validate(request(points));

        assertEquals(1, violations.size());
        ConstraintViolation<MapMatchRequestDTO> violation = violations.iterator().next();
        assertEquals("points", violation.getPropertyPath().toString());
        assertEquals("轨迹点数量超出上限（20000）", violation.getMessage());
    }

    /** R1c 级联校验：点内 seq=null → points[0].seq 违规（@NotNull） */
    @Test
    void cascaded_seqNull_violates() {
        MapMatchPointDTO bad = point(1, 31.22, 121.47);
        bad.setSeq(null);

        Set<ConstraintViolation<MapMatchRequestDTO>> violations =
                validator.validate(request(List.of(bad)));

        assertEquals(1, violations.size());
        assertEquals("points[0].seq", violations.iterator().next().getPropertyPath().toString());
    }

    /** R1c 级联校验：点内 lat=90.5 越出 [-90,90] → points[0].lat 违规（@DecimalMax） */
    @Test
    void cascaded_latOutOfRange_violates() {
        MapMatchPointDTO bad = point(1, 31.22, 121.47);
        bad.setLat(90.5);

        Set<ConstraintViolation<MapMatchRequestDTO>> violations =
                validator.validate(request(List.of(bad)));

        assertEquals(1, violations.size());
        ConstraintViolation<MapMatchRequestDTO> violation = violations.iterator().next();
        assertEquals("points[0].lat", violation.getPropertyPath().toString());
        assertEquals("纬度超出范围", violation.getMessage());
    }

    /** R1c 级联校验：点内 lng=null → points[0].lng 违规（@NotNull） */
    @Test
    void cascaded_lngNull_violates() {
        MapMatchPointDTO bad = point(1, 31.22, 121.47);
        bad.setLng(null);

        Set<ConstraintViolation<MapMatchRequestDTO>> violations =
                validator.validate(request(List.of(bad)));

        assertEquals(1, violations.size());
        assertEquals("points[0].lng", violations.iterator().next().getPropertyPath().toString());
    }

    /** R1d 全合法请求（seq/lat/lng 齐备且在界内）零违规 */
    @Test
    void fullyValidRequest_zeroViolations() {
        MapMatchRequestDTO req = request(List.of(point(0, -90.0, -180.0), point(1, 90.0, 180.0)));

        assertTrue(validator.validate(req).isEmpty(), "全合法请求不应产生约束违规");
    }
}
