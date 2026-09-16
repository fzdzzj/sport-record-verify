package com.sportverify.api.user;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.FriendRequestCreateDTO;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * user-service Feign 契约（user-api）。
 *
 * <p>健康探活 + 好友域四接口（申请/同意/拒绝/列表）。
 * 实现位于 user-service 的 InternalHealthController 与 InternalFriendController
 * （{@code /internal} 前缀），好友列表供 leaderboard-service 好友榜过滤调用。</p>
 *
 * <p>降级决策（add-resilience-hardening）：读路径（好友列表/批量用户）可软降级为空，
 * 好友榜产品口径为<b>空榜</b>而非「不过滤」；写路径不可软成功，见 {@link UserApiFallback}。</p>
 */
@FeignClient(name = "user-service", path = "/internal", fallbackFactory = UserApiFallback.class)
public interface UserApi {

    /**
     * 健康探活：其他服务经本接口确认 user-service 存活。
     */
    @GetMapping("/health")
    Result<String> health();

    // ===== 好友域（规范差异：好友申请创建 / 申请幂等去重 / 申请状态机 / 好友列表）=====

    /**
     * 发起好友申请：创建 status=PENDING 的申请单。
     * 幂等去重：同向 PENDING 重复 / 反向 PENDING 已存在 / 已存在关系 → 5001（或返回原申请单）。
     */
    @PostMapping("/friends/requests")
    Result<FriendRequestDTO> createFriendRequest(@RequestBody FriendRequestCreateDTO dto);

    /**
     * 同意申请：仅 PENDING 可流转，ACCEPTED 落 friendship（user_low&lt;user_high 规范化存储，
     * 并发互加只产生一条关系）；非 PENDING 流转 → 5002。
     */
    @PostMapping("/friends/requests/{id}/accept")
    Result<FriendRequestDTO> acceptFriendRequest(@PathVariable("id") Long id);

    /**
     * 拒绝申请：PENDING → REJECTED，不落 friendship；非 PENDING 流转 → 5002。
     */
    @PostMapping("/friends/requests/{id}/reject")
    Result<FriendRequestDTO> rejectFriendRequest(@PathVariable("id") Long id);

    /**
     * 好友列表：分页返回，仅包含 ACCEPTED 关系（排除 PENDING/REJECTED/CANCELLED）。
     */
    @GetMapping("/friends")
    Result<PageResult<FriendDTO>> listFriends(@RequestParam("userId") Long userId,
                                              @RequestParam("page") long page,
                                              @RequestParam("size") long size);

    /**
     * 按用户ID集合批量查用户概要（榜单场景：总榜前 N 名一次拉齐昵称，
     * 避免逐条远程调用；不存在的用户不出现在结果中，由消费方兜底展示）。
     */
    @GetMapping("/users")
    Result<List<UserDTO>> listUsersByIds(@RequestParam("ids") List<Long> ids);
}
