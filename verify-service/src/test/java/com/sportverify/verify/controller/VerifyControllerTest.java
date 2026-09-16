package com.sportverify.verify.controller;

import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.common.result.Result;
import com.sportverify.verify.service.VerifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员终判控制器单元测试（review 透传）。
 *
 * <p>纯 Mockito：mock VerifyService，断言 dto 透传与结果包装。</p>
 */
class VerifyControllerTest {

    private VerifyService verifyService;
    private VerifyController controller;

    @BeforeEach
    void setUp() {
        verifyService = mock(VerifyService.class);
        controller = new VerifyController(verifyService);
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
}