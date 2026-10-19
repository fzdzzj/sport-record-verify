package com.sportverify.api.user;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.ClubDTO;
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
import java.util.Map;

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

    /**
     * 两点是否互为好友（{@code userId} 与 {@code otherUserId} 之间已结成 ACCEPTED 关系，
     * 提供逐对判断，避免引入好友榜分页那样过重的逐对循环）。
     *
     * <p>读路径授权用（add-record-visibility，TASK-221）：record-service 在 {@code FRIENDS}
     * 可见记录上判断「读取者是否记录归属人的好友」。{@code isFriends} 名称避免与
     * 写路径动作动词（create/accept/reject）混淆；语义 = {@code friendship} 表中以
     * {@code user_low<user_high} 规范化存储的关系存在（规范差异「好友关系规范化存储」）。</p>
     */
    @GetMapping("/friends/exists")
    Result<Boolean> areFriends(@RequestParam("userId") Long userId,
                               @RequestParam("otherUserId") Long otherUserId);

    /**
     * 好友 ID 全量列表（{@code userId} 的全部已接受好友 user_id，双向还原），
     * 供好友动态 feed（TASK-222 add-friend-feed）一次内部调用拉齐 {@code IN (...)}
     * 子句的候选归属人集合。
     *
     * <p>与 {@link #listFriends} 的分页返回不同，本接口<b>不分页</b>，一次返回该用户
     * 全部好友 ID（feed 查询依赖完整集合做 {@code user_id IN} 过滤，分页截断会让 feed
     * 丢失未见好友的记录）。user-service 不可用时由 {@link UserApiFallback} 软降级为空列表
     * ——feed 退化为仅显示本人记录（fail-safe，不阻断不报错）。</p>
     */
    @GetMapping("/friends/ids")
    Result<List<Long>> listFriendIds(@RequestParam("userId") Long userId);

    // ===== 跑团域（TASK-276：里程入账定位所在跑团 + 团队榜 memberCount 真实计数）=====

    /**
     * 用户所在跑团（TASK-276 R2/R4）：按 {@code userId} 反查其 {@code club_member} 行对应的
     * 跑团（一人一团约束下至多一行），<b>未入团返回 {@code Result.success(null)}</b>（data=null，
     * 不是 404）；跑团行缺失（脏数据）同样返回 null。
     *
     * <p>消费方两组：record-service 在运动记录终态迁移后据此判断是否需要上报跑团里程
     * （未入团跳过，见 {@code ClubDistanceSettleService}）；leaderboard-service 组装团队榜
     * 时作为单团定位入口。</p>
     *
     * <p>降级（{@link UserApiFallback}）：user-service 不可用时同样返回 data=null（视作未入团），
     * 使里程入账旁路自然跳过——宁少记不误记，且不得阻断状态回调主链路。</p>
     */
    @GetMapping("/clubs/user/{userId}")
    Result<ClubDTO> getClubByUserId(@PathVariable("userId") Long userId);

    /**
     * 跑团成员数批查（TASK-276 R4）：{@code ids} 为 clubId 列表，返回 clubId→member_count
     * 映射；不存在的跑团/已解散跑团不出现在结果中（由消费方降级 0）。
     *
     * <p>供 leaderboard-service 组装团队榜前 N 名时<b>一次</b>拉齐真实成员数，避免逐团远程调用。
     * 降级（{@link UserApiFallback}）：user-service 不可用时返回空表 → 榜单 memberCount 降级 0。</p>
     */
    @GetMapping("/clubs/member-counts")
    Result<Map<Long, Integer>> listClubMemberCounts(@RequestParam("ids") List<Long> ids);
}
