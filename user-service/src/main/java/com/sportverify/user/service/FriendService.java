package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.api.user.dto.FriendRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.entity.FriendRequest;
import com.sportverify.user.entity.Friendship;
import com.sportverify.user.entity.User;
import com.sportverify.user.enums.FriendRequestStatus;
import com.sportverify.user.mapper.FriendRequestMapper;
import com.sportverify.user.mapper.FriendshipMapper;
import com.sportverify.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 好友服务（规范差异：好友申请创建 / 申请幂等去重 / 申请状态机 /
 * 好友关系规范化存储 / 并发互加唯一性 / 好友列表）。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>锁</b>：申请建单与同意/拒绝流转均以 Redisson 可重入锁
 *       {@code lock:friend:{low}_{high}}（min/max 归一，与 friendship 存储键一致，
 *       A→B 与 B→A 拿到同一把锁）串行化「检查-建单」，保证并发互加只产生一条关系
 *       （审批版 §7.5，规范差异「并发互加唯一性」）；</li>
 *   <li><b>幂等</b>：锁内同时检查同向 PENDING、反向 PENDING、已存在关系三类冲突 → 5001；</li>
 *   <li><b>流转</b>：乐观语义 {@code UPDATE ... WHERE status=PENDING}，影响 0 行报 5002；</li>
 *   <li><b>存储</b>：ACCEPTED 时写 {@code (user_low,user_high)}，唯一主键 + CHECK 兜底
 *       并发重复插入（DuplicateKeyException 按幂等成功吞掉）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FriendService {

    private final FriendRequestMapper friendRequestMapper;
    private final FriendshipMapper friendshipMapper;
    private final UserMapper userMapper;
    private final RedissonClient redissonClient;

    /** 锁等待上限（秒） */
    private static final long LOCK_WAIT_SECONDS = 5;
    /** 锁持有上限（秒，审批版 §7.5：好友并发互加 10s 可重入锁） */
    private static final long LOCK_LEASE_SECONDS = 10;

    // ==================== 好友申请创建 + 幂等去重 ====================

    /**
     * 发起好友申请（规范差异「好友申请创建」）。
     *
     * <p>顺序：入参校验（自申请拦截）→ 目标用户存在性（2002）→ 锁内「检查-建单」：
     * 同向 PENDING / 反向 PENDING / 已存在关系任一命中 → 5001 且不建单；否则落一条
     * status=PENDING 的 friend_request。</p>
     */
    public FriendRequestDTO createRequest(Long userId, Long targetUserId) {
        // —— 入参校验（userId 已从网关注入的 X-User-Id 认定身份，见 ADR-0007；自申请属非法参数 → 400）
        if (userId == null || targetUserId == null) {
            throw new IllegalArgumentException("userId 与 targetUserId 不能为空");
        }
        if (userId.equals(targetUserId)) {
            throw new IllegalArgumentException("不能添加自己为好友");
        }

        // —— 目标用户存在性（规范差异「目标用户不存在」→ 2002，且不建单）
        if (userMapper.selectById(targetUserId) == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        // —— 锁键归一：min/max 与 friendship 存储键一致，A→B 与 B→A 竞争同一把锁
        long low = Math.min(userId, targetUserId);
        long high = Math.max(userId, targetUserId);
        RLock lock = redissonClient.getLock("lock:friend:" + low + "_" + high);
        boolean locked = tryLock(lock);
        if (!locked) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "获取好友锁超时，请稍后重试");
        }
        try {
            // —— 锁内「检查-建单」原子化（规范差异「申请幂等去重」）
            // 1) 同向 PENDING 重复申请 → 5001（或返回原申请单，本实现统一 5001）
            FriendRequest sameDir = friendRequestMapper.selectOne(new LambdaQueryWrapper<FriendRequest>()
                    .eq(FriendRequest::getFromUser, userId)
                    .eq(FriendRequest::getToUser, targetUserId)
                    .eq(FriendRequest::getStatus, FriendRequestStatus.PENDING.getCode())
                    .last("LIMIT 1"));
            if (sameDir != null) {
                log.info("同向重复申请被拒：from={}, to={}, 既有申请Id={}", userId, targetUserId, sameDir.getId());
                throw new BizException(ResultCode.FRIEND_REQUEST_EXISTS, "重复申请：已有同向 PENDING 申请");
            }
            // 2) 反向 PENDING 已存在（对方已发起）→ 5001
            FriendRequest reverse = friendRequestMapper.selectOne(new LambdaQueryWrapper<FriendRequest>()
                    .eq(FriendRequest::getFromUser, targetUserId)
                    .eq(FriendRequest::getToUser, userId)
                    .eq(FriendRequest::getStatus, FriendRequestStatus.PENDING.getCode())
                    .last("LIMIT 1"));
            if (reverse != null) {
                log.info("反向 PENDING 已存在被拒：from={}, to={}", targetUserId, userId);
                throw new BizException(ResultCode.FRIEND_REQUEST_EXISTS, "对方已向你发起申请，请先处理对方申请");
            }
            // 3) 已存在好友关系 → 5001
            Long relationCount = friendshipMapper.selectCount(new LambdaQueryWrapper<Friendship>()
                    .eq(Friendship::getUserLow, low)
                    .eq(Friendship::getUserHigh, high));
            if (relationCount != null && relationCount > 0) {
                log.info("已存在好友关系，申请被拒：{} <-> {}", low, high);
                throw new BizException(ResultCode.FRIEND_REQUEST_EXISTS, "你们已是好友");
            }

            // —— 建单（status=PENDING，规范差异「好友申请创建」）
            LocalDateTime now = LocalDateTime.now();
            FriendRequest request = new FriendRequest();
            request.setFromUser(userId);
            request.setToUser(targetUserId);
            request.setStatus(FriendRequestStatus.PENDING.getCode());
            request.setCreatedAt(now);
            request.setUpdatedAt(now);
            friendRequestMapper.insert(request);
            log.info("创建好友申请：requestId={}, from={}, to={}", request.getId(), userId, targetUserId);
            return toDto(request);
        } finally {
            unlock(lock, locked);
        }
    }

    // ==================== 申请状态机流转 ====================

    /**
     * 同意申请（规范差异「申请状态机」）：PENDING → ACCEPTED，并落 friendship 关系。
     *
     * <p>落 friendship 用规范化键 {@code (user_low,user_high)}；若并发下关系已被另一方向写入，
     * 主键冲突抛 DuplicateKeyException → 幂等成功（规范化存储从根上消除重复行）。</p>
     *
     * <p>本地事务边界（ADR-0009）：{@code @Transactional} 覆盖「申请状态更新 + friendship 插入」两写，
     * 中间失败同进退，避免申请已 ACCEPTED 却无好友行。锁在方法内获取（锁与事务嵌套顺序为已知权衡，不借机重构）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public FriendRequestDTO accept(Long requestId) {
        FriendRequest request = friendRequestMapper.selectById(requestId);
        if (request == null) {
            throw new BizException(ResultCode.FRIEND_RELATION_NOT_FOUND, "好友申请不存在");
        }
        long low = Math.min(request.getFromUser(), request.getToUser());
        long high = Math.max(request.getFromUser(), request.getToUser());
        RLock lock = redissonClient.getLock("lock:friend:" + low + "_" + high);
        boolean locked = tryLock(lock);
        if (!locked) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "获取好友锁超时，请稍后重试");
        }
        try {
            // —— 乐观流转：仅 PENDING → ACCEPTED；影响 0 行说明申请已不存在或已处理 → 5002
            LocalDateTime now = LocalDateTime.now();
            int rows = friendRequestMapper.updateStatus(requestId,
                    FriendRequestStatus.PENDING.getCode(),
                    FriendRequestStatus.ACCEPTED.getCode(), now);
            if (rows == 0) {
                throw new BizException(ResultCode.FRIEND_RELATION_NOT_FOUND, "申请已处理，仅 PENDING 状态可流转");
            }
            // —— 落 friendship（规范化 user_low<user_high；唯一主键兜底并发重复插入）
            try {
                Friendship friendship = new Friendship();
                friendship.setUserLow(low);
                friendship.setUserHigh(high);
                friendship.setCreatedAt(now);
                friendshipMapper.insert(friendship);
            } catch (DuplicateKeyException e) {
                // 并发互加兜底：另一方向已落关系 → 幂等成功，不产生重复行
                log.info("friendship 已存在，幂等跳过：{}_{}", low, high);
            }
            request.setStatus(FriendRequestStatus.ACCEPTED.getCode());
            request.setUpdatedAt(now);
            log.info("同意好友申请：requestId={}, 关系 {}_{}", requestId, low, high);
            return toDto(request);
        } finally {
            unlock(lock, locked);
        }
    }

    /**
     * 拒绝申请（规范差异「申请状态机」）：PENDING → REJECTED，不落 friendship。
     * 非 PENDING 流转被拒（乐观语义影响 0 行）→ 5002。
     */
    public FriendRequestDTO reject(Long requestId) {
        FriendRequest request = friendRequestMapper.selectById(requestId);
        if (request == null) {
            throw new BizException(ResultCode.FRIEND_RELATION_NOT_FOUND, "好友申请不存在");
        }
        long low = Math.min(request.getFromUser(), request.getToUser());
        long high = Math.max(request.getFromUser(), request.getToUser());
        RLock lock = redissonClient.getLock("lock:friend:" + low + "_" + high);
        boolean locked = tryLock(lock);
        if (!locked) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "获取好友锁超时，请稍后重试");
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int rows = friendRequestMapper.updateStatus(requestId,
                    FriendRequestStatus.PENDING.getCode(),
                    FriendRequestStatus.REJECTED.getCode(), now);
            if (rows == 0) {
                throw new BizException(ResultCode.FRIEND_RELATION_NOT_FOUND, "申请已处理，仅 PENDING 状态可流转");
            }
            request.setStatus(FriendRequestStatus.REJECTED.getCode());
            request.setUpdatedAt(now);
            log.info("拒绝好友申请：requestId={}", requestId);
            return toDto(request);
        } finally {
            unlock(lock, locked);
        }
    }

    // ==================== 好友列表 ====================

    /**
     * 好友列表（规范差异「好友列表」）：分页返回，仅 ACCEPTED 关系
     * （friendship 表本身只承载已接受关系，天然排除 PENDING/REJECTED/CANCELLED）。
     *
     * <p>反规范化：由 {@code (user_low,user_high)} 还原对方 userId，再回表 user 取昵称。</p>
     */
    public PageResult<FriendDTO> listFriends(Long userId, long page, long size) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        Page<Friendship> result = friendshipMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getUserLow, userId)
                        .or()
                        .eq(Friendship::getUserHigh, userId)
                        .orderByDesc(Friendship::getCreatedAt));

        // —— 反规范化：对方 = 非本人那一端，批量回表取昵称
        List<Long> peerIds = result.getRecords().stream()
                .map(f -> f.getUserLow().equals(userId) ? f.getUserHigh() : f.getUserLow())
                .toList();
        Map<Long, User> userMap = peerIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(peerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        List<FriendDTO> records = result.getRecords().stream().map(f -> {
            Long peerId = f.getUserLow().equals(userId) ? f.getUserHigh() : f.getUserLow();
            User user = userMap.get(peerId);
            FriendDTO dto = new FriendDTO();
            dto.setUserId(peerId);
            dto.setNickname(user != null ? user.getNickname() : null);
            dto.setCreatedAt(f.getCreatedAt());
            return dto;
        }).toList();
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    // ==================== 内部工具 ====================

    /** 实体 → DTO（status 由 TINYINT 码转为枚举名下发，见 FriendRequestDTO 注释） */
    private FriendRequestDTO toDto(FriendRequest request) {
        FriendRequestDTO dto = new FriendRequestDTO();
        dto.setId(request.getId());
        dto.setFromUser(request.getFromUser());
        dto.setToUser(request.getToUser());
        dto.setStatus(FriendRequestStatus.nameOf(request.getStatus()));
        dto.setCreatedAt(request.getCreatedAt());
        dto.setUpdatedAt(request.getUpdatedAt());
        return dto;
    }

    /** 加锁（可重入锁，等待/租期上限见常量）；中断视为获取失败 */
    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 仅当本次调用确实持有锁时才释放（防误释放他人重入层） */
    private void unlock(RLock lock, boolean locked) {
        if (locked) {
            lock.unlock();
        }
    }
}
