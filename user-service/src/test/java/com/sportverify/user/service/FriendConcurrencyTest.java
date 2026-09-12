package com.sportverify.user.service;

import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.user.entity.FriendRequest;
import com.sportverify.user.entity.Friendship;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.FriendRequestMapper;
import com.sportverify.user.mapper.FriendshipMapper;
import com.sportverify.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentMatchers;

/**
 * T7 并发互加验收（tasks.json 阶段 4 / 规范差异「并发互加唯一性」）。
 *
 * <p>以「线程安全假存储 + 假 Redisson 锁（CAS 互斥，等价于 Redis 可重入锁的互斥语义）」
 * 替换 Mapper 与锁，在无中间件环境下验证：</p>
 * <ul>
 *   <li>A、B 并发互发申请 → 锁串行化「检查-建单」→ 恰好一条 PENDING 单，另一方 5001；</li>
 *   <li>两方向竞争同一把锁键 {@code lock:friend:{low}_{high}}（min/max 归一）；</li>
 *   <li>同意后 friendship 仅一条（规范化键 1001_1002），主键冲突按幂等成功吞掉。</li>
 * </ul>
 */
class FriendConcurrencyTest {

    private FriendService service;
    private final ConcurrentMap<Long, FriendRequest> requestStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Friendship> friendshipStore = new ConcurrentHashMap<>();
    private final AtomicLong requestIdSeq = new AtomicLong(1);
    private final List<String> lockKeys = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, AtomicBoolean> lockStates = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        requestStore.clear();
        friendshipStore.clear();
        lockKeys.clear();
        lockStates.clear();

        FriendRequestMapper requestMapper = mock(FriendRequestMapper.class);
        FriendshipMapper friendshipMapper = mock(FriendshipMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        RedissonClient redissonClient = mock(RedissonClient.class);

        // —— 假 RedissonClient：按锁键返回独立 RLock（CAS 互斥，自旋等待至多 5s）
        when(redissonClient.getLock(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            lockKeys.add(key);
            AtomicBoolean held = lockStates.computeIfAbsent(key, k -> new AtomicBoolean(false));
            return (RLock) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{RLock.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "tryLock":
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                while (!held.compareAndSet(false, true)) {
                                    if (System.nanoTime() > deadline) {
                                        return false;
                                    }
                                    LockSupport.parkNanos(1_000_000);
                                }
                                return true;
                            case "unlock":
                                held.set(false);
                                return null;
                            default:
                                throw new UnsupportedOperationException("测试未实现的 RLock 方法：" + method.getName());
                        }
                    });
        });

        // —— 假 Mapper：基于线程安全 Map 的最小语义（仅覆盖服务用到的路径）
        when(requestMapper.insert(ArgumentMatchers.<FriendRequest>any())).thenAnswer(inv -> {
            FriendRequest r = inv.getArgument(0);
            r.setId(requestIdSeq.getAndIncrement());
            requestStore.put(r.getId(), r);
            return 1;
        });
        // selectOne：假存储中存在「涉及 1001↔1002 的任一 PENDING 申请」即命中
        // （同向/反向在本次断言中等价：无论哪次检查命中，结果都是 5001 不建单）
        when(requestMapper.selectOne(any())).thenAnswer(inv -> requestStore.values().stream()
                .filter(r -> r.getStatus() != null && r.getStatus() == 0)
                .filter(r -> isPair(r, 1001L, 1002L))
                .findFirst().orElse(null));
        when(requestMapper.selectById(anyLong())).thenAnswer(inv -> requestStore.get(inv.getArgument(0)));
        // updateStatus：乐观语义——仅 PENDING(0) 可流转，否则影响 0 行
        when(requestMapper.updateStatus(any(), any(), any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Integer toStatus = inv.getArgument(2);
            FriendRequest r = requestStore.get(id);
            if (r == null || r.getStatus() != 0) {
                return 0;
            }
            r.setStatus(toStatus);
            return 1;
        });

        when(friendshipMapper.selectCount(any())).thenAnswer(inv ->
                friendshipStore.containsKey("1001_1002") ? 1L : 0L);
        // insert：规范化主键冲突 → DuplicateKeyException（模拟数据库唯一主键兜底）
        when(friendshipMapper.insert(ArgumentMatchers.<Friendship>any())).thenAnswer(inv -> {
            Friendship f = inv.getArgument(0);
            String key = f.getUserLow() + "_" + f.getUserHigh();
            Friendship prev = friendshipStore.putIfAbsent(key, f);
            if (prev != null) {
                throw new DuplicateKeyException("duplicate key: " + key);
            }
            return 1;
        });

        when(userMapper.selectById(1001L)).thenReturn(user(1001L, "A"));
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "B"));

        service = new FriendService(requestMapper, friendshipMapper, userMapper, redissonClient);
    }

    /** T7：A、B 同时互发申请 → 仅一条 PENDING 单、一条 friendship；两方向同一锁键 */
    @Test
    void t7_concurrentMutualAdd_singleRequest_singleFriendship() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2); // 两线程同时起跑
        List<Callable<FriendRequestDTO>> tasks = List.of(
                () -> {
                    barrier.await();
                    return service.createRequest(1001L, 1002L);
                },
                () -> {
                    barrier.await();
                    return service.createRequest(1002L, 1001L);
                });
        List<Future<FriendRequestDTO>> futures = pool.invokeAll(tasks);

        // —— 恰好一个申请成功（PENDING），另一个被幂等去重拒绝（5001）
        int success = 0;
        int conflict = 0;
        for (Future<FriendRequestDTO> f : futures) {
            try {
                FriendRequestDTO dto = f.get();
                success++;
                assertEquals("PENDING", dto.getStatus());
            } catch (ExecutionException e) {
                assertInstanceOf(BizException.class, e.getCause());
                assertEquals(5001, ((BizException) e.getCause()).getCode());
                conflict++;
            }
        }
        assertEquals(1, success, "并发互加应恰好一个申请成功");
        assertEquals(1, conflict, "并发互加应恰好一个申请被 5001 拒绝");
        assertEquals(1, requestStore.size(), "并发互加不应产生第二条 PENDING 单");

        // —— 规范差异「锁键归一一致」：两方向拿到同一把锁键 lock:friend:1001_1002
        assertEquals(List.of("lock:friend:1001_1002", "lock:friend:1001_1002"), lockKeys);

        // —— 目标用户同意 → friendship 仅一条（规范化键 1001_1002）
        FriendRequest only = requestStore.values().iterator().next();
        FriendRequestDTO accepted = service.accept(only.getId());
        assertEquals("ACCEPTED", accepted.getStatus());
        assertEquals(1, friendshipStore.size(), "并发互加最终只产生一条 friendship");
        assertTrue(friendshipStore.containsKey("1001_1002"), "关系必须以 user_low<user_high 规范化存储");

        pool.shutdownNow();
    }

    // ==================== 工具 ====================

    private boolean isPair(FriendRequest r, long a, long b) {
        return (r.getFromUser() == a && r.getToUser() == b)
                || (r.getFromUser() == b && r.getToUser() == a);
    }

    private User user(Long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setStatus(1);
        return u;
    }
}
