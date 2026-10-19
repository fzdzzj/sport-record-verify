package com.sportverify.user.controller;

import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.common.result.Result;
import com.sportverify.user.service.ClubService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * user-api Feign 契约实现（跑团域内部端点，TASK-276 R4）。
 *
 * <p>与对外 {@link ClubController} 共用 {@link ClubService}，只暴露服务间需要的只读面
 * （{@code /internal/**} 网内信任，无调用者上下文，不做 X-User-Id 鉴权，与
 * {@code InternalFriendController} 同款）：</p>
 * <ul>
 *   <li>{@code GET /internal/clubs/user/{userId}} —— 用户所在跑团（未入团 data=null），
 *       供 record-service 跑团里程入账前定位跑团、leaderboard-service 单团定位；</li>
 *   <li>{@code GET /internal/clubs/member-counts?ids=} —— clubId→member_count 批查，
 *       供 leaderboard-service 组装团队榜真实成员数（一次往返，避免逐团调用）。</li>
 * </ul>
 *
 * <p>两个端点均<b>只读</b>：不写 member_count、不写 role，跑团状态变更仍只走对外端点。</p>
 */
@RestController
@RequestMapping("/internal/clubs")
@RequiredArgsConstructor
public class InternalClubController {

    private final ClubService clubService;

    /**
     * 用户所在跑团（GET /internal/clubs/user/{userId}）。
     *
     * <p>未入团/跑团行缺失返回 {@code Result.success(null)}（data=null，语义为「无团」而非 404）；
     * 消费方据此跳过跑团里程入账，属正常业务分支。</p>
     */
    @GetMapping("/user/{userId}")
    public Result<ClubDTO> clubOfUser(@PathVariable("userId") Long userId) {
        return Result.success(clubService.getMyClub(userId));
    }

    /**
     * 跑团成员数批查（GET /internal/clubs/member-counts?ids=1,2,3）。
     *
     * <p>ids 为空/全空返回空表；不存在的跑团不出现在结果中，消费方对缺项降级 0
     * （榜单 memberCount「降级 0」占位语义）。</p>
     */
    @GetMapping("/member-counts")
    public Result<Map<Long, Integer>> memberCounts(@RequestParam("ids") List<Long> ids) {
        return Result.success(clubService.listMemberCounts(ids));
    }
}
