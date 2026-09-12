package com.sportverify.user.controller;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.FriendRequestCreateDTO;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.common.result.Result;
import com.sportverify.user.service.FriendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * user-api Feign 契约实现（好友域，接口与实现分离，规范「服务间 Feign 契约」）。
 *
 * <p>消费方为 record-service（后续好友榜过滤）：经 UserApi 申请/同意/拒绝/拉好友列表。
 * 与对外 {@link FriendController} 共用同一服务层。</p>
 */
@RestController
@RequestMapping("/internal/friends")
@RequiredArgsConstructor
public class InternalFriendController {

    private final FriendService friendService;

    /** 发起好友申请 */
    @PostMapping("/requests")
    public Result<FriendRequestDTO> createRequest(@RequestBody FriendRequestCreateDTO dto) {
        return Result.success(friendService.createRequest(dto.getUserId(), dto.getTargetUserId()));
    }

    /** 同意申请 */
    @PostMapping("/requests/{id}/accept")
    public Result<FriendRequestDTO> accept(@PathVariable("id") Long id) {
        return Result.success(friendService.accept(id));
    }

    /** 拒绝申请 */
    @PostMapping("/requests/{id}/reject")
    public Result<FriendRequestDTO> reject(@PathVariable("id") Long id) {
        return Result.success(friendService.reject(id));
    }

    /** 好友列表（分页，仅 ACCEPTED；record-service 好友榜过滤入口） */
    @GetMapping
    public Result<PageResult<FriendDTO>> list(@RequestParam("userId") Long userId,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return Result.success(friendService.listFriends(userId, page, size));
    }
}
