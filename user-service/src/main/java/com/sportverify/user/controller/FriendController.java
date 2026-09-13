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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    public Result<FriendRequestDTO> createRequest(@RequestBody FriendRequestCreateDTO dto,
                                                  HttpServletRequest request) {
        return Result.success(friendService.createRequest(
                resolveUserId(request, dto.getUserId()), dto.getTargetUserId()));
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
    public Result<PageResult<FriendDTO>> list(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        return Result.success(friendService.listFriends(resolveUserId(request, userId), page, size));
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

    /**
     * 本控制器局部异常处理：1001 → HTTP 401、1002 → HTTP 403（规范以 HTTP 状态为准）；
     * 其余业务码（5001/2002 等）保持全局口径（HTTP 200 + body code）。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletResponse response) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        if (e.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } else if (e.getCode() == ResultCode.FORBIDDEN.getCode()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
        }
        return Result.failure(e.getCode(), e.getMessage());
    }
}
