package com.sportverify.verify.controller;

import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.common.result.Result;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import com.sportverify.verify.service.VerifyService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员终判控制器单元测试（review 透传 + 待审队列透传 + B20 outbox 死信/重放/清理）。
 *
 * <p>纯 Mockito：mock VerifyService 与 VerifyEventOutboxMapper，断言透传与结果包装。</p>
 */
class VerifyControllerTest {

    private VerifyService verifyService;
    private VerifyEventOutboxMapper outboxMapper;
    private VerifyController controller;

    @BeforeEach
    void setUp() {
        verifyService = mock(VerifyService.class);
        outboxMapper = mock(VerifyEventOutboxMapper.class);
        controller = new VerifyController(verifyService, outboxMapper);
    }

    /** 待审队列：透传 service 待审列表，包装 code=0 */
    @Test
    void listPendingAppeals_delegates() {
        AppealDTO a1 = new AppealDTO();
        a1.setId(3L);
        a1.setRecordId(20L);
        AppealDTO a2 = new AppealDTO();
        a2.setId(2L);
        a2.setRecordId(19L);
        when(verifyService.listPendingAppeals()).thenReturn(List.of(a1, a2));

        Result<List<AppealDTO>> out = controller.listPendingAppeals();

        assertEquals(0, out.getCode());
        assertEquals(2, out.getData().size());
        assertEquals(3L, out.getData().get(0).getId());
        verify(verifyService).listPendingAppeals();
    }

    /** review 透传 service 结果，包装 code=0 */
    @Test
    void review_delegates() {
        AppealReviewDTO dto = new AppealReviewDTO();
        dto.setPass(true);
        dto.setOperator("admin");
        dto.setRecheckResult("复核通过");
        AppealDTO appeal = new AppealDTO();
        when(verifyService.reviewAppeal(9L, dto)).thenReturn(appeal);

        Result<AppealDTO> out = controller.review(9L, dto);

        assertEquals(0, out.getCode());
        assertEquals(appeal, out.getData());
        verify(verifyService).reviewAppeal(9L, dto);
    }

    /** E22（TASK-256）：终判缺 pass → @Valid 校验 400（漏传不再静默默认维持拒绝） */
    @Test
    void review_missingPass_failsBeanValidation() {
        AppealReviewDTO dto = new AppealReviewDTO();
        dto.setOperator("admin"); // pass 未设置 → null

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<AppealReviewDTO>> violations = validator.validate(dto);

        assertEquals(1, violations.size(), "仅 pass 一处 @NotNull 违规");
        assertEquals("pass",
                violations.iterator().next().getPropertyPath().toString());
    }

    // ==================== B20 Outbox 死信/重放/清理 ====================

    /** 死信列表缺省 limit=50，透传 mapper selectExhausted，包装 code=0 */
    @Test
    void listExhaustedOutbox_defaultLimit_delegates() {
        VerifyEventOutbox o = new VerifyEventOutbox();
        o.setId(5L);
        o.setStatus("EXHAUSTED");
        when(outboxMapper.selectExhausted(50)).thenReturn(List.of(o));

        Result<List<VerifyEventOutbox>> out = controller.listExhaustedOutbox(null);

        assertEquals(0, out.getCode());
        assertEquals(1, out.getData().size());
        assertEquals(5L, out.getData().get(0).getId());
        verify(outboxMapper).selectExhausted(50);
    }

    /** 死信列表 limit cap=200：超出上限收敛为 200 */
    @Test
    void listExhaustedOutbox_limitCappedTo200() {
        when(outboxMapper.selectExhausted(200)).thenReturn(List.of());

        Result<List<VerifyEventOutbox>> out = controller.listExhaustedOutbox(9999);

        assertEquals(0, out.getCode());
        assertTrue(out.getData().isEmpty());
        verify(outboxMapper).selectExhausted(200);
    }

    /** 毒丸人工重放：返回 mapper replayExhausted 实际影响行数 */
    @Test
    void replayExhausted_delegates() {
        when(outboxMapper.replayExhausted(3L)).thenReturn(1);

        Result<Integer> out = controller.replayExhausted(3L);

        assertEquals(0, out.getCode());
        assertEquals(1, out.getData());
        verify(outboxMapper).replayExhausted(3L);
    }

    /** 毒丸重放幂等：非 EXHAUSTED / 不存在影响 0 行，仍返回 0 */
    @Test
    void replayExhausted_notExhausted_zeroRows() {
        when(outboxMapper.replayExhausted(9L)).thenReturn(0);

        Result<Integer> out = controller.replayExhausted(9L);

        assertEquals(0, out.getCode());
        assertEquals(0, out.getData());
        verify(outboxMapper).replayExhausted(9L);
    }

    /** 历史投递清理缺省 days=7：阈值=now-7 天，单批上限 200 */
    @Test
    void cleanSentOutbox_defaultDays7() {
        when(outboxMapper.cleanSentEvents(any(LocalDateTime.class), eq(200))).thenReturn(12);

        Result<Integer> out = controller.cleanSentOutbox(null);

        assertEquals(0, out.getCode());
        assertEquals(12, out.getData());
        verify(outboxMapper).cleanSentEvents(any(LocalDateTime.class), eq(200));
    }

    /** 历史投递清理自定义 days：阈值=now-days 天 */
    @Test
    void cleanSentOutbox_customDays() {
        when(outboxMapper.cleanSentEvents(any(LocalDateTime.class), eq(200))).thenReturn(3);

        Result<Integer> out = controller.cleanSentOutbox(30);

        assertEquals(0, out.getCode());
        assertEquals(3, out.getData());
        verify(outboxMapper).cleanSentEvents(any(LocalDateTime.class), eq(200));
    }
}