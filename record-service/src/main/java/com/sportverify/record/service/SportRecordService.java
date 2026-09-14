package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.RecordEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 运动记录服务（规范「轨迹提交幂等」「轨迹分片存储」「校验状态机」）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>提交：request_id 唯一键幂等 → 记录落单表 + 轨迹按 user_id%16 分片落库 → SUBMITTED→VERIFYING → 发 SUBMITTED 事件；</li>
 *   <li>状态回调：verify 判定/终判后以乐观锁驱动迁移（冲突 3003）；</li>
 *   <li>轨迹查询：先经 sport_record 解析 user_id，再按 user_id 路由单分片；</li>
 *   <li>申诉：REJECTED→APPEALING（经 verify-api 建申诉单，record_id 唯一）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SportRecordService {

    private final SportRecordMapper sportRecordMapper;
    private final TrackPointMapper trackPointMapper;
    private final RecordEventProducer recordEventProducer;
    private final VerifyApi verifyApi;
    private final VerifyDegradeService verifyDegradeService;

    /**
     * 提交运动记录。
     *
     * <p>幂等：uk_request_id 唯一键，重复提交捕获 DuplicateKeyException 后
     * 返回原记录（接口层映射为 3004 + 原结果，规范「重复提交幂等」）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RecordSubmitResultDTO submit(RecordSubmitDTO dto) {
        // 1. 幂等前置校验（唯一键冲突的兜底在 insert 捕获）；
        //    requestId 必填校验由控制器层 @Valid + DTO 注解承担，此处不再重复判空
        SportRecord existing = sportRecordMapper.selectByRequestId(dto.getRequestId());
        if (existing != null) {
            log.info("重复提交幂等返回：requestId={}, recordId={}", dto.getRequestId(), existing.getId());
            return RecordSubmitResultDTO.of(existing.getId(), existing.getRequestId(),
                    existing.getStatus(), true, "重复提交，返回原结果");
        }

        // 1.1 运动类型解析：缺省回退 RUNNING（历史默认）；枚举外取值拒绝（规范「未知类型拒绝」）
        Integer sportType = resolveSportType(dto.getSportType());

        // 2. 落 sport_record 主表（单表，SUBMITTED 初始态）
        SportRecord record = new SportRecord();
        record.setRequestId(dto.getRequestId());
        record.setUserId(dto.getUserId());
        record.setSportType(sportType);
        record.setStartTime(dto.getStartTime());
        record.setEndTime(dto.getEndTime());
        record.setDistance(dto.getDistance());
        record.setDuration(dto.getDuration());
        record.setStatus(RecordStatus.SUBMITTED.getCode());
        record.setVersion(0);
        record.setCreatedAt(LocalDateTime.now());
        try {
            sportRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一键冲突 → 返回原结果
            SportRecord origin = sportRecordMapper.selectByRequestId(dto.getRequestId());
            if (origin == null) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "幂等冲突但原记录查询失败");
            }
            return RecordSubmitResultDTO.of(origin.getId(), origin.getRequestId(),
                    origin.getStatus(), true, "重复提交，返回原结果");
        }

        // 3. 轨迹点分片落库：ShardingSphere 按 user_id 自动路由到 track_point_{user_id%16}
        //    （WHERE/INSERT 必须携带分片键 user_id；id 由 MP 雪花算法生成）
        List<TrackPointDTO> points = dto.getPoints();
        if (points != null && !points.isEmpty()) {
            if (batchInsertEnabled) {
                insertPointsBatch(record, points);
            } else {
                // 基线路径（压测优化前）：逐条 INSERT，连接占用 ≈ N×RTT，高并发下打满连接池
                for (int i = 0; i < points.size(); i++) {
                    TrackPointDTO p = points.get(i);
                    trackPointMapper.insert(toTrackPoint(record, p, i));
                }
            }
        }

        // 4. 状态机 SUBMITTED → VERIFYING（乐观锁；刚插入 version=0，冲突概率极低）
        int rows = sportRecordMapper.updateStatus(record.getId(),
                RecordStatus.SUBMITTED.getCode(), RecordStatus.VERIFYING.getCode(), 0);
        if (rows == 0) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "提交后状态迁移失败");
        }
        // 回填内存态（库内已乐观推进为 VERIFYING），使提交响应直接展示 VERIFYING
        record.setStatus(RecordStatus.VERIFYING.getCode());
        record.setVersion(1);

        // 5. 事务提交后发 SUBMITTED 事件进入校验流程（避免事务回滚导致孤儿事件）；
        //    发布失败降级为 Feign 直调触发校验（熔断保护，VerifyApiFallback 抛 4001）；
        //    直调仍失败 → 熔断降级转人工（MANUAL_REVIEW 终态），提交主链路不挂
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean sent = recordEventProducer.publishSubmitted(record.getId(), record.getUserId());
                if (!sent) {
                    log.warn("MQ 发布失败，降级 Feign 直调触发校验：recordId={}", record.getId());
                    try {
                        verifyApi.triggerVerify(record.getId());
                    } catch (Exception ex) {
                        // verify 不可用（熔断 OPEN/连接拒绝，4001）：转人工，不再无限 VERIFYING
                        verifyDegradeService.degradeToManualReview(record.getId(), ex);
                    }
                }
            }
        });
        return RecordSubmitResultDTO.of(record.getId(), record.getRequestId(),
                record.getStatus(), false, "提交成功，进入校验");
    }

    /**
     * 状态回调（verify 判定/终判后驱动迁移，规范「校验状态机」）。
     * 乐观锁并发控制：影响 0 行 → 3003；目标状态与当前一致视为幂等成功（重试场景）。
     */
    public void statusCallback(StatusCallbackDTO dto) {
        SportRecord record = sportRecordMapper.selectById(dto.getRecordId());
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (record.getStatus().equals(dto.getToStatus())) {
            log.info("状态回调幂等跳过：recordId={}, status={}", dto.getRecordId(), dto.getToStatus());
            return;
        }
        int rows = sportRecordMapper.updateStatus(dto.getRecordId(),
                dto.getFromStatus(), dto.getToStatus(), dto.getVersion());
        if (rows == 0) {
            // 并发冲突：状态或版本已变更（规范「并发冲突」场景）
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "并发冲突：记录状态或版本已变更");
        }
        log.info("状态回调成功：recordId={}, {} → {}", dto.getRecordId(), dto.getFromStatus(), dto.getToStatus());
    }

    /**
     * 提交申诉（规范「申诉提交」场景）。
     *
     * <p>顺序：先经 verify-api 建申诉单（verify_db，record_id 唯一、重复幂等），
     * 再乐观锁迁移 REJECTED→APPEALING；若迁移影响 0 行但记录已 APPEALING，
     * 视为并发重复申诉（幂等成功）。跨库无强一致（§5.3），由重试收敛。</p>
     */
    public AppealDTO appeal(Long recordId, Long userId, String reason) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != RecordStatus.REJECTED.getCode()) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "仅 REJECTED 记录可发起申诉");
        }
        // 建申诉单（唯一键冲突由 verify 侧幂等返回既有单）；
        // userId 以记录归属人为准（服务端权威，不信任调用方入参，且避免入参缺失导致 NOT NULL 落库失败）
        Result<AppealDTO> appealResp = verifyApi.createAppeal(new AppealCreateDTO(recordId, record.getUserId(), reason));
        if (appealResp == null || appealResp.getCode() != 0) {
            throw new BizException(ResultCode.VERIFY_SERVICE_UNAVAILABLE,
                    "创建申诉单失败：" + (appealResp == null ? "无响应" : appealResp.getMessage()));
        }
        AppealDTO appeal = appealResp.getData();
        // 乐观锁迁移
        int rows = sportRecordMapper.updateStatus(recordId,
                RecordStatus.REJECTED.getCode(), RecordStatus.APPEALING.getCode(), record.getVersion());
        if (rows == 0) {
            SportRecord cur = sportRecordMapper.selectById(recordId);
            if (cur == null || cur.getStatus() == null || cur.getStatus() != RecordStatus.APPEALING.getCode()) {
                throw new BizException(ResultCode.RECORD_STATUS_INVALID, "并发冲突：状态迁移失败");
            }
            log.info("申诉已由并发请求完成：recordId={}", recordId);
        }
        return appeal;
    }

    /**
     * 查询判定结果（数据在 verify_db，经 verify-api Feign 拉取，规范「校验状态机」）。
     */
    public VerificationResultDTO getVerifyResult(Long recordId) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        return verifyApi.getVerificationResult(recordId).getData();
    }

    /**
     * 拉取记录全部轨迹点（校验引擎输入，规范「记录本身不分片」）。
     * 先查 sport_record 得到 user_id，再按 user_id 路由对应分片（WHERE 必须携带分片键）。
     */
    public List<TrackPointDTO> listPoints(Long recordId) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        return trackPointMapper.selectList(new LambdaQueryWrapper<TrackPoint>()
                        .eq(TrackPoint::getRecordId, recordId)
                        .eq(TrackPoint::getUserId, record.getUserId()) // 分片键：单分片路由
                        .orderByAsc(TrackPoint::getSeq))
                .stream().map(this::toDto).toList();
    }

    /**
     * 轨迹分页查询（规范「分片分页查询」场景）。
     * WHERE 携带 user_id → 路由单分片；若查询条件不含分片键，ShardingSphere 广播全部分片，
     * 代理对 COUNT/LIMIT 分片重写并在内存合并——分页插件绑定代理数据源后跨分片结果完整。
     */
    public Page<TrackPointDTO> pagePoints(Long recordId, long page, long size) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        Page<TrackPoint> result = trackPointMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<TrackPoint>()
                        .eq(TrackPoint::getRecordId, recordId)
                        .eq(TrackPoint::getUserId, record.getUserId())
                        .orderByAsc(TrackPoint::getSeq));
        Page<TrackPointDTO> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(result.getRecords().stream().map(this::toDto).toList());
        return dtoPage;
    }

    /** 记录详情（Feign 契约实现用） */
    public SportRecordDTO getDto(Long recordId) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        SportRecordDTO dto = new SportRecordDTO();
        BeanUtils.copyProperties(record, dto);
        return dto;
    }

    // ==================== 内部工具 ====================

    /**
     * 解析提交的运动类型：缺省（null）回退 RUNNING（历史默认），
     * 枚举外取值抛 3007 拒绝（规范「未知类型拒绝」）。
     */
    private Integer resolveSportType(Integer code) {
        if (code == null) {
            return SportType.RUNNING.getCode();
        }
        SportType type = SportType.fromCode(code);
        if (type == null) {
            throw new BizException(ResultCode.SPORT_TYPE_INVALID, "未知运动类型：" + code);
        }
        return type.getCode();
    }

    /** 轨迹批量写入开关（压测「连接池调优案例」优化侧；默认关闭保留基线行为，对比复现后生产置 true） */
    @Value("${record.track.batch-insert-enabled:false}")
    private boolean batchInsertEnabled;

    /** 单批上限：一条多值 INSERT 至少 500 点，超长轨迹分批防止单语句过大 */
    private static final int POINTS_BATCH_SIZE = 500;

    /**
     * 批量轨迹落库（优化路径）：同记录分片键一致 → 单分片单条多值 INSERT。
     * 连接占用从 N×RTT 降到 ⌈N/500⌉×RTT，高并发下不再打满连接池（前后对比见压测报告）。
     */
    private void insertPointsBatch(SportRecord record, List<TrackPointDTO> points) {
        List<TrackPoint> batch = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            batch.add(toTrackPoint(record, points.get(i), i));
        }
        for (int from = 0; from < batch.size(); from += POINTS_BATCH_SIZE) {
            trackPointMapper.insertBatch(batch.subList(from, Math.min(from + POINTS_BATCH_SIZE, batch.size())));
        }
    }

    /** DTO → 实体（seq 缺省用下标；id 预生成——多值 INSERT 不经过 MP 的 ASSIGN_ID 回填） */
    private TrackPoint toTrackPoint(SportRecord record, TrackPointDTO p, int index) {
        TrackPoint tp = new TrackPoint();
        tp.setId(IdWorker.getId());
        tp.setRecordId(record.getId());
        tp.setUserId(record.getUserId()); // 冗余分片键（路由必须）
        tp.setSeq(p.getSeq() != null ? p.getSeq() : index);
        tp.setLat(p.getLat());
        tp.setLng(p.getLng());
        tp.setTs(p.getTs());
        tp.setSpeed(p.getSpeed());
        return tp;
    }

    private TrackPointDTO toDto(TrackPoint tp) {
        TrackPointDTO dto = new TrackPointDTO();
        BeanUtils.copyProperties(tp, dto);
        return dto;
    }
}
