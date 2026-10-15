package com.sportverify.verify.controller;

import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.common.result.Result;
import com.sportverify.verify.service.VerifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员终判接口（规范「终判改判」「终判维持拒绝」）。
 *
 * <p>网关 /admin/** → verify-service（StripPrefix=1）→ 本控制器，
 * 外部访问路径形如 {@code /admin/api/appeals/pending}（待审队列）与
 * {@code /admin/api/appeals/{id}/review}（终判）。</p>
 */
@RestController
@RequestMapping("/api/appeals")
@RequiredArgsConstructor
public class VerifyController {

    private final VerifyService verifyService;

    /**
     * 待审核申诉队列（B15 申诉待审闭环）：按 id DESC 返回所有 PENDING 状态申诉，
     * 供管理后台展示待审列表、一键填入审核，终结盲打 ID。外部经网关
     * {@code /admin/api/appeals/pending} 访问。
     */
    @GetMapping("/pending")
    public Result<List<AppealDTO>> listPendingAppeals() {
        return Result.success(verifyService.listPendingAppeals());
    }

    /**
     * 管理员终判：pass=true 改判通过（RE_PASSED + VERIFIED 事件）；
     * pass=false 维持拒绝（RE_CONFIRMED + REJECTED 事件）。
     */
    @PostMapping("/{id}/review")
    public Result<AppealDTO> review(@PathVariable("id") Long id, @Valid @RequestBody AppealReviewDTO dto) {
        return Result.success(verifyService.reviewAppeal(id, dto));
    }
}
