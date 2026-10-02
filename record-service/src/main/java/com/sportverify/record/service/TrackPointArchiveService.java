package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.entity.TrackPointArchive;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 轨迹点冷热分离归档任务（课题 3）。
 *
 * <p>候选边界：终态（PASSED/REJECTED/RE_PASSED/RE_CONFIRMED）且 end_time 早于
 * 冷边界（默认 90 天）且未归档。verify 判定链路前置为 VERIFYING 活跃态，
 * 恒热数据，永不与归档冲突。</p>
 *
 * <p>迁移幂等三步（无事务依赖，崩溃重入自愈）：① 归档表已有该记录数据则
 * 跳过插入；② 批插归档表（保留原雪花 id）；③ 删热表后事务外置
 * archived=1。正确性以幂等为主（同类内自调用的 @Transactional 代理不生效，
 * 不依赖事务注解）。</p>
 */
@Slf4j
@Service
public class TrackPointArchiveService {

    /** 归档任务互斥锁（多实例至多一个执行者） */
    static final String ARCHIVE_LOCK_KEY = "lock:track:archive";

    /** 终态集合：判定/复判闭环，不再被判定链路触碰 */
    private static final Set<Integer> FINAL_STATUSES = Set.of(
            RecordStatus.PASSED.getCode(),
            RecordStatus.REJECTED.getCode(),
            RecordStatus.RE_PASSED.getCode(),
            RecordStatus.RE_CONFIRMED.getCode());

    private final SportRecordMapper sportRecordMapper;
    private final TrackPointMapper trackPointMapper;
    private final TrackPointArchiveMapper trackPointArchiveMapper;
    private final RedissonClient redissonClient;
    private final Counter migratedCounter;

    @Value("${track.archive.cold-days:90}")
    private long coldDays;

    @Value("${track.archive.batch-records:20}")
    private int batchRecords;

    public TrackPointArchiveService(SportRecordMapper sportRecordMapper,
            TrackPointMapper trackPointMapper,
            TrackPointArchiveMapper trackPointArchiveMapper,
            RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.sportRecordMapper = sportRecordMapper;
        this.trackPointMapper = trackPointMapper;
        this.trackPointArchiveMapper = trackPointArchiveMapper;
        this.redissonClient = redissonClient;
        this.migratedCounter = Counter.builder("track.archive.migrated.records")
                .description("Number of records archived to track_point_archive")
                .register(meterRegistry);
    }

    /**
     * 归档扫描（默认 1h 一轮；fixedDelay 保证上一轮跑完再计时）。
     * 锁服务异常时跳过本轮（可用性优先，归档可延迟）。
     */
    @Scheduled(fixedDelayString = "${track.archive.interval-ms:3600000}")
    public void archiveSweep() {
        RLock lock = null;
        try {
            lock = redissonClient.getLock(ARCHIVE_LOCK_KEY);
        } catch (RuntimeException ex) {
            log.warn("track archive lock service error, skip round", ex);
            return;
        }
        if (!tryLockQuietly(lock)) {
            return;
        }
        try {
            sweepOnce();
        } finally {
            unlockQuietly(lock);
        }
    }

    /** 单轮扫描：候选取批后逐记录迁移，单记录失败不断轮 */
    void sweepOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(coldDays);
        List<SportRecord> candidates = sportRecordMapper.selectList(
                new LambdaQueryWrapper<SportRecord>()
                        .eq(SportRecord::getArchived, 0)
                        .in(SportRecord::getStatus, FINAL_STATUSES)
                        .isNotNull(SportRecord::getEndTime)
                        .lt(SportRecord::getEndTime, cutoff)
                        .orderByAsc(SportRecord::getId)
                        .last("LIMIT " + batchRecords));
        for (SportRecord record : candidates) {
            try {
                archiveOne(record);
            } catch (RuntimeException ex) {
                log.warn("archive record failed, continue next: recordId={}",
                        record.getId(), ex);
            }
        }
    }

    /**
     * 单记录幂等迁移：热表读点 →（归档表已有则跳过插入）→ 批插 → 删热表 → 置标志。
     * 零点记录直接置标志；置标志走 archived=0 条件更新，并发竞态安全。
     */
    void archiveOne(SportRecord record) {
        Long recordId = record.getId();
        Long userId = record.getUserId();
        List<TrackPoint> hotPoints = trackPointMapper.selectList(
                new LambdaQueryWrapper<TrackPoint>()
                        .eq(TrackPoint::getRecordId, recordId)
                        .eq(TrackPoint::getUserId, userId)
                        .orderByAsc(TrackPoint::getSeq));
        Long archivedCount = trackPointArchiveMapper.selectCount(
                new LambdaQueryWrapper<TrackPointArchive>()
                        .eq(TrackPointArchive::getRecordId, recordId)
                        .eq(TrackPointArchive::getUserId, userId));
        if (hotPoints.isEmpty() && archivedCount == 0) {
            markArchived(recordId);
            return;
        }
        if (!hotPoints.isEmpty() && archivedCount == 0) {
            trackPointArchiveMapper.insertBatch(hotPoints.stream()
                    .map(this::toArchive).toList());
        }
        if (!hotPoints.isEmpty()) {
            trackPointMapper.delete(new LambdaQueryWrapper<TrackPoint>()
                    .eq(TrackPoint::getRecordId, recordId)
                    .eq(TrackPoint::getUserId, userId));
        }
        markArchived(recordId);
    }

    private TrackPointArchive toArchive(TrackPoint p) {
        TrackPointArchive a = new TrackPointArchive();
        a.setId(p.getId());
        a.setRecordId(p.getRecordId());
        a.setUserId(p.getUserId());
        a.setSeq(p.getSeq());
        a.setLat(p.getLat());
        a.setLng(p.getLng());
        a.setTs(p.getTs());
        a.setSpeed(p.getSpeed());
        return a;
    }

    /** 置归档标志（条件更新防重复置位），成功才递增迁移计数器 */
    private void markArchived(Long recordId) {
        int rows = sportRecordMapper.update(null,
                new LambdaUpdateWrapper<SportRecord>()
                        .eq(SportRecord::getId, recordId)
                        .eq(SportRecord::getArchived, 0)
                        .set(SportRecord::getArchived, 1));
        if (rows > 0) {
            migratedCounter.increment();
        }
    }

    private boolean tryLockQuietly(RLock lock) {
        try {
            return lock.tryLock();
        } catch (RuntimeException ex) {
            log.warn("track archive tryLock error, skip round", ex);
            return false;
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (RuntimeException ex) {
            log.warn("track archive unlock error", ex);
        }
    }
}
