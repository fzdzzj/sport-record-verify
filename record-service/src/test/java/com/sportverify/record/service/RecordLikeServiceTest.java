package com.sportverify.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.RecordLike;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞服务单元测试（规范差异：点赞前置校验 / 点赞幂等 T9 / 计数读热写冷 /
 * 取消幂等 / 异步批量落库 / 对账纠偏）。
 *
 * <p>用 Mockito 打桩 Mapper 与 StringRedisTemplate / RedissonClient（RLock 恒可获取），
 * 覆盖各业务分支；不依赖真实 Redis/MySQL（与好友模块单测同款风格）。</p>
 */
class RecordLikeServiceTest {

    private SportRecordMapper sportRecordMapper;
    private RecordLikeMapper recordLikeMapper;
    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private ValueOperations<String, String> valueOps;
    private ListOperations<String, String> listOps;
    private RedissonClient redissonClient;
    private RLock lock;
    private RecordLikeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        recordLikeMapper = mock(RecordLikeMapper.class);
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        valueOps = mock(ValueOperations.class);
        listOps = mock(ListOperations.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);

        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForList()).thenReturn(listOps);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        service = new RecordLikeService(sportRecordMapper, recordLikeMapper, redis, redissonClient, new ObjectMapper());
    }

    // ==================== 点赞（前置校验 + 幂等 T9） ====================

    /** 规范差异「通过校验可赞」：PASSED 记录 → SADD 命中首次 → INCR + push pending */
    @Test
    void like_success_firstTime() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        when(setOps.add("like:record:1:users", "100")).thenReturn(1L);
        when(valueOps.increment("like:count:1")).thenReturn(1L);

        LikeDTO dto = service.like(1L, 100L);

        assertEquals(1L, dto.getRecordId());
        assertEquals(1L, dto.getLikeCount());
        assertEquals(true, dto.getLiked());
        verify(listOps).rightPush(eq(RecordLikeService.PENDING_QUEUE_KEY), anyString());
    }

    /** T9 重复点赞：同人同记录赞两次 → 第二次 SADD 返回 0，计数不变、不重复 push（落库仅一条） */
    @Test
    void like_duplicate_idempotent() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        // 第一次：SADD=1 → INCR → push
        when(setOps.add("like:record:1:users", "100")).thenReturn(1L);
        when(valueOps.increment("like:count:1")).thenReturn(1L);
        service.like(1L, 100L);
        // 第二次：SADD=0（已赞）→ 幂等，不 INCR、不 push
        when(setOps.add("like:record:1:users", "100")).thenReturn(0L);
        when(valueOps.get("like:count:1")).thenReturn("1");

        LikeDTO dto = service.like(1L, 100L);

        assertEquals(1L, dto.getLikeCount());   // 计数 +1 仅发生一次
        verify(valueOps, org.mockito.Mockito.times(1)).increment("like:count:1");
        verify(listOps, org.mockito.Mockito.times(1)).rightPush(anyString(), anyString());
    }

    /** 规范差异「未通过校验被拒」：REJECTED / VERIFYING / SUBMITTED → 6001，不产生点赞 */
    @Test
    void like_notPassed_rejected() {
        for (RecordStatus status : new RecordStatus[]{RecordStatus.REJECTED, RecordStatus.VERIFYING, RecordStatus.SUBMITTED}) {
            when(sportRecordMapper.selectById(1L)).thenReturn(record(status));
            BizException e = assertThrows(BizException.class, () -> service.like(1L, 100L));
            assertEquals(ResultCode.RECORD_NOT_PASSED.getCode(), e.getCode());
            verify(setOps, never()).add(anyString(), anyString());
        }
    }

    /** RE_PASSED（申诉终判改判通过）同为「通过校验」终态，可赞 */
    @Test
    void like_rePassed_allowed() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.RE_PASSED));
        when(setOps.add("like:record:1:users", "100")).thenReturn(1L);
        when(valueOps.increment("like:count:1")).thenReturn(1L);

        LikeDTO dto = service.like(1L, 100L);

        assertEquals(1L, dto.getLikeCount());
    }

    /** 记录不存在 → 3001 */
    @Test
    void like_recordNotFound() {
        when(sportRecordMapper.selectById(1L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.like(1L, 100L));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    // ==================== 取消点赞（幂等） ====================

    /** 规范差异「取消成功」：SREM=1 → DECR + push pending 删除 */
    @Test
    void unlike_success() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        when(setOps.remove("like:record:1:users", "100")).thenReturn(1L);
        when(valueOps.decrement("like:count:1")).thenReturn(0L);

        LikeDTO dto = service.unlike(1L, 100L);

        assertEquals(0L, dto.getLikeCount());
        assertEquals(false, dto.getLiked());
        verify(listOps).rightPush(eq(RecordLikeService.PENDING_QUEUE_KEY), anyString());
    }

    /** 规范差异「重复取消幂等」：SREM=0（未赞）→ 不 DECR、不 push */
    @Test
    void unlike_duplicate_idempotent() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        when(setOps.remove("like:record:1:users", "100")).thenReturn(0L);
        when(valueOps.get("like:count:1")).thenReturn("0");

        LikeDTO dto = service.unlike(1L, 100L);

        assertEquals(0L, dto.getLikeCount());
        verify(valueOps, never()).decrement(anyString());
        verify(listOps, never()).rightPush(anyString(), anyString());
    }

    /** 计数下限 0：DECR 出现负数（漂移/并发）时强制归零 */
    @Test
    void unlike_countFloorZero() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        when(setOps.remove("like:record:1:users", "100")).thenReturn(1L);
        when(valueOps.decrement("like:count:1")).thenReturn(-1L);

        LikeDTO dto = service.unlike(1L, 100L);

        assertEquals(0L, dto.getLikeCount());
        verify(valueOps).set("like:count:1", "0");
    }

    // ==================== 计数读取（读热写冷 + 兜底回填） ====================

    /** 规范差异「计数走 Redis」：计数与 liked 均命中 Redis，不碰 DB */
    @Test
    void getLike_redisHit() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        when(setOps.isMember("like:record:1:users", "100")).thenReturn(true);
        when(valueOps.get("like:count:1")).thenReturn("3");

        LikeDTO dto = service.getLike(1L, 100L);

        assertEquals(3L, dto.getLikeCount());
        assertEquals(true, dto.getLiked());
        verify(recordLikeMapper, never()).countByRecordId(anyLong());
    }

    /** 规范差异「兜底回填」：Redis 计数键缺失 → DB COUNT(*) → 回填 Redis */
    @Test
    void getLike_dbFallbackBackfill() {
        when(sportRecordMapper.selectById(1L)).thenReturn(record(RecordStatus.PASSED));
        when(setOps.isMember("like:record:1:users", "100")).thenReturn(false);
        when(valueOps.get("like:count:1")).thenReturn(null);
        when(recordLikeMapper.countByRecordId(1L)).thenReturn(5L);

        LikeDTO dto = service.getLike(1L, 100L);

        assertEquals(5L, dto.getLikeCount());
        assertEquals(false, dto.getLiked());
        verify(valueOps).set("like:count:1", "5");
    }

    // ==================== 异步批量落库（flush） ====================

    /** 规范差异「批量落库」：同键末次动作生效（like→unlike 净删、unlike→like 净插），成功后 LTRIM */
    @Test
    void flush_batch_lastWinsAndTrim() {
        List<String> ops = List.of(
                "{\"recordId\":1,\"userId\":100,\"action\":\"LIKE\"}",
                "{\"recordId\":1,\"userId\":101,\"action\":\"LIKE\"}",
                "{\"recordId\":1,\"userId\":100,\"action\":\"UNLIKE\"}");   // 覆盖 (1,100) 为净删
        when(listOps.range(RecordLikeService.PENDING_QUEUE_KEY, 0, 199)).thenReturn(ops);
        when(recordLikeMapper.batchInsertIgnore(any())).thenReturn(1);
        when(recordLikeMapper.batchDelete(any())).thenReturn(1);

        service.flushPendingLikes();

        // (1,100) 末次为 UNLIKE → 只删不插；(1,101) 只插不删
        verify(recordLikeMapper).batchInsertIgnore(org.mockito.ArgumentMatchers.<List<RecordLike>>argThat(list ->
                list.size() == 1 && list.get(0).getUserId().equals(101L)));
        verify(recordLikeMapper).batchDelete(org.mockito.ArgumentMatchers.<List<RecordLike>>argThat(list ->
                list.size() == 1 && list.get(0).getUserId().equals(100L)));
        verify(listOps).trim(RecordLikeService.PENDING_QUEUE_KEY, 3, -1);
        verify(lock).unlock();
    }

    /** 规范差异「多实例防重」：锁未获取 → 本轮跳过，不消费队列 */
    @Test
    void flush_lockNotAcquired_skip() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        service.flushPendingLikes();

        verify(listOps, never()).range(anyString(), anyLong(), anyLong());
        verify(lock, never()).unlock();
    }

    /** 落库失败 → 不 LTRIM（队列保留重试，flush 幂等） */
    @Test
    void flush_dbFailure_keepsQueue() {
        List<String> ops = List.of("{\"recordId\":1,\"userId\":100,\"action\":\"LIKE\"}");
        when(listOps.range(RecordLikeService.PENDING_QUEUE_KEY, 0, 199)).thenReturn(ops);
        when(recordLikeMapper.batchInsertIgnore(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.flushPendingLikes());

        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
    }

    // ==================== 对账纠偏（最终一致） ====================

    /** 规范差异「对账纠偏」：以 DB 行为准覆盖 Redis 计数 + 重建成员集 */
    @Test
    void reconcile_fixesRedisFromDb() {
        when(recordLikeMapper.selectDistinctRecordIds()).thenReturn(List.of(1L, 2L));
        when(recordLikeMapper.selectUserIdsByRecordId(1L)).thenReturn(List.of(100L, 101L));
        when(recordLikeMapper.selectUserIdsByRecordId(2L)).thenReturn(List.of());

        service.reconcileLikeCounts();

        verify(valueOps).set("like:count:1", "2");
        verify(valueOps).set("like:count:2", "0");
        verify(redis).delete("like:record:1:users");
        verify(setOps).add("like:record:1:users", "100", "101");
        verify(redis).delete("like:record:2:users");
        // 记录 2 无点赞行 → 只清键不重建成员集
        verify(setOps, never()).add(eq("like:record:2:users"), any(String[].class));
    }

    // ==================== 工具 ====================

    private SportRecord record(RecordStatus status) {
        SportRecord r = new SportRecord();
        r.setId(1L);
        r.setUserId(100L);
        r.setStatus(status.getCode());
        r.setVersion(0);
        return r;
    }
}
