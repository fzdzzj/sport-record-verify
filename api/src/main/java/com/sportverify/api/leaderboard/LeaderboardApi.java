package com.sportverify.api.leaderboard;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * leaderboard-service Feign 契约（leaderboard-api，接口与实现分离）。
 *
 * <p>契约随服务拆分自 RecordApi 迁出（服务数 4→5，榜单职责独立为
 * leaderboard-service，见 ADR-0005）。实现位于 leaderboard-service 的
 * InternalLeaderboardController（{@code /internal} 前缀），榜单查询
 * 供后续变更跨服务取数。</p>
 *
 * <p>降级决策（add-resilience-hardening）：契约层显式抛 4008（见 {@link LeaderboardApiFallback}），
 * 不假装返回榜单。消费方现状：record-service 删除记录后经 {@link #rollbackContribution(Long)}
 * 回撤榜单里程贡献（TASK-223 D1，best-effort 写路径）；榜单查询读路径暂无消费方。</p>
 */
@FeignClient(name = "leaderboard-service", path = "/internal", fallbackFactory = LeaderboardApiFallback.class)
public interface LeaderboardApi {

    /**
     * 榜单查询（审批版 §4.5）。
     *
     * @param type   overall 总榜 / friend 好友榜
     * @param userId 查询人（friend 榜必填：按其好友列表过滤 ZSet；overall 忽略）
     * @param size   取前 N 名（榜单读多写少：ZREVRANGE 秒级）
     */
    @GetMapping("/leaderboard")
    Result<List<LeaderboardDTO>> leaderboard(@RequestParam("type") String type,
                                             @RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "size", defaultValue = "50") int size);

    /**
     * 榜单里程贡献回撤（TASK-223 D1）：用户删除/撤回运动记录后，由 record-service 在删除事务
     * {@code afterCommit} 内 best-effort 调用，通知 leaderboard-service 把该记录的贡献锚点
     * {@code ACTIVE → ROLLED_BACK} 并扣回 ZSet 里程。
     *
     * <p>服务侧幂等（复用 {@code LeaderboardService.rollbackOnRejected}）：无贡献锚点（记录从未入榜）
     * 或已 ROLLED_BACK（重复调用）均跳过，不产生负里程；同一 {@code lock:rollback:{recordId}}
     * 与入榜互斥，锚点行乐观迁移保证只有一次调用真正扣分。</p>
     *
     * <p>语义为「尽力而为」：调用方吞并异常留证，跨服务最终一致（ADR-0009，无本地跨库事务）；
     * 降级时 fallback 显式抛 4008（不假装成功），使残留的 ACTIVE 锚点可被对账/人工发现。</p>
     *
     * @param recordId 运动记录 id（已删除记录的主键，作为贡献锚点主键）
     */
    @PostMapping("/leaderboard/records/{recordId}/rollback")
    Result<Void> rollbackContribution(@PathVariable("recordId") Long recordId);
}
