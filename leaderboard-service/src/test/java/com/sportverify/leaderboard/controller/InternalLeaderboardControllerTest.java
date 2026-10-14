package com.sportverify.leaderboard.controller;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内部榜单控制器单元测试（TASK-223 D1 榜单贡献回撤端点）。
 *
 * <p>纯 Mockito：mock {@link LeaderboardService}，断言
 * {@code POST /internal/leaderboard/records/{recordId}/rollback} 的契约声明
 * 与对既有 {@code rollbackOnRejected}（乐观锁状态机，未改动）的承接透传；
 * 无贡献/已回滚的幂等语义由 LeaderboardService 自身承担（见 LeaderboardServiceTest），
 * 本测试只覆盖控制器层「不拦截、不吞异常、不额外判空」。</p>
 */
class InternalLeaderboardControllerTest {

    private LeaderboardService leaderboardService;
    private InternalLeaderboardController controller;

    @BeforeEach
    void setUp() {
        leaderboardService = mock(LeaderboardService.class);
        controller = new InternalLeaderboardController(leaderboardService);
    }

    /** R1：回撤端点声明为 POST /leaderboard/records/{recordId}/rollback（类级 /internal 前缀拼为对外完整路径） */
    @Test
    void rollbackContribution_declaresPostMappingAndPathVariable() throws NoSuchMethodException {
        java.lang.reflect.Method method =
                InternalLeaderboardController.class.getMethod("rollbackContribution", Long.class);

        PostMapping pm = method.getAnnotation(PostMapping.class);
        assertNotNull(pm, "回撤端点必须声明 @PostMapping");
        assertEquals("/leaderboard/records/{recordId}/rollback", pm.value()[0]);

        PathVariable pv = method.getParameters()[0].getAnnotation(PathVariable.class);
        assertNotNull(pv, "recordId 必须声明 @PathVariable");
        assertEquals("recordId", pv.value());
    }

    /** R1：承接既有 LeaderboardService.rollbackOnRejected（不新增回滚逻辑）并返回成功空体 */
    @Test
    void rollbackContribution_delegatesToRollbackOnRejected() {
        Result<Void> result = controller.rollbackContribution(100L);

        verify(leaderboardService).rollbackOnRejected(100L);
        assertEquals(0, result.getCode());
        assertNull(result.getData());
    }

    /** R1：无贡献锚点/已回滚时 service 幂等返回（无异常）→ 控制器仍返回成功，不额外判空拦截 */
    @Test
    void rollbackContribution_idempotentSkipStillReturnsSuccess() {
        // mock 未打桩：rollbackOnRejected 正常返回即代表 service 侧幂等跳过路径
        Result<Void> result = controller.rollbackContribution(999L);

        verify(leaderboardService).rollbackOnRejected(999L);
        assertEquals(0, result.getCode());
    }

    /** R1：锁竞争超时（IllegalStateException）由控制器透传，交由 record-service 侧 best-effort 吞并留证 */
    @Test
    void rollbackContribution_propagatesLockTimeout() {
        doThrow(new IllegalStateException("获取回滚互斥锁超时"))
                .when(leaderboardService).rollbackOnRejected(100L);

        assertThrows(IllegalStateException.class, () -> controller.rollbackContribution(100L));
    }

    /** 既有查询端点回归：GET /leaderboard 原样透传 type/userId/size（本次改动不得影响读路径） */
    @Test
    void leaderboard_delegatesToServiceTop() {
        LeaderboardDTO dto = new LeaderboardDTO();
        dto.setRank(1);
        dto.setUserId(200L);
        dto.setDistance(new BigDecimal("5.00"));
        when(leaderboardService.top("overall", null, 10)).thenReturn(List.of(dto));

        Result<List<LeaderboardDTO>> result = controller.leaderboard("overall", null, 10);

        verify(leaderboardService).top("overall", null, 10);
        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertSame(dto, result.getData().get(0));
    }
}
