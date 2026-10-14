package com.sportverify.leaderboard.controller;

import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * leaderboard-api Feign 契约实现（{@link LeaderboardApi}，接口与实现分离，{@code /internal} 前缀）。
 *
 * <p>榜单查询供后续变更跨服务取数（与 record-service 的点赞内部接口同款惯例）；
 * 公开端点 {@code /api/leaderboard} 由 LeaderboardController 承载，二者共用服务层。</p>
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalLeaderboardController {

    private final LeaderboardService leaderboardService;

    /** 榜单查询（Feign 契约实现，审批版 §4.5；与公开端点共用 LeaderboardService） */
    @GetMapping("/leaderboard")
    public Result<List<LeaderboardDTO>> leaderboard(@RequestParam("type") String type,
                                                    @RequestParam(value = "userId", required = false) Long userId,
                                                    @RequestParam(value = "size", defaultValue = "50") int size) {
        return Result.success(leaderboardService.top(type, userId, size));
    }

    /**
     * 榜单里程贡献回撤（Feign 契约实现，TASK-223 D1）：记录被删除/撤回后回撤其榜单贡献。
     *
     * <p>承接 {@link LeaderboardService#rollbackOnRejected(Long)}（既有乐观锁状态机，未改动）：
     * 锚点 {@code ACTIVE → ROLLED_BACK} 成功后按锚点里程扣回 ZSet；无锚点或已回滚幂等跳过，
     * 不产生负里程。锁竞争超时抛 {@code IllegalStateException}（由调用方 best-effort 吞并留证）。</p>
     */
    @PostMapping("/leaderboard/records/{recordId}/rollback")
    public Result<Void> rollbackContribution(@PathVariable("recordId") Long recordId) {
        leaderboardService.rollbackOnRejected(recordId);
        return Result.success();
    }
}
