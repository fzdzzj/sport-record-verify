package com.sportverify.user.service;

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
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private FriendService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        requestMapper = mock(FriendRequestMapper.class);
        friendshipMapper = mock(FriendshipMapper.class);
        userMapper = mock(UserMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 形式规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 注意：构造参数顺序与 @RequiredArgsConstructor 字段声明顺序一致
        service = new FriendService(requestMapper, friendshipMapper, userMapper, redissonClient);
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

    // ==================== 申请状态机 ====================

    /** 规范差异「同意申请」：PENDING→ACCEPTED，并落 friendship（规范化 user_low<user_high） */
    @Test
    void accept_success() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(1), any())).thenReturn(1);

        FriendRequestDTO dto = service.accept(5L);

        assertEquals("ACCEPTED", dto.getStatus());
        ArgumentCaptor<Friendship> captor = ArgumentCaptor.forClass(Friendship.class);
        verify(friendshipMapper).insert(captor.capture());
        assertEquals(1001L, captor.getValue().getUserLow());
        assertEquals(1002L, captor.getValue().getUserHigh());
        assertTrue(captor.getValue().getUserLow() < captor.getValue().getUserHigh(),
                "friendship 必须强制 user_low < user_high");
    }

    /** 申请不存在 → 5002，不写关系 */
    @Test
    void accept_requestNotFound() {
        when(requestMapper.selectById(5L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.accept(5L));

        assertEquals(ResultCode.FRIEND_RELATION_NOT_FOUND.getCode(), e.getCode());
        verify(friendshipMapper, never()).insert(ArgumentMatchers.<Friendship>any());
    }

    /** 规范差异「非 PENDING 流转被拒」：乐观更新影响 0 行 → 5002 */
    @Test
    void accept_alreadyProcessed() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.accept(5L));

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

        FriendRequestDTO dto = service.accept(5L);

        assertEquals("ACCEPTED", dto.getStatus());
    }

    /**
     * 锁定 accept 两写同事务（ADR-0009）：方法带 {@code @Transactional}，
     * 申请状态更新与 friendship 插入均在该方法体内顺序执行；不引入真库。
     */
    @Test
    void accept_twoWritesShareTransactionalMethod() throws Exception {
        Method m = FriendService.class.getMethod("accept", Long.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "accept 必须标注 @Transactional，保证申请状态与好友行同进退");
        assertTrue(tx.rollbackFor().length > 0, "rollbackFor 须显式声明");

        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(1), any())).thenReturn(1);
        when(friendshipMapper.insert(any(Friendship.class))).thenReturn(1);

        FriendRequestDTO dto = service.accept(5L);

        assertEquals("ACCEPTED", dto.getStatus());
        // 同一 accept 调用内完成两写（Mockito 单测无法验证真库回滚，用注解+调用序锁定契约）
        var inOrder = inOrder(requestMapper, friendshipMapper);
        inOrder.verify(requestMapper).updateStatus(eq(5L), eq(0), eq(1), any());
        inOrder.verify(friendshipMapper).insert(any(Friendship.class));
    }

    /** 规范差异「拒绝申请」：PENDING→REJECTED，不落 friendship */
    @Test
    void reject_success() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(eq(5L), eq(0), eq(2), any())).thenReturn(1);

        FriendRequestDTO dto = service.reject(5L);

        assertEquals("REJECTED", dto.getStatus());
        verify(friendshipMapper, never()).insert(ArgumentMatchers.<Friendship>any());
    }

    /** 拒绝非 PENDING 申请 → 5002 */
    @Test
    void reject_alreadyProcessed() {
        when(requestMapper.selectById(5L)).thenReturn(pendingRequest(5L, 1001L, 1002L));
        when(requestMapper.updateStatus(anyLong(), anyInt(), anyInt(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.reject(5L));

        assertEquals(ResultCode.FRIEND_RELATION_NOT_FOUND.getCode(), e.getCode());
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
