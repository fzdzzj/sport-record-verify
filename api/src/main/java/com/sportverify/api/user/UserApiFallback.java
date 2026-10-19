package com.sportverify.api.user;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.FriendRequestCreateDTO;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * user-service Feign 降级工厂（add-resilience-hardening）。
 *
 * <p><b>产品口径（好友榜过滤）</b>：user-service 不可用时好友榜<b>返回空榜</b>，
 * 不选「不过滤」。不过滤会把总榜非好友泄露为好友排名，破坏隐私与产品语义；
 * 空榜仅短期可用性降级，可接受。本工厂对 {@code listFriends}/{@code listUsersByIds}
 * 返回空成功结果；{@code areFriends} 在 user-service 不可用时<b>返回 false</b>
 * （失败安全隐私：读路径对 {@code FRIENDS} 可见记录的最保守裁决是「非好友」= 403，
 * 与好友榜空榜同口径——不允许把好友判断软成功为 true 而泄露），写路径（申请/同意/拒绝）
 * 与探活不可软成功，抛 4006。</p>
 */
@Slf4j
@Component
public class UserApiFallback implements FallbackFactory<UserApi> {

    @Override
    public UserApi create(Throwable cause) {
        return new UserApi() {
            @Override
            public Result<String> health() {
                throw degrade("health", cause);
            }

            @Override
            public Result<FriendRequestDTO> createFriendRequest(FriendRequestCreateDTO dto) {
                throw degrade("createFriendRequest", cause);
            }

            @Override
            public Result<FriendRequestDTO> acceptFriendRequest(Long id) {
                throw degrade("acceptFriendRequest:" + id, cause);
            }

            @Override
            public Result<FriendRequestDTO> rejectFriendRequest(Long id) {
                throw degrade("rejectFriendRequest:" + id, cause);
            }

            @Override
            public Result<PageResult<FriendDTO>> listFriends(Long userId, long page, long size) {
                // 读路径软降级：空好友列表 → 调用方好友榜为空榜（禁止不过滤）
                log.warn("user-service 熔断降级：listFriends 返回空页（好友榜空榜口径），userId={}, cause={}",
                        userId, cause == null ? "unknown" : cause.toString());
                return Result.success(new PageResult<>(page, size, 0, Collections.emptyList()));
            }

            @Override
            public Result<List<UserDTO>> listUsersByIds(List<Long> ids) {
                // 读路径软降级：空用户列表 → 调用方昵称占位符兜底
                log.warn("user-service 熔断降级：listUsersByIds 返回空列表，ids={}, cause={}",
                        ids == null ? 0 : ids.size(), cause == null ? "unknown" : cause.toString());
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<Long>> listFriendIds(Long userId) {
                // 读路径软降级：空好友 ID 列表 → 调用方（feed）退化为仅显示本人记录
                // （fail-safe：不把好友动态错误地当全部，也不报错阻断 feed 主链路）。
                // 与 listUsersByIds/listFriends 空成功结果同口径（好友榜空榜/昵称占位符兜底）。
                log.warn("user-service 熔断降级：listFriendIds 返回空列表（feed 退化为仅本人），userId={}, cause={}",
                        userId, cause == null ? "unknown" : cause.toString());
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<Boolean> areFriends(Long userId, Long otherUserId) {
                // 读路径软降级为 false（失败安全隐私）：FRIENDS 可见记录对非确认好友最保守裁决是「非好友」→
                // 调用方 403。绝不允许软成功为 true（否则隐私泄露），与好友榜空榜同口径。
                log.warn("user-service 熔断降级：areFriends 返回 false（失败安全隐私），userId={}, otherUserId={}, cause={}",
                        userId, otherUserId, cause == null ? "unknown" : cause.toString());
                return Result.success(false);
            }

            @Override
            public Result<ClubDTO> getClubByUserId(Long userId) {
                // 读路径软降级为空（视作未入团）：跑团里程入账旁路自然跳过（宁少记不误记），
                // 团队榜 memberCount 亦按降级 0 处理，均不得阻断调用方主链路。
                log.warn("user-service 熔断降级：getClubByUserId 返回 null（视作未入团），userId={}, cause={}",
                        userId, cause == null ? "unknown" : cause.toString());
                return Result.success(null);
            }

            @Override
            public Result<Map<Long, Integer>> listClubMemberCounts(List<Long> ids) {
                // 读路径软降级：空映射 → 调用方榜单 memberCount 降级 0（保持占位语义，榜单主体照常返回）
                log.warn("user-service 熔断降级：listClubMemberCounts 返回空表（memberCount 降级 0），ids={}, cause={}",
                        ids == null ? 0 : ids.size(), cause == null ? "unknown" : cause.toString());
                return Result.success(Collections.emptyMap());
            }
        };
    }

    private static BizException degrade(String op, Throwable cause) {
        log.warn("user-service 熔断降级：op={}, cause={}", op, cause == null ? "unknown" : cause.toString());
        return new BizException(ResultCode.USER_SERVICE_UNAVAILABLE, "用户服务熔断降级：" + op);
    }
}
