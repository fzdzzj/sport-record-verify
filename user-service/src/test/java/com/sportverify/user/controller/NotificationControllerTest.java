package com.sportverify.user.controller;

import com.sportverify.api.common.PageResult;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.dto.NotificationView;
import com.sportverify.user.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知中心读取接口单元测试（TASK-182 add-notification-center）。
 *
 * <p>身份认定（ADR-0007）两态：auth.enabled=false 降级显式携带 / true 以网关注入 X-User-Id 为准、
 * 显式不一致 → 越权（安全护栏异常）；局部 {@code @ExceptionHandler(BizException)} 在纯单测下不自动
 * 托管，故越权路径断言抛异常 + 单独点测 handler 的业务码映射。纯 Mockito：服务层 mock。</p>
 */
class NotificationControllerTest {

    private NotificationService service;
    private NotificationController controller;
    private HttpServletRequest request;
    private HttpServletResponse response;

    private NotificationView view(long id) {
        NotificationView v = new NotificationView();
        v.setId(id);
        return v;
    }

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        controller = new NotificationController(service);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    /** 降级态（auth=false，默认）：显式携带 userId 生效，命中分页服务 */
    @Test
    void page_claimsUserIdWhenAuthDisabled() {
        PageResult<NotificationView> page = new PageResult<>(1L, 20L, 1L, List.of(view(9L)));
        when(service.pageNotifications(1001L, 1, 20)).thenReturn(page);

        Result<PageResult<NotificationView>> r = controller.page(1001L, 1, 20, request);

        assertEquals(ResultCode.SUCCESS.getCode(), r.getCode());
        assertEquals(1L, r.getData().getTotal());
        verify(service).pageNotifications(1001L, 1, 20);
    }

    /** 未读数：降级态显式携带 userId，命中服务 */
    @Test
    void unreadCount_claimsUserIdWhenAuthDisabled() {
        when(service.unreadCount(7L)).thenReturn(3L);

        Result<Long> r = controller.unreadCount(7L, request);

        assertEquals(3L, r.getData());
        verify(service).unreadCount(7L);
    }

    /** 全部已读：降级态显式携带 userId，透出受影响行数 */
    @Test
    void markAllRead_delegatesAndReturnsAffectedRows() {
        when(service.markAllRead(1001L)).thenReturn(4);

        Result<Integer> r = controller.markAllRead(1001L, request);

        assertEquals(4, r.getData());
        verify(service).markAllRead(1001L);
    }

    /** 单条已读（身份认定）：auth=true 以 X-User-Id 为准；本人身仍由身份确定，不信任自报 */
    @Test
    void markRead_success_usesHeaderIdentity() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("42");
        when(service.markRead(42L, 5L)).thenReturn(true);

        Result<Void> r = controller.markRead(5L, request);

        assertEquals(ResultCode.SUCCESS.getCode(), r.getCode());
        verify(service).markRead(42L, 5L);
    }

    /** 单条已读未命中（不存在/已读/非本人）→ 5003 业务码提示 */
    @Test
    void markRead_miss_returns5003() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("42");
        when(service.markRead(42L, 5L)).thenReturn(false);

        Result<Void> r = controller.markRead(5L, request);

        assertEquals(5003, r.getCode());
    }

    /** 鉴权态：显式携带与网关注入不一致 → 越权（安全护栏抛 BizException），不触达服务 */
    @Test
    void page_claimedMismatchHeader_throwsForbidden() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("42");

        assertThrows(BizException.class, () -> controller.page(999L, 1, 20, request));

        verify(service, never()).pageNotifications(anyLong(), anyLong(), anyLong());
    }

    /** 鉴权态：缺网关 X-User-Id → 401（安全护栏抛 BizException） */
    @Test
    void markAllRead_missingHeaderWhenAuthEnabled_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThrows(BizException.class, () -> controller.markAllRead(null, request));

        verify(service, never()).markAllRead(anyLong());
    }

    /** 鉴权态：以 X-User-Id 为准（未显式携带时），忽略请求体缺失 */
    @Test
    void page_usesHeaderUserIdWhenAuthEnabled() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("42");
        when(service.pageNotifications(42L, 1, 20)).thenReturn(new PageResult<>(1L, 20L, 0L, List.of()));

        controller.page(null, 1, 20, request);

        verify(service).pageNotifications(42L, 1, 20);
    }

    /** 局部异常处理映射：越权 1002 → HTTP 403 + body 1002；401 → HTTP 401 */
    @Test
    void handleBizException_mapsForbiddenAndUnauthorizedHttpStatus() {
        Result<Void> forbidden = controller.handleBizException(
                new BizException(ResultCode.FORBIDDEN, "无权限"), response);
        assertEquals(ResultCode.FORBIDDEN.getCode(), forbidden.getCode());
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(status.capture());
        assertEquals(403, status.getValue().intValue());
    }

    /** 非鉴权业务码（如 9999）保持全局口径不变：不污染 HTTP 状态 */
    @Test
    void handleBizException_otherCodesKeepsHttp200BodyOnly() {
        controller.handleBizException(new BizException(ResultCode.SYSTEM_ERROR), response);
        verify(response, never()).setStatus(eq(403));
        verify(response, never()).setStatus(eq(401));
    }
}