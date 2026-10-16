package com.sportverify.user.controller;

import com.sportverify.api.common.PageResult;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.dto.NotificationPreferenceView;
import com.sportverify.user.dto.NotificationView;
import com.sportverify.user.dto.UpdateNotificationPreferenceRequest;
import com.sportverify.user.service.NotificationPreferenceService;
import com.sportverify.user.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


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

    /** 通知偏好服务（TASK-187 add-notification-preference） */
    @Autowired(required = false)
    NotificationPreferenceService notificationPreferenceService;

    @Autowired
    public NotificationController(NotificationService notificationService,
                                  NotificationPreferenceService notificationPreferenceService) {
        this.notificationService = notificationService;
        this.notificationPreferenceService = notificationPreferenceService;
    }

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /** 网关注入的 role 请求头（治理面准入依据；网关鉴权分支覆盖式注入，外部伪造头被剥离） */
    static final String HEADER_ROLE = "X-Role";

    /** 治理面管理员角色值（与网关 AuthGlobalFilter.ROLE_ADMIN 同字面量） */
    static final String ROLE_ADMIN = "ADMIN";

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
     * 批量标记已读（B17 批量标记已读）：按指定 ID 集合批量流转 is_read=1 并回填 read_at，
     * 仅收件人本人（越权隔离由归属条件保证，非本人通知不被流转）；空集合或缺失视为空操作
     * （返回 0）。返回本次实际流转条数。
     */
    @PatchMapping("/batch-read")
    public Result<Integer> batchMarkRead(
            @RequestBody List<Long> ids,
            HttpServletRequest request) {
        Long resolved = resolveUserId(request, null);
        int updated = notificationService.batchMarkRead(resolved, ids);
        return Result.success(updated);
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
     * 通知偏好设置列表（TASK-187 add-notification-preference）：返回三类通知开关视图。
     * 缺行默认开启；仅收件人本人可见；越权或缺失 X-User-Id 拒绝沿既有口径。
     */
    @GetMapping("/preferences")
    public Result<List<NotificationPreferenceView>> getPreferences(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long resolved = resolveUserId(request, userId);
        return Result.success(notificationPreferenceService != null
                ? notificationPreferenceService.getPreferences(resolved)
                : List.of());
    }

    /**
     * 更新通知偏好设置（TASK-187 add-notification-preference）：行级原子 upsert。
     * 保存成功返回更新后的偏好视图列表供前端刷新。
     */
    @PutMapping("/preferences")
    public Result<List<NotificationPreferenceView>> updatePreferences(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestBody UpdateNotificationPreferenceRequest body,
            HttpServletRequest request) {
        Long resolved = resolveUserId(request, userId);
        return Result.success(notificationPreferenceService != null
                ? notificationPreferenceService.updatePreferences(resolved, body)
                : List.of());
    }


    /**
     * 清空本人已读通知（B21，DELETE /api/notifications/read）：仅删除当前登录用户
     * （X-User-Id 认定，见 ADR-0007）已读的行，未读通知不受影响；返回实际删除条数。
     */
    @DeleteMapping("/read")
    public Result<Integer> clearMyReadNotifications(HttpServletRequest request) {
        Long resolved = resolveUserId(request, null);
        return Result.success(notificationService.cleanMyReadNotifications(resolved));
    }

    /**
     * 清理过期已读通知（B21 治理面，DELETE /api/notifications/expired?days=90）：
     * 以「已读 + created_at &lt; now-days」为界分批物理删除历史已读通知；返回本批实际删除条数。
     *
     * <p><b>双层防线（TASK-251 R1/E3）</b>：本端点删除范围是<b>全平台</b>（mapper 全表 DELETE，
     * 无 user_id 归属条件），属跨用户数据治理动作，故设两层——
     * ① <b>网关前置</b>：外部路径 {@code /user/api/notifications/expired} 已列入 gateway
     * {@code app.auth.admin.paths}，非 ADMIN 在网关即 403（未登录 401），请求不落到本服务；
     * ② <b>服务层兜底</b>：本方法再读网关注入的 {@code X-Role} 判 ADMIN，非 ADMIN 或头缺失一律
     * {@link ResultCode#FORBIDDEN} 失败关闭（与 {@link #resolveUserId} 同款「只认网关注入头」认定模式，
     * 不信任自报身份）。②的存在意义是「网关配置漂移/直连端口」时不出现零鉴权清库路径——
     * 网关是唯一外部入口（ADR-0007），但防线不依赖单点配置正确。</p>
     */
    @DeleteMapping("/expired")
    public Result<Integer> cleanExpiredNotifications(
            @RequestParam(value = "days", required = false) Integer days,
            HttpServletRequest request) {
        requireAdmin(request);
        int thresholdDays = (days == null || days <= 0) ? 90 : days;
        return Result.success(notificationService.cleanExpiredNotifications(thresholdDays, 200));
    }

    /**
     * 治理面角色门（TASK-251 R1）：X-Role 非 ADMIN 或头缺失 → 403（1002）失败关闭。
     * 不读库、不看 body，纯头认定——与网关 admin.paths 判定同源（均以网关注入的 X-Role 为唯一依据）。
     */
    private void requireAdmin(HttpServletRequest request) {
        String role = request.getHeader(HEADER_ROLE);
        if (!ROLE_ADMIN.equals(role)) {
            log.warn("治理面端点越权拒绝：requiredRole=ADMIN, actualRole={}", role);
            throw new BizException(ResultCode.FORBIDDEN);
        }
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
