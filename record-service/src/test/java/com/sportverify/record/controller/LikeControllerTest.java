package com.sportverify.record.controller;

import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.api.record.dto.LikeRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.RecordLikeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞控制器单元测试（like/unlike/getLike 透传 + 鉴权降级路由与越权防护 + 局部异常处理）。
 *
 * <p>纯 Mockito。覆盖 auth.enabled=false 显式携带、true 网关头覆盖/缺头 1001/越权 1002，
 * 以及 unlike/getLike 的 query 参数透传。</p>
 */
class LikeControllerTest {

    private RecordLikeService recordLikeService;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private LikeController controller;

    @BeforeEach
    void setUp() {
        recordLikeService = mock(RecordLikeService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        controller = new LikeController(recordLikeService);
        ReflectionTestUtils.setField(controller, "authEnabled", false);
    }

    private LikeDTO like(Long recordId, Long count) {
        LikeDTO dto = new LikeDTO();
        dto.setRecordId(recordId);
        return dto;
    }

    /** 点赞透传（auth 关） */
    @Test
    void like_delegates() {
        LikeRequestDTO dto = new LikeRequestDTO();
        dto.setUserId(100L);
        LikeDTO like = like(7L, 1L);
        when(recordLikeService.like(7L, 100L)).thenReturn(like);

        LikeDTO out = controller.like(7L, dto, request).getData();

        assertEquals(like, out);
        verify(recordLikeService).like(7L, 100L);
    }

    /** 取消点赞透传（query 参数） */
    @Test
    void unlike_delegates() {
        LikeDTO like = like(7L, 0L);
        when(recordLikeService.unlike(7L, 100L)).thenReturn(like);

        LikeDTO out = controller.unlike(7L, 100L, request).getData();

        assertEquals(like, out);
        verify(recordLikeService).unlike(7L, 100L);
    }

    /** 查询点赞状态透传 */
    @Test
    void getLike_delegates() {
        LikeDTO like = like(7L, 1L);
        when(recordLikeService.getLike(7L, 100L)).thenReturn(like);

        LikeDTO out = controller.getLike(7L, 100L, request).getData();

        assertEquals(like, out);
        verify(recordLikeService).getLike(7L, 100L);
    }

    /** auth 开、网关头存在且与显式一致 → 以网关注入为准 */
    @Test
    void like_authEnabled_headerOverrides() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        LikeRequestDTO dto = new LikeRequestDTO();
        dto.setUserId(100L);
        when(request.getHeader("X-User-Id")).thenReturn("100");
        when(recordLikeService.like(7L, 100L)).thenReturn(like(7L, 1L));

        controller.like(7L, dto, request);

        verify(recordLikeService).like(7L, 100L);
    }

    /** auth 开、显式携带与网关身份不一致 → 1002 越权 */
    @Test
    void like_authEnabled_claimedMismatch_throwsForbidden() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        LikeRequestDTO dto = new LikeRequestDTO();
        dto.setUserId(100L);
        when(request.getHeader("X-User-Id")).thenReturn("200");

        BizException e = assertThrows(BizException.class, () -> controller.like(7L, dto, request));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    /** auth 开、query 携带 userId 且与网关头一致 → 取消点赞以网关注入为准 */
    @Test
    void unlike_authEnabled_headerOverridesQuery() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("100");
        when(recordLikeService.unlike(7L, 100L)).thenReturn(like(7L, 0L));

        controller.unlike(7L, 100L, request);

        verify(recordLikeService).unlike(7L, 100L);
    }

    /** 局部异常处理：1001 → 401 */
    @Test
    void handleBizException_unauthorized_sets401() throws Exception {
        BizException e = new BizException(ResultCode.UNAUTHORIZED);

        Result<Void> out = controller.handleBizException(e, response);

        verify(response).setStatus(401);
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), out.getCode());
    }

    /** 局部异常处理：1002 → 403 */
    @Test
    void handleBizException_forbidden_sets403() throws Exception {
        BizException e = new BizException(ResultCode.FORBIDDEN);

        Result<Void> out = controller.handleBizException(e, response);

        verify(response).setStatus(403);
        assertEquals(ResultCode.FORBIDDEN.getCode(), out.getCode());
    }

    /** 局部异常处理：业务码（6001 未通过校验）→ 不设 HTTP 状态，body 透传 */
    @Test
    void handleBizException_otherCode_leavesStatusUnchanged() throws Exception {
        BizException e = new BizException(ResultCode.RECORD_NOT_PASSED);

        Result<Void> out = controller.handleBizException(e, response);

        verify(response, org.mockito.Mockito.never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
        assertEquals(ResultCode.RECORD_NOT_PASSED.getCode(), out.getCode());
    }
}