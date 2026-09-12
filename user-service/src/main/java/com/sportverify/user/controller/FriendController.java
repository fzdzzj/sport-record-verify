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
 * 好友域对外接口（网关 /user/api/friends/** → StripPrefix → 本控制器，
 * 对应审批版 §4.1 好友四接口）。
 *
 * <p>骨架无认证鉴权，申请人/查询人 userId 由调用方显式携带
 * （与 record 域提交/申诉入参约定一致）。</p>
 */
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    /**
     * 发起好友申请（规范差异「好友申请创建」「申请幂等去重」）：
     * 创建 status=PENDING 申请单；重复申请/反向 PENDING/已存在关系 → 5001；
     * 目标用户不存在 → 2002。
     */
    @PostMapping("/requests")
    public Result<FriendRequestDTO> createRequest(@RequestBody FriendRequestCreateDTO dto) {
        return Result.success(friendService.createRequest(dto.getUserId(), dto.getTargetUserId()));
    }

    /**
     * 同意申请（规范差异「申请状态机」）：PENDING → ACCEPTED 并落 friendship；
     * 非 PENDING 流转 → 5002。
     */
    @PostMapping("/requests/{id}/accept")
    public Result<FriendRequestDTO> accept(@PathVariable("id") Long id) {
        return Result.success(friendService.accept(id));
    }

    /**
     * 拒绝申请（规范差异「申请状态机」）：PENDING → REJECTED，不落 friendship；
     * 非 PENDING 流转 → 5002。
     */
    @PostMapping("/requests/{id}/reject")
    public Result<FriendRequestDTO> reject(@PathVariable("id") Long id) {
        return Result.success(friendService.reject(id));
    }

    /**
     * 好友列表（规范差异「好友列表」）：分页返回，仅 ACCEPTED 关系，
     * 由 (user_low,user_high) 反规范化还原对方 userId/nickname。
     */
    @GetMapping
    public Result<PageResult<FriendDTO>> list(@RequestParam("userId") Long userId,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return Result.success(friendService.listFriends(userId, page, size));
    }
}
