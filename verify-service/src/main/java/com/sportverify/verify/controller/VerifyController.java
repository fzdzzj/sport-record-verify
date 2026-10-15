package com.sportverify.verify.controller;

import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.common.result.Result;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import com.sportverify.verify.service.VerifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
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
    private final VerifyEventOutboxMapper verifyEventOutboxMapper;

    /** B20 死信拉取 limit 上限：防止手滑传超大值整表倾倒进内存 */
    private static final int EXHAUSTED_LIMIT_MAX = 200;

    /** B20 死信拉取默认条数 */
    private static final int EXHAUSTED_LIMIT_DEFAULT = 50;

    /** B20 历史投递清理默认阈值天数 */
    private static final int CLEAN_SENT_DAYS_DEFAULT = 7;

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

    /**
     * B20 Outbox 毒丸死信列表（管理面防御面，经网关 /admin/api/outbox/exhausted 访问）：
     * 返回 EXHAUSTED 终态行（id 倒序），供人工查看与一键重放。limit 缺省 50，上限 200。
     */
    @GetMapping("/outbox/exhausted")
    public Result<List<VerifyEventOutbox>> listExhaustedOutbox(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int capped = (limit == null || limit <= 0)
                ? EXHAUSTED_LIMIT_DEFAULT : Math.min(limit, EXHAUSTED_LIMIT_MAX);
        return Result.success(verifyEventOutboxMapper.selectExhausted(capped));
    }

    /**
     * B20 Outbox 毒丸人工重放：EXHAUSTED → PENDING（retry_count=0），重新进入 relay 投递集。
     * 返回实际重放行数（非 EXHAUSTED / 不存在 → 0）。
     */
    @PostMapping("/outbox/exhausted/{id}/replay")
    public Result<Integer> replayExhausted(@PathVariable("id") Long id) {
        return Result.success(verifyEventOutboxMapper.replayExhausted(id));
    }

    /**
     * B20 历史已投递行物理清理：仅删除 status='SENT' 且 sent_at 早于 {@code now - days} 的
     * 过期行（单批上限 200），返回实际清理行数。days 缺省 7，最小 1。
     */
    @DeleteMapping("/outbox/sent")
    public Result<Integer> cleanSentOutbox(
            @RequestParam(value = "days", required = false) Integer days) {
        int thresholdDays = (days == null || days <= 0) ? CLEAN_SENT_DAYS_DEFAULT : days;
        LocalDateTime before = LocalDateTime.now().minusDays(thresholdDays);
        return Result.success(verifyEventOutboxMapper.cleanSentEvents(before, EXHAUSTED_LIMIT_MAX));
    }
}
