package com.sportverify.api.leaderboard;

import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * LeaderboardApi Feign「显式失败」工厂（add-resilience-hardening）。
 *
 * <p>当前仓库无 Feign 消费方；契约层统一抛 {@code LEADERBOARD_SERVICE_UNAVAILABLE(4008)}，
 * 禁止假装返回榜单数据。未来读路径接入时由调用方决定空榜或错误展示。</p>
 */
@Slf4j
@Component
public class LeaderboardApiFallback implements FallbackFactory<LeaderboardApi> {

    @Override
    public LeaderboardApi create(Throwable cause) {
        return (type, userId, size) -> {
            log.warn("leaderboard-service 不可用：type={}, userId={}, cause={}",
                    type, userId, cause == null ? "unknown" : cause.toString());
            throw new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE,
                    "榜单服务不可用：leaderboard");
        };
    }
}
