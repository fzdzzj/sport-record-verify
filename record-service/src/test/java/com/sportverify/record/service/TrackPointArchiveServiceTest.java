package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.entity.TrackPointArchive;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轨迹点归档服务单元测试（课题 3 冷热分离：候选扫描谓词与批次上限 /
 * 调度锁互斥与锁服务异常降级 / 幂等三步迁移与崩溃重入自愈 / 迁移计数器）。
 *
 * <p>纯 Mockito：Mapper / RedissonClient 全部 mock，MeterRegistry 用真实
 * {@code SimpleMeterRegistry}（禁 mock MeterRegistry）；不依赖 MySQL / Redis。
 * MP wrapper 列名渲染用 {@code TableInfoHelper.initTableInfo} 单测惯用法。</p>
 */
class TrackPointArchiveServiceTest {

    private SportRecordMapper sportRecordMapper;
    private TrackPointMapper trackPointMapper;
    private TrackPointArchiveMapper trackPointArchiveMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private SimpleMeterRegistry meterRegistry;
    private TrackPointArchiveService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        trackPointMapper = mock(TrackPointMapper.class);
        trackPointArchiveMapper = mock(TrackPointArchiveMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 真实 SimpleMeterRegistry（禁 mock MeterRegistry）：counter 读数用例直接取注册表实测
        meterRegistry = new SimpleMeterRegistry();
        service = new TrackPointArchiveService(sportRecordMapper, trackPointMapper,
                trackPointArchiveMapper, redissonClient, meterRegistry);
        // 无 Spring：@Value 不生效；setField 模拟产品默认（cold-days 90 / batch-records 20）
        ReflectionTestUtils.setField(service, "coldDays", 90L);
        ReflectionTestUtils.setField(service, "batchRecords", 20);
        // MP 单测惯用初始化：注册三实体列缓存，使 wrapper 的 lambda 列名可渲染
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPoint.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPointArchive.class);
    }

    // ==================== 候选扫描（谓词完备 + 批次上限） ====================

    /**
     * 判别式：候选 SQL 段含 archived、status IN、end_time、ORDER BY id、LIMIT（批上限 20）；
     * 扫描结果逐条走 archiveOne（两条候选 → 批插/删热各 2 次，计数器 2）。
     */
    @Test
    void sweepOnce_scanPredicate_andBatchLimit() {
        SportRecord c1 = record(11L, 100L);
        SportRecord c2 = record(12L, 101L);
        when(sportRecordMapper.selectList(any())).thenReturn(List.of(c1, c2));
        when(trackPointMapper.selectList(any())).thenReturn(points(5L, 100L, 2));
        when(trackPointArchiveMapper.selectCount(any())).thenReturn(0L);
        when(sportRecordMapper.update(any(), any())).thenReturn(1);

        service.sweepOnce();

        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sportRecordMapper, times(1)).selectList(wrapper.capture());
        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.contains("archived"), "候选扫描必须限定未归档：" + segment);
        assertTrue(segment.contains("status IN"), "候选扫描必须限定终态集合：" + segment);
        assertTrue(segment.contains("end_time"), "候选扫描必须限定 end_time 非空且早于冷边界：" + segment);
        assertTrue(segment.contains("ORDER BY id"), "扫描必须按 id 升序稳定取批：" + segment);
        assertTrue(segment.contains("LIMIT 20"), "单轮至多处理批次上限 20 条：" + segment);
        // 扫描结果逐条走 archiveOne：2 条候选 → 批插与删热各 2 次、计数器 2
        verify(trackPointArchiveMapper, times(2)).insertBatch(any());
        verify(trackPointMapper, times(2)).delete(any());
        verify(sportRecordMapper, times(2)).update(any(), any());
        assertEquals(2.0, meterRegistry.get("track.archive.migrated.records").counter().count(), 0.0001);
    }

    // ==================== 调度互斥（锁未获 / 锁服务异常） ====================

    /** 判别式：tryLock 未获锁 → 本轮跳过，不扫描、不释放锁（多实例至多一个执行者） */
    @Test
    void archiveSweep_lockNotAcquired_skipsRound() {
        doReturn(false).when(lock).tryLock();

        service.archiveSweep();

        verify(sportRecordMapper, never()).selectList(any());
        verify(trackPointMapper, never()).selectList(any());
        verify(lock, never()).unlock();
    }

    /** 判别式：getLock 抛 RuntimeException → 跳过本轮、不扫描、不向上抛（可用性优先） */
    @Test
    void archiveSweep_redissonError_skipsRound() {
        when(redissonClient.getLock(anyString())).thenThrow(new RuntimeException("redisson down"));

        service.archiveSweep();

        verify(sportRecordMapper, never()).selectList(any());
        verify(lock, never()).unlock();
    }

    // ==================== 幂等三步迁移（崩溃重入自愈） ====================

    /** 判别式：热表 2 点 + 归档 count 0 → 批插恰 1 次且 id 为热表原值、删热 1 次、置标志 1 次、counter=1 */
    @Test
    void archiveOne_migratesPoints_idPreserved() {
        SportRecord r = record(5L, 100L);
        when(trackPointMapper.selectList(any())).thenReturn(points(5L, 100L, 2));
        when(trackPointArchiveMapper.selectCount(any())).thenReturn(0L);
        when(sportRecordMapper.update(any(), any())).thenReturn(1);

        service.archiveOne(r);

        verify(trackPointArchiveMapper).insertBatch(argThat(batch ->
                batch != null && batch.size() == 2
                        && Long.valueOf(1000L).equals(batch.get(0).getId())
                        && Long.valueOf(1001L).equals(batch.get(1).getId())
                        && Long.valueOf(5L).equals(batch.get(0).getRecordId())
                        && Long.valueOf(100L).equals(batch.get(0).getUserId())
                        && Integer.valueOf(0).equals(batch.get(0).getSeq())
                        && Integer.valueOf(1).equals(batch.get(1).getSeq())));
        verify(trackPointMapper, times(1)).delete(any());
        verify(sportRecordMapper, times(1)).update(any(), any());
        assertEquals(1.0, meterRegistry.get("track.archive.migrated.records").counter().count(), 0.0001);
    }

    /** 判别式（崩溃重入：批插后未删热）：归档 count 2 → 不重复插入、补删热、置标志、counter=1 */
    @Test
    void archiveOne_reentry_afterInsertBeforeDelete() {
        SportRecord r = record(5L, 100L);
        when(trackPointMapper.selectList(any())).thenReturn(points(5L, 100L, 2));
        when(trackPointArchiveMapper.selectCount(any())).thenReturn(2L);
        when(sportRecordMapper.update(any(), any())).thenReturn(1);

        service.archiveOne(r);

        verify(trackPointArchiveMapper, never()).insertBatch(any());
        verify(trackPointMapper, times(1)).delete(any());
        verify(sportRecordMapper, times(1)).update(any(), any());
        assertEquals(1.0, meterRegistry.get("track.archive.migrated.records").counter().count(), 0.0001);
    }

    /** 判别式（崩溃重入：删热后未置标志）：热 0 点 + 归档 count 2 → 插删均 never、仅置标志、counter=1 */
    @Test
    void archiveOne_reentry_afterDeleteBeforeFlag() {
        SportRecord r = record(5L, 100L);
        when(trackPointMapper.selectList(any())).thenReturn(List.of());
        when(trackPointArchiveMapper.selectCount(any())).thenReturn(2L);
        when(sportRecordMapper.update(any(), any())).thenReturn(1);

        service.archiveOne(r);

        verify(trackPointArchiveMapper, never()).insertBatch(any());
        verify(trackPointMapper, never()).delete(any());
        verify(sportRecordMapper, times(1)).update(any(), any());
        assertEquals(1.0, meterRegistry.get("track.archive.migrated.records").counter().count(), 0.0001);
    }

    /** 判别式（零轨迹点记录）：热 0 + 归档 0 → 插删均 never、直接置标志、counter=1 */
    @Test
    void archiveOne_emptyPoints_marksFlagDirectly() {
        SportRecord r = record(5L, 100L);
        when(trackPointMapper.selectList(any())).thenReturn(List.of());
        when(trackPointArchiveMapper.selectCount(any())).thenReturn(0L);
        when(sportRecordMapper.update(any(), any())).thenReturn(1);

        service.archiveOne(r);

        verify(trackPointArchiveMapper, never()).insertBatch(any());
        verify(trackPointMapper, never()).delete(any());
        verify(sportRecordMapper, times(1)).update(any(), any());
        assertEquals(1.0, meterRegistry.get("track.archive.migrated.records").counter().count(), 0.0001);
    }

    /** 判别式：置标志 update 返回 0（并发竞态丢失）→ counter 不递增（防重复计数） */
    @Test
    void archiveOne_markArchivedLostRace_counterNotIncremented() {
        SportRecord r = record(5L, 100L);
        when(trackPointMapper.selectList(any())).thenReturn(points(5L, 100L, 2));
        when(trackPointArchiveMapper.selectCount(any())).thenReturn(0L);
        when(sportRecordMapper.update(any(), any())).thenReturn(0);

        service.archiveOne(r);

        verify(trackPointArchiveMapper, times(1)).insertBatch(any());
        verify(trackPointMapper, times(1)).delete(any());
        verify(sportRecordMapper, times(1)).update(any(), any());
        assertEquals(0.0, meterRegistry.get("track.archive.migrated.records").counter().count(), 0.0001);
    }

    // ==================== 工具 ====================

    /** 候选/被归档记录（终态 + end_time 早于冷边界的代表形态） */
    private SportRecord record(Long id, Long userId) {
        SportRecord r = new SportRecord();
        r.setId(id);
        r.setUserId(userId);
        r.setStatus(RecordStatus.PASSED.getCode());
        r.setEndTime(LocalDateTime.now().minusDays(100));
        return r;
    }

    /** 构造热表轨迹点（id 自 1000 递增，用于断言迁移保留原 id） */
    private List<TrackPoint> points(Long recordId, Long userId, int n) {
        List<TrackPoint> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TrackPoint p = new TrackPoint();
            p.setId(1000L + i);
            p.setRecordId(recordId);
            p.setUserId(userId);
            p.setSeq(i);
            p.setLat(new BigDecimal("31.2300"));
            p.setLng(new BigDecimal("121.4737"));
            p.setTs(1_700_000_000_000L + i);
            p.setSpeed(new BigDecimal("1.5"));
            list.add(p);
        }
        return list;
    }
}
