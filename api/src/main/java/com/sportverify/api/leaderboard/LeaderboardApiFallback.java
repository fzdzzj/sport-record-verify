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
 * （{@link LeaderboardApi#rollbackContribution(Long)}，TASK-223 D1；{@link LeaderboardApi#recordClubDistance}
 * 跑团里程入账，TASK-276 R2）刻意不静默成功——回撤未执行时贡献锚点仍是 ACTIVE、里程未入账时
 * 跑团榜真实少记，二者都必须留证（调用方 record-service 捕获后 WARN）以便对账收敛，
 * 返回「成功」会伪造一致并掩盖里程漂移。</p>
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

            @Override
            public Result<Boolean> recordClubDistance(LeaderboardApi.ClubDistancePayload payload) {
                // 写路径刻意不静默成功（同 rollbackContribution）：跑团里程未入账是真实缺口，
                // 必须留证给调用方 WARN 与后续对账，返回「成功」会伪造一致并掩盖里程漂移。
                log.warn("leaderboard-service 不可用，跑团里程入账未执行（净额缺口待对账收敛）："
                        + "clubId={}, userId={}, distanceMeters={}, cause={}",
                        payload == null ? null : payload.getClubId(),
                        payload == null ? null : payload.getUserId(),
                        payload == null ? null : payload.getDistanceMeters(),
                        cause == null ? "unknown" : cause.toString());
                throw new BizException(ResultCode.LEADERBOARD_SERVICE_UNAVAILABLE,
                        "榜单服务不可用：recordClubDistance");
            }
        };
    }
}
