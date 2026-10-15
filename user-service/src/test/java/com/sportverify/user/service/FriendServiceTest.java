package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.entity.FriendRequest;
import com.sportverify.user.entity.Friendship;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.FriendRequestMapper;
import com.sportverify.user.mapper.FriendshipMapper;
import com.sportverify.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 好友服务单元测试（规范差异：好友申请创建 / 申请幂等去重 / 申请状态机 / 好友列表）。
 *
 * <p>用 Mockito 打桩 Mapper 与 RedissonClient（RLock 恒可获取），覆盖各业务分支；
 * 并发互加场景（T7）见 {@link FriendConcurrencyTest}（假存储 + 假锁）。</p>
 */
class FriendServiceTest {

    private FriendRequestMapper requestMapper;
    private FriendshipMapper friendshipMapper;
    private UserMapper userMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private NotificationService notificationService;
    private FriendService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        requestMapper = mock(FriendRequestMapper.class);
        friendshipMapper = mock(FriendshipMapper.class);
        userMapper = mock(UserMapper.class);
        redissonClient = mock(RedissonClient.class);
        notificationService = mock(NotificationService.class);
        lock = mock(RLock.class);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 形式规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 注意：构造参数顺序与 @RequiredArgsConstructor 字段声明顺序一致
        service = new FriendService(requestMapper, friendshipMapper, userMapper, redissonClient,
                notificationService);
        // MP 单测惯用初始化（与 NotificationServiceTest:46 / SportRecordServiceTest:762 同源）：
        // 注册 Friendship 实体列缓存，使 LambdaQuery wrapper 的 lambda 列名可渲染而不需真 Spring 上下文
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Friendship.class);
    }

    // ==================== 好友申请创建 ====================

    /** 规范差异「申请成功」：目标存在且无冲突 → 建 PENDING 单并返回 {id, fromUser, toUser, status:PENDING} */
    @Test
    void createRequest_success() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        when(friendshipMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.insert(ArgumentMatchers.<FriendRequest>any())).thenAnswer(inv -> {
            FriendRequest r = inv.getArgument(0);
            r.setId(5L);
            return 1;
        });

        FriendRequestDTO dto = service.createRequest(1001L, 1002L);

        assertEquals(5L, dto.getId());
        assertEquals(1001L, dto.getFromUser());
        assertEquals(1002L, dto.getToUser());
        assertEquals("PENDING", dto.getStatus());
        verify(redissonClient).getLock("lock:friend:1001_1002");
        verify(lock).unlock();
    }

    /** 规范差异「目标用户不存在」→ 2002，且不建单 */
    @Test
    void createRequest_targetNotFound() {
        when(userMapper.selectById(1002L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.createRequest(1001L, 1002L));

        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), e.getCode());
        verify(requestMapper, never()).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** 自申请拦截（tasks.json 阶段 2）：非法参数 → 400 */
    @Test
    void createRequest_selfAdd() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createRequest(1001L, 1001L));
        assertTrue(e.getMessage().contains("自己"));
        verify(requestMapper, never()).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** 入参缺失 → 非法参数 */
    @Test
    void createRequest_nullParams() {
        assertThrows(IllegalArgumentException.class, () -> service.createRequest(null, 1002L));
        assertThrows(IllegalArgumentException.class, () -> service.createRequest(1001L, null));
    }

    // ==================== 申请幂等去重 ====================

    /** 规范差异「同向重复申请」→ 5001，且不产生第二条 PENDING 单 */
    @Test
    void createRequest_sameDirectionDuplicate() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        when(requestMapper.selectOne(any())).thenReturn(pendingRequest(1L, 1001L, 1002L));

        BizException e = assertThrows(BizException.class, () -> service.createRequest(1001L, 1002L));

        assertEquals(ResultCode.FRIEND_REQUEST_EXISTS.getCode(), e.getCode());
        verify(requestMapper, never()).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** 规范差异「反向 PENDING 已存在」→ 5001（第一次同向检查放行，第二次反向检查命中） */
    @Test
    void createRequest_reversePendingExists() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        when(requestMapper.selectOne(any())).thenReturn(null, pendingRequest(1L, 1002L, 1001L));

        BizException e = assertThrows(BizException.class, () -> service.createRequest(1001L, 1002L));

        assertEquals(ResultCode.FRIEND_REQUEST_EXISTS.getCode(), e.getCode());
        verify(requestMapper, never()).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** 规范差异「已存在关系」→ 5001，且不建单 */
    @Test
    void createRequest_existingFriendship() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        when(requestMapper.selectOne(any())).thenReturn(null);
        when(friendshipMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.createRequest(1001L, 1002L));

        assertEquals(ResultCode.FRIEND_REQUEST_EXISTS.getCode(), e.getCode());
        verify(requestMapper, never()).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** TASK-243 B13：同向申请满 7 天后重新发起 → 旧申请惰性流转 CANCELLED，成功创建新单（解除同向死锁） */
    @Test
    void createRequest_whenSameDirExpired_cancelsOldAndCreatesNew() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        FriendRequest oldSame = pendingRequest(1L, 1001L, 1002L);
        oldSame.setCreatedAt(LocalDateTime.now().minusDays(10)); // 超过 7 天
        when(requestMapper.selectOne(any())).thenReturn(oldSame, null); // 同向命中过期单，反向无
        when(friendshipMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.updateStatus(eq(1L), eq(0), eq(3), any())).thenReturn(1);
        when(requestMapper.insert(ArgumentMatchers.<FriendRequest>any())).thenAnswer(inv -> {
            FriendRequest r = inv.getArgument(0);
            r.setId(9L);
            return 1;
        });

        FriendRequestDTO dto = service.createRequest(1001L, 1002L);

        assertEquals(9L, dto.getId());
        assertEquals("PENDING", dto.getStatus());
        // 旧申请 PENDING(0) → CANCELLED(3)
        verify(requestMapper).updateStatus(eq(1L), eq(0), eq(3), any());
        verify(requestMapper).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** TASK-243 B13：反向申请满 7 天后发起申请 → 旧反向申请惰性流转 CANCELLED，成功创建新单（过期反向不阻断正向） */
    @Test
    void createRequest_whenReverseDirExpired_cancelsReverseAndCreatesNew() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        FriendRequest oldReverse = pendingRequest(2L, 1002L, 1001L);
        oldReverse.setCreatedAt(LocalDateTime.now().minusDays(9)); // 超过 7 天
        when(requestMapper.selectOne(any())).thenReturn(null, oldReverse); // 同向无，反向命中过期单
        when(friendshipMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.updateStatus(eq(2L), eq(0), eq(3), any())).thenReturn(1);
        when(requestMapper.insert(ArgumentMatchers.<FriendRequest>any())).thenAnswer(inv -> {
            FriendRequest r = inv.getArgument(0);
            r.setId(10L);
            return 1;
        });

        FriendRequestDTO dto = service.createRequest(1001L, 1002L);

        assertEquals(10L, dto.getId());
        assertEquals("PENDING", dto.getStatus());
        // 旧反向申请 PENDING(0) → CANCELLED(3)
        verify(requestMapper).updateStatus(eq(2L), eq(0), eq(3), any());
        verify(requestMapper).insert(ArgumentMatchers.<FriendRequest>any());
    }

    /** TASK-243 B13：同向申请未满 7 天仍拦截（5001），不流转不建单 */
    @Test
    void createRequest_sameDirWithinSevenDays_stillRejected() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        when(requestMapper.selectOne(any())).thenReturn(pendingRequest(1L, 1001L, 1002L)); // createdAt = now（7 天内）

        BizException e = assertThrows(BizException.class, () -> service.createRequest(1001L, 1002L));

        assertEquals(ResultCode.FRIEND_REQUEST_EXISTS.getCode(), e.getCode());
        verify(requestMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), any());
        verify(requestMapper, never()).insert(ArgumentMatchers.<FriendRequest>any());
    }

    // ==================== 申请状态机 ====================

    /** 规范差异「同意申请」：PENDING→ACCEPTED，并落 friendship（规范化 user_low<user_high） */
    @Test
    void accept_success() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(1), any())).thenReturn(1);

        FriendRequestDTO dto = service.accept(5L, 1002L);

        assertEquals("ACCEPTED", dto.getStatus());
        ArgumentCaptor<Friendship> captor = ArgumentCaptor.forClass(Friendship.class);
        verify(friendshipMapper).insert(captor.capture());
        assertEquals(1001L, captor.getValue().getUserLow());
        assertEquals(1002L, captor.getValue().getUserHigh());
        assertTrue(captor.getValue().getUserLow() < captor.getValue().getUserHigh(),
                "friendship 必须强制 user_low < user_high");
        // TASK-182：accept 事务内写好友通过通知（收件人=申请人，dedup_key=FRIEND_ACCEPTED:{requestId}）
        verify(notificationService).createNotification(1001L, "FRIEND_ACCEPTED", 5L,
                "好友申请已被通过", null, "FRIEND_ACCEPTED:5");
    }

    /** 申请不存在 → 5002，不写关系 */
    @Test
    void accept_requestNotFound() {
        when(requestMapper.selectById(5L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.accept(5L, null));

        assertEquals(ResultCode.FRIEND_RELATION_NOT_FOUND.getCode(), e.getCode());
        verify(friendshipMapper, never()).insert(ArgumentMatchers.<Friendship>any());
    }

    /** 规范差异「非 PENDING 流转被拒」：乐观更新影响 0 行 → 5002 */
    @Test
    void accept_alreadyProcessed() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.accept(5L, 1002L));

        assertEquals(ResultCode.FRIEND_RELATION_NOT_FOUND.getCode(), e.getCode());
        verify(friendshipMapper, never()).insert(ArgumentMatchers.<Friendship>any());
    }

    /** 并发互加兜底：friendship 主键冲突（DuplicateKeyException）→ 幂等成功，不中断流转 */
    @Test
    void accept_duplicateFriendshipInsertIgnored() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);
        doThrow(new DuplicateKeyException("duplicate key 1001_1002"))
                .when(friendshipMapper).insert(ArgumentMatchers.<Friendship>any());

        FriendRequestDTO dto = service.accept(5L, 1002L);

        assertEquals("ACCEPTED", dto.getStatus());
        // 关系冲突吞掉仍是幂等成功，好友通过通知照常写（同事务）
        verify(notificationService).createNotification(eq(1001L), eq("FRIEND_ACCEPTED"), eq(5L),
                any(), any(), eq("FRIEND_ACCEPTED:5"));
    }

    // ==================== 归属校验（TASK-204） ====================

    /** 红 R2a：accept caller 非空且非申请 to_user（to_user=1002L）→ 403，不流转不写关系 */
    @Test
    void accept_callerMismatch_throwsForbidden() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));

        BizException e = assertThrows(BizException.class, () -> service.accept(5L, 1003L));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(friendshipMapper, never()).insert(ArgumentMatchers.<Friendship>any());
    }

    /** 绿 R2b：accept caller == 申请 to_user → ACCEPTED */
    @Test
    void accept_callerIsReceiver_passes() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(1), any())).thenReturn(1);
        when(friendshipMapper.insert(any(Friendship.class))).thenReturn(1);

        FriendRequestDTO dto = service.accept(5L, 1002L);

        assertEquals("ACCEPTED", dto.getStatus());
    }

    /** 绿 R2c：accept caller 为 null（鉴权关闭降级）→ 放行 */
    @Test
    void accept_callerNull_degradeAllowed() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(1), any())).thenReturn(1);
        when(friendshipMapper.insert(any(Friendship.class))).thenReturn(1);

        FriendRequestDTO dto = service.accept(5L, null);

        assertEquals("ACCEPTED", dto.getStatus());
    }

    /**
     * 锁定 accept 两写同事务（ADR-0009）：方法带 {@code @Transactional}，
     * 申请状态更新与 friendship 插入均在该方法体内顺序执行；不引入真库。
     */
    @Test
    void accept_twoWritesShareTransactionalMethod() throws Exception {
        Method m = FriendService.class.getMethod("accept", Long.class, Long.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "accept 必须标注 @Transactional，保证申请状态与好友行同进退");
        assertTrue(tx.rollbackFor().length > 0, "rollbackFor 须显式声明");

        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(1), any())).thenReturn(1);
        when(friendshipMapper.insert(any(Friendship.class))).thenReturn(1);

        FriendRequestDTO dto = service.accept(5L, 1002L);

        assertEquals("ACCEPTED", dto.getStatus());
        // 同一 accept 调用内完成两写（Mockito 单测无法验证真库回滚，用注解+调用序锁定契约）
        var inOrder = inOrder(requestMapper, friendshipMapper);
        inOrder.verify(requestMapper).updateStatus(eq(5L), eq(0), eq(1), any());
        inOrder.verify(friendshipMapper).insert(any(Friendship.class));
        // TASK-182：好友通过通知在 accept 事务内直写（同方法体，不跨服务）
        verify(notificationService).createNotification(1001L, "FRIEND_ACCEPTED", 5L,
                "好友申请已被通过", null, "FRIEND_ACCEPTED:5");
    }

    /** 规范差异「拒绝申请」：PENDING→REJECTED，不落 friendship */
    @Test
    void reject_success() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(2), any())).thenReturn(1);

        FriendRequestDTO dto = service.reject(5L, 1002L);

        assertEquals("REJECTED", dto.getStatus());
        verify(friendshipMapper, never()).insert(ArgumentMatchers.<Friendship>any());
        // TASK-182：拒绝不通知（产品裁决），reject 不得写通知
        verify(notificationService, never()).createNotification(any(), any(), any(),
                any(), any(), any());
    }

    /** 红 R3a：reject caller 非空且非申请 to_user（to_user=1002L）→ 403，不流转 */
    @Test
    void reject_callerMismatch_throwsForbidden() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));

        BizException e = assertThrows(BizException.class, () -> service.reject(5L, 1003L));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(requestMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), any());
    }

    /** 绿 R3b：reject caller == 申请 to_user → REJECTED */
    @Test
    void reject_callerIsReceiver_passes() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(2), any())).thenReturn(1);

        FriendRequestDTO dto = service.reject(5L, 1002L);

        assertEquals("REJECTED", dto.getStatus());
    }

    /** 绿 R3c：reject caller 为 null（鉴权关闭降级）→ 放行 */
    @Test
    void reject_callerNull_degradeAllowed() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(2), any())).thenReturn(1);

        FriendRequestDTO dto = service.reject(5L, null);

        assertEquals("REJECTED", dto.getStatus());
    }

    /** 拒绝非 PENDING 申请 → 5002 */
    @Test
    void reject_alreadyProcessed() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.reject(5L, 1002L));

        assertEquals(ResultCode.FRIEND_RELATION_NOT_FOUND.getCode(), e.getCode());
    }

    // ==================== 待处理申请列表与批量流转（TASK-245 B25） ====================

    /** B25 绿：listPendingRequests 按申请人批量补齐 fromNickname（userMapper 批量拉昵称） */
    @Test
    void listPendingRequests_batchEnrichesFromNickname() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), FriendRequest.class);
        Page<FriendRequest> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(pendingRequest(1L, 1001L, 2001L), pendingRequest(2L, 1002L, 2001L)));
        when(requestMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1001L, "Alice"), user(1002L, "Bob")));

        PageResult<FriendRequestDTO> result = service.listPendingRequests(2001L, 1, 20);

        assertEquals(2, result.getRecords().size());
        assertEquals("Alice", result.getRecords().get(0).getFromNickname(), "申请人昵称须被批量补齐");
        assertEquals("Bob", result.getRecords().get(1).getFromNickname());
        // 批量查询只按申请人 ID 集合走一次 userMapper.selectBatchIds
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(userMapper).selectBatchIds(idsCaptor.capture());
        assertTrue(idsCaptor.getValue().contains(1001L) && idsCaptor.getValue().contains(1002L));
    }

    /** B25 绿：listPendingRequests 申请人昵称缺失（未知用户）→ fromNickname 不出现在 DTO 中 */
    @Test
    void listPendingRequests_missingApplicant_nullNickname() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), FriendRequest.class);
        Page<FriendRequest> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(pendingRequest(1L, 9999L, 2001L)));
        when(requestMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of()); // 申请人 9999 拉不到

        PageResult<FriendRequestDTO> result = service.listPendingRequests(2001L, 1, 20);

        assertEquals(1, result.getRecords().size());
        assertNull(result.getRecords().get(0).getFromNickname());
    }

    /** B25 绿：batchAccept 逐条同意成功 → 返回成功条数=入参数量 */
    @Test
    void batchAccept_success_returnsCount() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 2001L));
        when(requestMapper.selectById(6L)).thenReturn(pendingRequest(6L, 1002L, 2001L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);

        int ok = service.batchAccept(List.of(5L, 6L), 2001L);

        assertEquals(2, ok);
        verify(friendshipMapper, times(2)).insert(any(Friendship.class));
    }

    /** B25 绿：batchAccept 其中一条已处理（5002）→ 跳过不中断，返回成功条数=1 */
    @Test
    void batchAccept_skipsAlreadyProcessed() {
        // 5 正常同意；6 已处理（updateStatus 影响 0 行 → 5002）
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 2001L));
        when(requestMapper.selectById(6L)).thenReturn(pendingRequest(6L, 1002L, 2001L));
        when(requestMapper.updateStatus(eq(5L), anyInt(), anyInt(), any())).thenReturn(1);
        when(requestMapper.updateStatus(eq(6L), anyInt(), anyInt(), any())).thenReturn(0);

        int ok = service.batchAccept(List.of(5L, 6L), 2001L);

        assertEquals(1, ok, "已处理申请跳过，不中断整批");
    }

    /** B25 红：batchAccept 含越权申请（caller 非 to_user）→ 该条 403 跳过，其余继续 */
    @Test
    void batchAccept_skipsUnauthorized() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 2001L));
        when(requestMapper.selectById(6L)).thenReturn(pendingRequest(6L, 1002L, 3001L)); // to_user=3001 ≠ caller
        when(requestMapper.updateStatus(eq(5L), anyInt(), anyInt(), any())).thenReturn(1);

        int ok = service.batchAccept(List.of(5L, 6L), 2001L);

        assertEquals(1, ok);
        verify(friendshipMapper, times(1)).insert(any(Friendship.class));
    }

    /** B25 绿：batchReject 逐条拒绝成功 → 返回成功条数，不落 friendship */
    @Test
    void batchReject_success_returnsCount() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 2001L));
        when(requestMapper.selectById(6L)).thenReturn(pendingRequest(6L, 1002L, 2001L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);

        int ok = service.batchReject(List.of(5L, 6L), 2001L);

        assertEquals(2, ok);
        verify(friendshipMapper, never()).insert(any(Friendship.class));
    }

    /** B25 绿：空列表/入参 null → 返回 0，零流转调用 */
    @Test
    void batchTransition_emptyOrNull_returnsZero() {
        assertEquals(0, service.batchAccept(null, 2001L));
        assertEquals(0, service.batchAccept(List.of(), 2001L));
        assertEquals(0, service.batchReject(null, 2001L));
        verifyNoInteractions(requestMapper);
    }

    // ==================== 好友列表 ====================

    /** 规范差异「仅返回已接受好友 + 分页正确」：反规范化还原对方 userId/nickname（双向语义） */
    @Test
    void listFriends_onlyAcceptedAndNormalized() {
        Page<Friendship> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(friendship(1001L, 1002L), friendship(1003L, 1001L)));
        when(friendshipMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1002L, "B"), user(1003L, "C")));

        PageResult<FriendDTO> result = service.listFriends(1001L, 1, 20);

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
        // 本人是 low 端：对方取 high 端
        FriendDTO first = result.getRecords().get(0);
        assertEquals(1002L, first.getUserId());
        assertEquals("B", first.getNickname());
        // 本人是 high 端：对方取 low 端（双向语义）
        FriendDTO second = result.getRecords().get(1);
        assertEquals(1003L, second.getUserId());
        assertEquals("C", second.getNickname());
    }

    /** 列表入参缺失 → 非法参数 */
    @Test
    void listFriends_nullUserId() {
        assertThrows(IllegalArgumentException.class, () -> service.listFriends(null, 1, 20));
    }

    // ==================== areFriends（TASK-221 好友对判断） ====================

    /** 绿：两点互为好友（规范化 user_low<user_high selectCount>0）→ true，且共享既有 normalized 查询 */
    @Test
    @SuppressWarnings("unchecked")
    void areFriends_friendshipExists_returnsTrue() {
        when(friendshipMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.areFriends(1001L, 1002L));

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(friendshipMapper).selectCount(wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        // 规范化存储：无论调用方向，查询都必须按 user_low=min / user_high=max 组织
        assertTrue(segment.contains("user_low =") && segment.contains("user_high ="),
                "areFriends 查询必须按 user_low/user_high 规范化过滤：" + segment);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1001L),
                "低端应为 min(1001,1002)=1001");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1002L),
                "高端应为 max(1001,1002)=1002");
    }

    /** 绿：方向不影响 —— 反向调用（1002→1001）查询同一 normalized 键 → true */
    @Test
    void areFriends_reverseDirection_normalizesToSamePair() {
        when(friendshipMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.areFriends(1002L, 1001L));
    }

    /** 红：无关系（selectCount=0）→ false */
    @Test
    void areFriends_noFriendship_returnsFalse() {
        when(friendshipMapper.selectCount(any())).thenReturn(0L);

        assertFalse(service.areFriends(1001L, 1002L));
    }

    /** 红：任一参数 null → false（不抛错，读取面把它当「非好友」，fail-safe 契约） */
    @Test
    void areFriends_nullArg_returnsFalse() {
        assertFalse(service.areFriends(null, 1002L));
        assertFalse(service.areFriends(1001L, null));
        verifyNoInteractions(friendshipMapper);
    }

    /** 绿：两点相同 → true（契约独立成立；record-service 归属短路不会走到） */
    @Test
    void areFriends_sameUser_returnsTrue() {
        assertTrue(service.areFriends(1001L, 1001L));
        verifyNoInteractions(friendshipMapper);
    }

    // ==================== 好友 ID 全量列表（TASK-222 add-friend-feed） ====================

    /** 绿 R3：listFriendIds 双向还原全部好友 ID（本人 low 端取 high、high 端取 low），不分页 */
    @Test
    void listFriendIds_bidirectionalRestore() {
        when(friendshipMapper.selectList(any())).thenReturn(java.util.Arrays.asList(
                friendship(1001L, 1002L), // 本人是 low 端 → 对方 1002
                friendship(1003L, 1001L))); // 本人是 high 端 → 对方 1003

        List<Long> ids = service.listFriendIds(1001L);

        assertEquals(2, ids.size());
        assertTrue(ids.contains(1002L), "low 端本人应取 high 端");
        assertTrue(ids.contains(1003L), "high 端本人应取 low 端");
    }

    /** 绿：好友 ID 去重（同一好友多关系只出现一次） */
    @Test
    void listFriendIds_deduplicates() {
        when(friendshipMapper.selectList(any())).thenReturn(java.util.Arrays.asList(
                friendship(1001L, 1002L),
                friendship(1001L, 1002L)));
        List<Long> ids = service.listFriendIds(1001L);
        assertEquals(1, ids.size());
        assertTrue(ids.contains(1002L));
    }

    /** 绿 fail-safe：无好友 → 空列表；入参 null → 空列表且零 mapper 访问 */
    @Test
    void listFriendIds_emptyOrNull() {
        when(friendshipMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        // 1001 非 null：selectList 恰一次（返回空 → 空结果）
        assertTrue(service.listFriendIds(1001L).isEmpty());
        verify(friendshipMapper, times(1)).selectList(any());
        // null：短路返回空，不得触碰 mapper（fail-safe 契约）
        assertTrue(service.listFriendIds(null).isEmpty());
        verify(friendshipMapper, times(1)).selectList(any());
    }

    // ==================== 锁键归一 ====================

    /** 规范差异「锁键归一一致」：A→B 与 B→A 两方向得到相同锁键 lock:friend:{low}_{high}（min/max） */
    @Test
    void lockKey_normalized_bothDirections() {
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));
        when(userMapper.selectById(1001L)).thenReturn(user(1001L, "A"));
        when(requestMapper.selectOne(any())).thenReturn(null);
        when(friendshipMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.insert(ArgumentMatchers.<FriendRequest>any())).thenReturn(1);

        service.createRequest(1001L, 1002L);
        service.createRequest(1002L, 1001L);

        verify(redissonClient, times(2)).getLock("lock:friend:1001_1002");
    }

    // ==================== 解除好友关系（TASK-242 B14） ====================

    /**
     * 绿 R1（正常解除）：规范化 (user_low,user_high) 删除行，锁键归一 lock:friend:low_high，
     * 持锁后 unlock；不因方向变化改变归一删除键。
     */
    @Test
    void deleteFriend_success() {
        service.deleteFriend(1001L, 1002L);

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(friendshipMapper).delete(wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("user_low =") && segment.contains("user_high ="),
                "deleteFriend 必须按 user_low/user_high 规范化过滤：" + segment);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1001L),
                "低端应为 min(1001,1002)=1001");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1002L),
                "高端应为 max(1001,1002)=1002");
        verify(redissonClient).getLock("lock:friend:1001_1002");
        verify(lock).unlock();
    }

    /** 绿 R1b（反向解除归一）：1002 解除 1001 仍查同一规范化键（min/max 与正向一致） */
    @Test
    void deleteFriend_reverseDirection_normalizesToSamePair() {
        service.deleteFriend(1002L, 1001L);

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(friendshipMapper).delete(wrapper.capture());
        // 先渲染 getSqlSegment 以重填参数值（MyBatis-Plus delete 执行后再取参数需经段渲染触发生成）
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("user_low =") && segment.contains("user_high ="),
                "deleteFriend 必须按 user_low/user_high 规范化过滤：" + segment);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1001L),
                "低端应为 min(1001,1002)=1001");
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(1002L),
                "高端应为 max(1001,1002)=1002");
        // 删除与申请/同意/拒绝同一把锁：lock:friend:1001_1002
        verify(redissonClient).getLock("lock:friend:1001_1002");
    }

    /** 红 R2（自删拦截）：userId == targetUserId → IllegalArgumentException，不触碰 mapper */
    @Test
    void deleteFriend_self_throwsException() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.deleteFriend(1001L, 1001L));
        assertTrue(e.getMessage().contains("自己"));
        verify(friendshipMapper, never()).delete(any());
    }

    /** 红 R2b（入参缺失）→ IllegalArgumentException，不触碰 mapper 不取锁 */
    @Test
    void deleteFriend_nullParams_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteFriend(null, 1002L));
        assertThrows(IllegalArgumentException.class, () -> service.deleteFriend(1001L, null));
        verify(friendshipMapper, never()).delete(any());
        verifyNoInteractions(redissonClient);
    }

    /**
     * 绿 R3（幂等：非好友重复解除安全）：{@link FriendshipMapper#delete} 返回 0（行不存在）
     * 时 deleteFriend 仍成功返回（不抛异常）——幂等成功，重复解除安全。
     */
    @Test
    void deleteFriend_idempotent_whenNotExist() {
        when(friendshipMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);

        service.deleteFriend(1001L, 1002L); // 不存在该关系也不抛错

        verify(friendshipMapper).delete(any(LambdaQueryWrapper.class));
        verify(lock).unlock();
    }

    // ==================== 工具 ====================

    private User user(Long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setStatus(1);
        return u;
    }

    private FriendRequest pendingRequest(Long id, Long from, Long to) {
        FriendRequest r = new FriendRequest();
        r.setId(id);
        r.setFromUser(from);
        r.setToUser(to);
        r.setStatus(0); // PENDING
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private Friendship friendship(Long low, Long high) {
        Friendship f = new Friendship();
        f.setUserLow(low);
        f.setUserHigh(high);
        f.setCreatedAt(LocalDateTime.now());
        return f;
    }
}
