package com.sportverify.leaderboard.controller;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 榜单域对外接口（网关 /leaderboard/api/leaderboard → StripPrefix → 本控制器，
 * 对应审批版 §4.5 榜单：总榜 / 好友榜 / 快照结算）。
 *
 * <p>路由前缀随服务拆分变更：原 /record/api/leaderboard（record-service）已下线，
 * 现走独立 /leaderboard/** 路由指向本服务（服务数 4→5，见 ADR-0005）。
 * 数据源为 Redis ZSet（读多写少，秒级）；写入由 VERIFIED/REJECTED 事件消费驱动，
 * 定时结算任务对账纠偏。骨架无认证鉴权，好友榜查询人 userId 由调用方显式携带
 * （与点赞域惯例一致，接 JWT 后改从 token 解析）。</p>
 */
@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    /**
     * 榜单查询：{@code type=overall} 总榜（按累计 pass 里程降序取前 N）；
     * {@code type=friend} 好友榜（Feign 拉好友列表后过滤 ZSet，只显示好友）。
     * 无好友/好友未上榜返回空榜；非法 type 或 friend 缺 userId 返回 400。
     */
    @GetMapping
    public Result<List<LeaderboardDTO>> leaderboard(
            @RequestParam("type") String type,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return Result.success(leaderboardService.top(type, userId, size));
    }
}
