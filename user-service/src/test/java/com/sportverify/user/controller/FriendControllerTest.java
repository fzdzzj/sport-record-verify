package com.sportverify.user.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.entity.FriendRequest;
import com.sportverify.user.enums.FriendRequestStatus;
import com.sportverify.user.mapper.FriendRequestMapper;
import com.sportverify.user.mapper.FriendshipMapper;
import com.sportverify.user.mapper.UserMapper;
import com.sportverify.user.service.FriendService;
import com.sportverify.user.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 好友控制器单元测试（TASK-235 add-friend-requests：GET /api/friends/requests 待处理申请列表）。
 *
 * <p>本类以<b>真实 FriendService</b>（Mockito 打桩 Mapper/RedissonClient/NotificationService）装配
 * 控制器——既覆盖控制器身份认定（ADR-0007）两态（auth=false 降级显式携带 / true 以网关注入
 * X-User-Id 为准、显式不一致 → 越权），又覆盖服务层 {@code listPendingRequests} 的判别式
 * （to_user=本人、status=PENDING(0)、createdAt 倒序）。FriendServiceTest 不在 TASK-235 白名单内，
 * 故 TASK-235 新增服务方法的单测落在本白名单文件。</p>
 */
class FriendControllerTest {

    private FriendRequestMapper requestMapper;
    private FriendService service;
    private FriendController controller;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        requestMapper = mock(FriendRequestMapper.class);
        FriendshipMapper friendshipMapper = mock(FriendshipMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        NotificationService notificationService = mock(NotificationService.class);
        service = new FriendService(requestMapper, friendshipMapper, userMapper,
                redissonClient, notificationService);
        controller = new FriendController(service);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        // MP 单测惯用初始化：使 LambdaQuery wrapper 的 lambda 列名可渲染（与 FriendServiceTest:86 同源）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), FriendRequest.class);
    }

    private FriendRequest pendingRequest(long id, long fromUser, long toUser) {
        FriendRequest r = new FriendRequest();
        r.setId(id);
        r.setFromUser(fromUser);
        r.setToUser(toUser);
        r.setStatus(FriendRequestStatus.PENDING.getCode());
        r.setCreatedAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        return r;
    }

    private void stubPendingPage(Page<FriendRequest> page) {
        when(requestMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
    }

    // ==================== GET /api/friends/requests：待处理申请列表 ====================

    /** 降级态（auth=false）：显式携带 userId → 待处理申请列表（仅 PENDING、to_user=本人、createdAt 倒序） */
    @Test
    @SuppressWarnings("unchecked")
    void listRequests_claimsUserIdWhenAuthDisabled_filtersPending() {
        Page<FriendRequest> page = new Page<>(1, 20);
        page.setTotal(2);
        page.setRecords(List.of(pendingRequest(5L, 2001L, 1001L), pendingRequest(6L, 2002L, 1001L)));
        stubPendingPage(page);

        Result<PageResult<FriendRequestDTO>> r = controller.listRequests(1001L, 1, 20, request);

        assertEquals(ResultCode.SUCCESS.getCode(), r.getCode());
        assertEquals(2, r.getData().getTotal());
        assertEquals(2001L, r.getData().getRecords().get(0).getFromUser());
        assertEquals("PENDING", r.getData().getRecords().get(0).getStatus());

        // 判别式：查询条件 = to_user=1001（本人）AND status=PENDING(0)，createdAt 倒序
        ArgumentCaptor<LambdaQueryWrapper<FriendRequest>> wrapper =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(requestMapper).selectPage(any(Page.class), wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("to_user ="), "必须按被申请人 to_user 过滤：" + segment);
        assertTrue(segment.contains("status ="), "必须按状态过滤：" + segment);
        java.util.Collection<Object> params = wrapper.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains(1001L), "to_user 参数 = 当前登录用户，实测=" + params);
        assertTrue(params.contains(FriendRequestStatus.PENDING.getCode()),
                "status 参数恰为 PENDING(0)，实测=" + params);
        assertTrue(segment.contains("ORDER BY created_at DESC"), "按创建时间倒序：" + segment);
    }

    /** 鉴权态（auth=true）：以网关注入 X-User-Id 为准（未显式携带时）命中服务 */
    @Test
    @SuppressWarnings("unchecked")
    void listRequests_usesHeaderIdentityWhenAuthEnabled() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("42");
        stubPendingPage(new Page<>(1, 20));

        Result<PageResult<FriendRequestDTO>> r = controller.listRequests(null, 1, 20, request);

        // 真实服务委托到 requestMapper.selectPage：to_user 参数必须为网关注入的 42
        // （注意：MP 的 paramNameValuePairs 在 getSqlSegment() 渲染时才填充，须先取 segment）
        ArgumentCaptor<LambdaQueryWrapper<FriendRequest>> wrapper =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(requestMapper).selectPage(any(Page.class), wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("to_user ="), "必须按被申请人 to_user 过滤：" + segment);
        assertTrue(wrapper.getValue().getParamNameValuePairs().values().contains(42L),
                "to_user 参数 = 网关注入 X-User-Id(42)，实测="
                        + wrapper.getValue().getParamNameValuePairs().values());
    }

    /** 鉴权态：显式携带与网关注入不一致 → 越权 403，服务零触达 */
    @Test
    void listRequests_claimedMismatchHeader_throwsForbidden() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("42");

        BizException e = assertThrows(BizException.class, () -> controller.listRequests(999L, 1, 20, request));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(requestMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    /** 鉴权态：缺网关 X-User-Id → 401 UNAUTHORIZED，服务零触达 */
    @Test
    void listRequests_missingHeaderWhenAuthEnabled_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> controller.listRequests(null, 1, 20, request));

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
        verify(requestMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    /** 空列表：无待处理申请 → total=0、records 空，不报错 */
    @Test
    void listRequests_emptyPage_passesThrough() {
        stubPendingPage(new Page<>(1, 20)); // total 缺省 0、records 空

        Result<PageResult<FriendRequestDTO>> r = controller.listRequests(1001L, 1, 20, request);

        assertEquals(0, r.getData().getTotal());
        assertTrue(r.getData().getRecords().isEmpty());
    }
}