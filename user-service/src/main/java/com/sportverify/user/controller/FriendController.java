package com.sportverify.user.controller;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.FriendRequestCreateDTO;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.service.FriendService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 好友域对外接口（网关 /user/api/friends/** → StripPrefix → 本控制器，
 * 对应审批版 §4.1 好友四接口）。
 *
 * <p>身份认定（add-jwt-auth，见 ADR-0007）：<b>已从网关注入的 X-User-Id 读取</b>——
 * 申请人/查询人 userId 不再信任请求体/参数显式携带值；{@code app.auth.enabled=true} 时
 * 以 X-User-Id 为准（显式值不一致 → 403 越权），false（默认）时降级旧行为（显式携带）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 发起好友申请（规范差异「好友申请创建」「申请幂等去重」）：
     * 创建 status=PENDING 申请单；重复申请/反向 PENDING/已存在关系 → 5001；
     * 目标用户不存在 → 2002。
     */
    @PostMapping("/requests")
    public Result<FriendRequestDTO> createRequest(@Valid @RequestBody FriendRequestCreateDTO dto,
                                                  HttpServletRequest request) {
        return Result.success(friendService.createRequest(
                resolveUserId(request, dto.getUserId()), dto.getTargetUserId()));
    }

    /**
     * 收到的待处理好友申请（TASK-235 add-friend-requests）：查询当前登录用户
     * 收到的 {@code PENDING(0)} 申请列表（to_user=本人），按创建时间倒序分页——
     * 前端「收到的好友申请」卡片据此渲染一键「同意」/「拒绝」，终结线下报
     * requestId 手工敲数字的交互。身份认定同 {@link #resolveUserId}（auth=true
     * 以网关注入 X-User-Id 为准；false 降级显式携带 userId）。
     */
    @GetMapping("/requests")
    public Result<PageResult<FriendRequestDTO>> listRequests(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        return Result.success(friendService.listPendingRequests(resolveUserId(request, userId), page, size));
    }

    /**
     * 同意申请（规范差异「申请状态机」）：PENDING → ACCEPTED 并落 friendship；
     * 非 PENDING 流转 → 5002。归属校验（TASK-204）：true 时以网关注入的 X-User-Id
     * 为调用者，服务层断言调用者==申请 to_user；false 时 caller=null 降级旧行为。
     */
    @PostMapping("/requests/{id}/accept")
    public Result<FriendRequestDTO> accept(@PathVariable("id") Long id,
                                           HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(friendService.accept(id, caller));
    }

    /**
     * 拒绝申请（规范差异「申请状态机」）：PENDING → REJECTED，不落 friendship；
     * 非 PENDING 流转 → 5002。归属校验（TASK-204）：true 时以网关注入的 X-User-Id
     * 为调用者，服务层断言调用者==申请 to_user；false 时 caller=null 降级旧行为。
     */
    @PostMapping("/requests/{id}/reject")
    public Result<FriendRequestDTO> reject(@PathVariable("id") Long id,
                                           HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(friendService.reject(id, caller));
    }

    /**
     * 批量同意好友申请（TASK-245 B25）：入参 {@code List<Long> requestIds}，逐条委托 accept
     * 并跳过已处理/越权申请，返回实际同意成功条数。身份认定同 {@link #resolveUserId}。
     */
    @PostMapping("/requests/batch-accept")
    public Result<Integer> batchAccept(@RequestBody List<Long> requestIds,
                                       HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(friendService.batchAccept(requestIds, caller));
    }

    /**
     * 批量拒绝/忽略好友申请（TASK-245 B25）：入参 {@code List<Long> requestIds}，逐条委托 reject
     * 并跳过已处理/越权申请，返回实际拒绝成功条数。身份认定同 {@link #resolveUserId}。
     */
    @PostMapping("/requests/batch-reject")
    public Result<Integer> batchReject(@RequestBody List<Long> requestIds,
                                       HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(friendService.batchReject(requestIds, caller));
    }

    /**
     * 好友列表（规范差异「好友列表」）：分页返回，仅 ACCEPTED 关系，
     * 由 (user_low,user_high) 反规范化还原对方 userId/nickname。
     */
    @GetMapping
    public Result<PageResult<FriendDTO>> list(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        return Result.success(friendService.listFriends(resolveUserId(request, userId), page, size));
    }

    /**
     * 解除好友关系（TASK-242 B14 社交闭环）：删除规范化 {@code (user_low,user_high)}
     * 好友行，幂等成功（关系不存在亦返回成功）。身份认定同 {@link #resolveUserId}
     * （auth=true 以网关注入 X-User-Id 为准；false 降级显式携带路径参数由服务层自判）。
     */
    @DeleteMapping("/{targetUserId}")
    public Result<Void> deleteFriend(@PathVariable("targetUserId") Long targetUserId,
                                     HttpServletRequest request) {
        Long userId = resolveUserId(request, null);
        friendService.deleteFriend(userId, targetUserId);
        return Result.success();
    }

    /**
     * 身份认定（数据隔离核心，见 ADR-0007）：true 时以网关 X-User-Id 为准，
     * 显式携带值不一致 → 403（1002，越权）；false 时降级为显式携带值（旧行为）。
     */
    private Long resolveUserId(HttpServletRequest request, Long claimed) {
        if (authEnabled) {
            String header = request.getHeader(HEADER_USER_ID);
            if (header == null || header.isBlank()) {
                throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
            }
            Long tokenUserId = Long.parseLong(header);
            if (claimed != null && !claimed.equals(tokenUserId)) {
                // 调用方试图以他人身份申请/查好友：不信任自报家门，越权 → 403
                log.warn("越权访问被拒：tokenUserId={}, claimed={}", tokenUserId, claimed);
                throw new BizException(ResultCode.FORBIDDEN);
            }
            return tokenUserId;
        }
        return claimed;
    }
}
