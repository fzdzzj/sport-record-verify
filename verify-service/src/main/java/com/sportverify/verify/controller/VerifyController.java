package com.sportverify.verify.controller;

import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.common.result.Result;
import com.sportverify.verify.service.VerifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员终判接口（规范「终判改判」「终判维持拒绝」）。
 *
 * <p>网关 /admin/** → verify-service（StripPrefix=1）→ 本控制器，
 * 外部访问路径形如 {@code /admin/api/appeals/{id}/review}。</p>
 */
@RestController
@RequestMapping("/api/appeals")
@RequiredArgsConstructor
public class VerifyController {

    private final VerifyService verifyService;

    /**
     * 管理员终判：pass=true 改判通过（RE_PASSED + VERIFIED 事件）；
     * pass=false 维持拒绝（RE_CONFIRMED + REJECTED 事件）。
     */
    @PostMapping("/{id}/review")
    public Result<AppealDTO> review(@PathVariable("id") Long id, @Valid @RequestBody AppealReviewDTO dto) {
        return Result.success(verifyService.reviewAppeal(id, dto));
    }
}
