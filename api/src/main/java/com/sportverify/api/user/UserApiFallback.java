package com.sportverify.api.user;

import com.sportverify.api.common.PageResult;
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

/**
 * user-service Feign 降级工厂（add-resilience-hardening）。
 *
 * <p><b>产品口径（好友榜过滤）</b>：user-service 不可用时好友榜<b>返回空榜</b>，
 * 不选「不过滤」。不过滤会把总榜非好友泄露为好友排名，破坏隐私与产品语义；
 * 空榜仅短期可用性降级，可接受。本工厂对 {@code listFriends}/{@code listUsersByIds}
 * 返回空成功结果；写路径（申请/同意/拒绝）与探活不可软成功，抛 4006。</p>
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
        };
    }

    private static BizException degrade(String op, Throwable cause) {
        log.warn("user-service 熔断降级：op={}, cause={}", op, cause == null ? "unknown" : cause.toString());
        return new BizException(ResultCode.USER_SERVICE_UNAVAILABLE, "用户服务熔断降级：" + op);
    }
}
