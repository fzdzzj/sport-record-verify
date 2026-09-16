package com.sportverify.api.leaderboard;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 不假装返回榜单；当前仓库无 Feign 消费方。</p>
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
}
