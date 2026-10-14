package com.sportverify.api.leaderboard;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LeaderboardApi Feign「显式失败」工厂（add-resilience-hardening）。
 *
 * <p>契约层统一抛 {@code LEADERBOARD_SERVICE_UNAVAILABLE(4008)}，禁止假装返回榜单数据：
 * 读路径（{@code leaderboard}）由调用方决定空榜或错误展示；写路径
 * （{@link LeaderboardApi#rollbackContribution(Long)}，TASK-223 D1）刻意不静默成功——
 * 回撤未执行时贡献锚点仍是 ACTIVE，必须留证（调用方 record-service 捕获后 WARN）
 * 以便对账收敛，返回「成功」会伪造一致并掩盖里程漂移。</p>
 */
@Slf4j
@Component
public class LeaderboardApiFallback implements FallbackFactory<LeaderboardApi> {

    @Override
    public LeaderboardApi create(Throwable cause) {
        return new LeaderboardApi() {

            @Override
            public Result<List<LeaderboardDTO>> leaderboard(String type, Long userId, int size) {
                log.warn("leaderboard-service 不可用：type={}, userId={}, cause={}",
                        type, userId, cause == null ? "unknown" : cause.toString());
                throw new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE,
                        "榜单服务不可用：leaderboard");
            }

            @Override
            public Result<Void> rollbackContribution(Long recordId) {
                log.warn("leaderboard-service 不可用，榜单贡献回撤未执行（锚点残留 ACTIVE，待对账收敛）："
                        + "recordId={}, cause={}", recordId, cause == null ? "unknown" : cause.toString());
                throw new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE,
                        "榜单服务不可用：rollbackContribution");
            }
        };
    }
}
