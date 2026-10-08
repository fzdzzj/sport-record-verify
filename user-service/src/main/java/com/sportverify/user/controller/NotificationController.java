package com.sportverify.user.controller;

import com.sportverify.api.common.PageResult;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.dto.NotificationView;
import com.sportverify.user.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心读取域对外接口（网关 /user/api/notifications/** → StripPrefix → 本控制器，
 * TASK-182 add-notification-center）。
 *
 * <p>身份认定与 FriendController 同源（ADR-0007，add-jwt-auth）：已从网关注入的
 * {@code X-User-Id} 读取收件人 userId，不再信任显式携带值；{@code app.auth.enabled=true} 时
 * 以 X-User-Id 为准（显式值不一致 → 403 越权），false（默认）时降级显式携带。所有读取均以
 * 收件人 userId 为隔离前缀，越权读他人通知一律 403（1002）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 通知分页（规范差异「通知列表」）：按 id 倒序分页返回，仅收件人本人；越权 → 403。
     */
    @GetMapping
    public Result<PageResult<NotificationView>> page(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        return Result.success(
                notificationService.pageNotifications(resolveUserId(request, userId), page, size));
    }

    /**
     * 未读数（规范差异「未读角标」）：仅收件人本人；越权 → 403。
     */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        return Result.success(
                notificationService.unreadCount(resolveUserId(request, userId)));
    }

    /**
     * 单条已读（规范差异「标注已读」）：置 is_read=1；仅收件人本人（且未读）可置，
     * 他人/已读 → 5003（操作提示非越权，具体见服务实现）。
     */
    @PatchMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable("id") Long id, HttpServletRequest request) {
        Long userId = resolveUserId(request, null);
        boolean updated = notificationService.markRead(userId, id);
        return updated ? Result.success() : Result.failure(
                5003, "通知不存在、已读或非本人不可重复置读");
    }

    /**
     * 全部已读（规范差异「全部标已读」）：仅收件人本人全部未读置读；返回本次流转条数。
     * 未传 userId 且未开启鉴权降级时视为空操作（返回 0）。
     */
    @PatchMapping("/read-all")
    public Result<Integer> markAllRead(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long resolved = resolveUserId(request, userId);
        int updated = notificationService.markAllRead(resolved);
        return Result.success(updated);
    }

    /**
     * 身份认定（数据隔离核心，与 FriendController 逐字同源，见 ADR-0007）：true 时以网关
     * X-User-Id 为准，显式携带值不一致 → 403（1002，越权）；false 时降级为显式携带值（旧行为）。
     */
    private Long resolveUserId(HttpServletRequest request, Long claimed) {
        if (authEnabled) {
            String header = request.getHeader(HEADER_USER_ID);
            if (header == null || header.isBlank()) {
                throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
            }
            Long tokenUserId = Long.parseLong(header);
            if (claimed != null && !claimed.equals(tokenUserId)) {
                // 调用方试图以他人身份读通知：不信任自报家门，越权 → 403
                log.warn("越权访问被拒：tokenUserId={}, claimed={}", tokenUserId, claimed);
                throw new BizException(ResultCode.FORBIDDEN);
            }
            return tokenUserId;
        }
        return claimed;
    }

    /**
     * 本控制器局部异常处理（与 FriendController 同源）：1002 → HTTP 403；其余业务码保持全局口径。
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