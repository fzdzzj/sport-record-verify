package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.RecordVisibility;
import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.RecordWithPointsDTO;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.record.dto.FeedItemDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.api.verify.AppealStatus;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.dto.RecordAggDTO;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.entity.TrackPointArchive;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.RecordEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 运动记录服务（规范「轨迹提交幂等」「轨迹分片存储」「校验状态机」）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>提交：request_id 唯一键幂等 → 记录直接以 VERIFYING 落单表 + 轨迹按 user_id%16 分片落库
 *       → 提交后发 SUBMITTED 事件（提交事务内不再单独 UPDATE 出不可见的 SUBMITTED 中间态）；</li>
 *   <li>状态回调：verify 判定/终判后以乐观锁驱动迁移（冲突 3003）；</li>
 *   <li>轨迹查询：先经 sport_record 解析 user_id，再按 user_id 路由单分片；</li>
 *   <li>申诉：REJECTED→APPEALING（经 verify-api 建申诉单，record_id 唯一）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SportRecordService {

    private final SportRecordMapper sportRecordMapper;
    private final TrackPointMapper trackPointMapper;
    private final TrackPointArchiveMapper trackPointArchiveMapper;
    private final RecordEventProducer recordEventProducer;
    private final VerifyApi verifyApi;
    private final VerifyDegradeService verifyDegradeService;
    /** user-service Feign（好友域）：FRIENDS 可见记录读取面逐对好友判断（TASK-221）。 */
    private final UserApi userApi;
    /** 点赞 DB 级联清理（TASK-223，经 setter 注入避免构造参数超 8）。 */
    private RecordLikeMapper recordLikeMapper;
    /** 点赞 Redis 缓存清理（TASK-223，经 setter 注入避免构造参数超 8）。 */
    private RecordLikeService recordLikeService;
    /** leaderboard-service Feign（TASK-223 D1 删除后回撤榜单里程贡献，经 setter 注入避免构造参数超 8）。 */
    private LeaderboardApi leaderboardApi;

    @Autowired
    public SportRecordService(SportRecordMapper sportRecordMapper,
                              TrackPointMapper trackPointMapper,
                              TrackPointArchiveMapper trackPointArchiveMapper,
                              RecordEventProducer recordEventProducer,
                              VerifyApi verifyApi,
                              VerifyDegradeService verifyDegradeService,
                              UserApi userApi) {
        this.sportRecordMapper = sportRecordMapper;
        this.trackPointMapper = trackPointMapper;
        this.trackPointArchiveMapper = trackPointArchiveMapper;
        this.recordEventProducer = recordEventProducer;
        this.verifyApi = verifyApi;
        this.verifyDegradeService = verifyDegradeService;
        this.userApi = userApi;
    }

    /** 注入点赞 Mapper（TASK-223 级联删除 DB 行）。 */
    @Autowired
    public void setRecordLikeMapper(RecordLikeMapper recordLikeMapper) {
        this.recordLikeMapper = recordLikeMapper;
    }

    /** 注入点赞 Service（TASK-223 缓存清理；@Lazy 懒代理解耦双向 setter 注入循环，TASK-266）。 */
    @Autowired
    public void setRecordLikeService(@Lazy RecordLikeService recordLikeService) {
        this.recordLikeService = recordLikeService;
    }

    /** 注入榜单 Feign 契约（TASK-223 D1 删除后回撤榜单里程贡献）。 */
    @Autowired
    public void setLeaderboardApi(LeaderboardApi leaderboardApi) {
        this.leaderboardApi = leaderboardApi;
    }

    /** 提交事务分段计时开关（TASK-139 插桩，默认关闭；true 时日志周期打 SUBMIT_TX_TIMING snapshot） */
    @Value("${record.submit.tx-timing-enabled:false}")
    private boolean txTimingEnabled;

    /** 分段计时聚合器（本服务为单例 bean，聚合跨请求；关闭态零开销） */
    private final SubmitTxTiming submitTxTiming = new SubmitTxTiming();

    /**
     * 提交运动记录。
     *
     * <p>幂等：uk_request_id 唯一键，重复提交捕获 DuplicateKeyException 后
     * 返回原记录（接口层映射为 3004 + 原结果，规范「重复提交幂等」）。</p>
     *
     * <p>本地事务边界（ADR-0009）：{@code @Transactional} 覆盖主记录 + 轨迹点；
     * 首次提交直接 INSERT VERIFYING / version=0，事务内不再对不可见的 SUBMITTED
     * 中间态单独 UPDATE（TASK-139 缩短提交 DB 足迹）。MQ/Feign 仅在
     * {@code afterCommit} 触发，轨迹写入失败不得发出校验事件。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RecordSubmitResultDTO submit(RecordSubmitDTO dto) {
        // 0. 分段计时（TASK-139 插桩，默认关闭）：把归因推断的 T_tx 拆成
        //    select / insert 主表 / 轨迹写入 / updateStatus（若仍在）/ commit 各段；
        //    成功收口在 afterCommit（见 5.1），回滚丢弃不计样本
        SubmitTxTiming.Rec txRec = submitTxTiming.begin(txTimingEnabled);

        // connWait 归因桥接（TASK-140，仅计时开启）：入口武装，事务完结（含回滚）的
        // afterCompletion 解除；注册先于任何 SQL，覆盖幂等/重复键/异常全部退出路径。
        // 仅本请求线程被武装，其他端点与后台线程不产生样本
        if (txTimingEnabled) {
            SubmitTxTiming.armConnWait(txRec);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    SubmitTxTiming.disarmConnWait();
                }
            });
        }

        // 1. 幂等前置校验（唯一键冲突的兜底在 insert 捕获）；
        //    requestId 必填校验由控制器层 @Valid + DTO 注解承担，此处不再重复判空
        long tSelect = System.nanoTime();
        SportRecord existing = sportRecordMapper.selectByRequestId(dto.getRequestId());
        txRec.span("select", tSelect);
        if (existing != null) {
            log.info("重复提交幂等返回：requestId={}, recordId={}", dto.getRequestId(), existing.getId());
            return RecordSubmitResultDTO.of(existing.getId(), existing.getRequestId(),
                    existing.getStatus(), true, "重复提交，返回原结果");
        }

        // 1.1 运动类型解析：缺省回退 RUNNING（历史默认）；枚举外取值拒绝（规范「未知类型拒绝」）
        Integer sportType = resolveSportType(dto.getSportType());
        // 1.2 可见性解析：缺省（null）回退 PUBLIC（历史 & 新记录默认公开）；枚举外取值拒绝（TASK-221）
        Integer visibility = resolveVisibility(dto.getVisibility());

        // 1.3 关键字段物理推导与合理性校验（TASK-234）：
        //     - startTime / endTime / duration / distance 未显式提供且 points 非空时，服务端基于
        //       轨迹首末点毫秒推导时间与时长（按 BUSINESS_ZONE 东八墙钟落库，TASK-259 时区语义
        //       统一；ts 本身为 UTC 纪元毫秒，与 GenSamples/前端 toISOString 同口径）、
        //       相邻点 Haversine 累加推导距离（公里保留两位，对齐 sport_record.distance DECIMAL(8,2)），
        //       根除库内关键字段存 NULL 的破窗；
        //     - 推导或显式后的 startTime/endTime 均存在时不得倒流，startTime 不得为未来时间。
        List<TrackPointDTO> points = dto.getPoints();
        LocalDateTime startTime = dto.getStartTime();
        LocalDateTime endTime = dto.getEndTime();
        BigDecimal distance = dto.getDistance();
        Integer duration = dto.getDuration();
        if (points != null && !points.isEmpty()
                && (startTime == null || endTime == null || duration == null || distance == null)) {
            DerivedTrackMetrics derived = deriveTrackMetrics(points);
            if (startTime == null) {
                startTime = derived.startTime();
            }
            if (endTime == null) {
                endTime = derived.endTime();
            }
            if (duration == null) {
                duration = derived.durationSeconds();
            }
            if (distance == null) {
                distance = derived.distanceKm();
            }
        }
        validateTimeRationality(startTime, endTime, startTime != null && dto.getStartTime() == null);

        // 2. 落 sport_record 主表（单表）：首次提交直接 INSERT status=VERIFYING / version=0，
        //    不再先 INSERT SUBMITTED 再在同一事务里 UPDATE——那条 UPDATE 只写了其他连接
        //    从未可见的中间态（规范「提交事务不落不可见中间态」）；SUBMITTED 保留为
        //    历史行/回调路径的合法状态
        SportRecord record = new SportRecord();
        record.setRequestId(dto.getRequestId());
        record.setUserId(dto.getUserId());
        record.setSportType(sportType);
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setDistance(distance);
        record.setDuration(duration);
        record.setStatus(RecordStatus.VERIFYING.getCode());
        record.setVisibility(visibility);
        record.setVersion(0);
        record.setCreatedAt(LocalDateTime.now());
        long tInsert = System.nanoTime();
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
        txRec.span("insertMain", tInsert);

        // 3. 轨迹点分片落库：ShardingSphere 按 user_id 自动路由到 track_point_{user_id%16}
        //    （WHERE/INSERT 必须携带分片键 user_id；id 由 MP 雪花算法生成）
        long tTrack = System.nanoTime();
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
        txRec.span("trackWrite", tTrack);
        // 4.（已删除）原同一事务内 SUBMITTED→VERIFYING 的乐观锁 UPDATE：
        //    提交事务内 SQL 少 1 条（TASK-139 缩短提交 DB 足迹）；updateStatus 方法本体
        //    与回调/补偿路径的 SUBMITTED→VERIFYING 迁移保留

        // 5.1 分段计时收口（TASK-139 插桩）：commit 段在真正提交前后取点；
        //     本同步注册在事件发布之前，afterCommit 按注册序先收口再发事件，提交段不被 MQ 发送污染
        if (txTimingEnabled) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                private long commitStart;

                @Override
                public void beforeCommit(boolean readOnly) {
                    commitStart = System.nanoTime();
                }

                @Override
                public void afterCommit() {
                    txRec.span("commit", commitStart);
                    txRec.flush();
                }
            });
        }

        // 5. 事务提交后异步发 SUBMITTED 事件进入校验流程（避免事务回滚导致孤儿事件）；
        //    不在请求线程 syncSend；失败回调里降级 Feign 直调（熔断保护，VerifyApiFallback 抛 4001）；
        //    直调仍失败 → 熔断降级转人工（MANUAL_REVIEW 终态），提交主链路不挂
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Long recordId = record.getId();
                recordEventProducer.publishSubmitted(recordId, record.getUserId(),
                        () -> fallbackTriggerVerify(recordId));
            }
        });
        return RecordSubmitResultDTO.of(record.getId(), record.getRequestId(),
                record.getStatus(), false, "提交成功，进入校验");
    }

    /**
     * MQ 异步发送失败后的降级：Feign 直调触发校验；直调仍失败则转人工。
     * 供 afterCommit 失败回调调用（可能在 RocketMQ 回调线程执行）。
     */
    void fallbackTriggerVerify(Long recordId) {
        log.warn("MQ 发布失败，降级 Feign 直调触发校验：recordId={}", recordId);
        try {
            verifyApi.triggerVerify(recordId);
        } catch (Exception ex) {
            // verify 不可用（熔断 OPEN/连接拒绝，4001）：转人工，不再无限 VERIFYING
            verifyDegradeService.degradeToManualReview(recordId, ex);
        }
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
    public AppealDTO appeal(Long recordId, Long caller, String reason) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        // 归属校验（TASK-204）：caller 非空且不等于记录归属人 → 403 越权。
        // 仅 app.auth.enabled=true 时 caller 非空；false 时恒 null → 零改动降级旧行为。
        if (caller != null && !caller.equals(record.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        if (record.getStatus() == null || record.getStatus() != RecordStatus.REJECTED.getCode()) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "仅 REJECTED 记录可发起申诉");
        }
        // 申诉防线（课题 3）：已归档记录拒绝——复判会拉轨迹点，冷数据已迁走，读空无意义
        if (Objects.equals(record.getArchived(), 1)) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "记录已归档，不支持申诉");
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
     * 删除运动记录（TASK-223，硬删除级联恰四表 + Redis 缓存清理）。
     *
     * <p>本地事务边界（ADR-0009）：{@code @Transactional} 覆盖主记录与关联表；
     * 单事务内级联删除 track_point、track_point_archive、record_like 行及 sport_record
     * （恰四表，轨迹分片表携带 user_id 路由单分片）。</p>
     * <p>缓存清理：事务提交后（{@code afterCommit}）best-effort 清除 Redis 点赞计数键与
     * 成员集键，Redis 失败不阻断删除、不回滚 DB（对账机制兜底收敛）。</p>
     * <p>榜单贡献回撤（TASK-223 D1）：同一 {@code afterCommit} 内 best-effort 经 leaderboard-api
     * 通知 leaderboard-service 把该记录的里程贡献 {@code ACTIVE → ROLLED_BACK} 并扣回 ZSet
     * （无贡献/已回滚幂等）；失败只告警留证，不回滚删除、不阻断主链路。</p>
     *
     * @param caller 调用者 userId（鉴权开启时为网关注入，关闭时为显式参数；非空时必须匹配归属人）
     * @param recordId 运动记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(Long caller, Long recordId) {
        SportRecord record = recordId == null ? null : sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        // 归属校验：caller 非空且不等于记录归属人 → 403 越权
        if (caller != null && !caller.equals(record.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }

        // 1. 级联删除轨迹点（携带 user_id 分片键路由单分片，避免广播）
        trackPointMapper.delete(new LambdaQueryWrapper<TrackPoint>()
                .eq(TrackPoint::getRecordId, recordId)
                .eq(TrackPoint::getUserId, record.getUserId()));
        trackPointArchiveMapper.delete(new LambdaQueryWrapper<TrackPointArchive>()
                .eq(TrackPointArchive::getRecordId, recordId)
                .eq(TrackPointArchive::getUserId, record.getUserId()));

        // 2. 级联删除点赞 DB 行
        if (recordLikeMapper != null) {
            recordLikeMapper.deleteByRecordId(recordId);
        }

        // 3. 删除主记录（按 id + userId 条件删除）
        sportRecordMapper.delete(new LambdaQueryWrapper<SportRecord>()
                .eq(SportRecord::getId, recordId)
                .eq(SportRecord::getUserId, record.getUserId()));

        // 4. 事务提交后 best-effort 清理 Redis 缓存（异常吞并留证，不阻断主链路）
        if (recordLikeService != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            recordLikeService.cleanupCache(recordId);
                        } catch (Exception e) {
                            log.warn("Redis 点赞缓存清理异常（best-effort 留证）：recordId={}", recordId, e);
                        }
                    }
                });
            } else {
                try {
                    recordLikeService.cleanupCache(recordId);
                } catch (Exception e) {
                    log.warn("Redis 点赞缓存清理异常（best-effort 留证）：recordId={}", recordId, e);
                }
            }
        }
        // 5. 事务提交后 best-effort 回撤榜单里程贡献（TASK-223 D1）：删除已提交才可通知，
        //    失败只告警留证（不回滚删除、不阻断主链路），跨服务最终一致（ADR-0009）
        if (leaderboardApi != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        rollbackLeaderboardContribution(recordId);
                    }
                });
            } else {
                rollbackLeaderboardContribution(recordId);
            }
        }
        log.info("运动记录删除成功：recordId={}, userId={}", recordId, record.getUserId());
    }

    /**
     * 回撤该记录的榜单里程贡献（best-effort，TASK-223 D1）。
     *
     * <p>经 leaderboard-api 通知 leaderboard-service 把贡献锚点 ACTIVE → ROLLED_BACK 并扣回 ZSet
     * （服务侧对「无锚点」「已回滚」幂等，不产生负里程）。跨服务无强一致（ADR-0009）：
     * 任何异常（Feign 降级 4008 / 锁竞争超时 / 网络故障）只 WARN 留证并返回，
     * 不抛出、不回滚已提交的删除；残留 ACTIVE 锚点由对账/人工收敛。</p>
     */
    void rollbackLeaderboardContribution(Long recordId) {
        try {
            leaderboardApi.rollbackContribution(recordId);
        } catch (Exception e) {
            log.warn("榜单贡献回撤失败（best-effort 留证，锚点可能残留 ACTIVE 待收敛）：recordId={}", recordId, e);
        }
    }

    /**
     * 查询判定结果（数据在 verify_db，经 verify-api Feign 拉取，规范「校验状态机」）。
     *
     * <p>读取面授权（TASK-221）：调用者 {@code caller} 经 {@link #assertCanView} 门判断
     * 是否可读——caller=null（鉴权关闭降级）恒放行、归属人恒放行、PRIVATE 非归属人 403、
     * FRIENDS 需互为好友、PUBLIC 恒放行。关闭了此前「任何人可读任意判定」的开放暴露。</p>
     */
    public VerificationResultDTO getVerifyResult(Long recordId, Long caller) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        assertCanView(record, caller);
        return verifyApi.getVerificationResult(recordId).getData();
    }

    /**
     * 一次性拉取记录详情与全部轨迹点（聚合查询，供 verify 校验输入）。
     * 先查一次 sport_record 得到元数据与 user_id，若不存在抛 3001；
     * 再按 user_id 路由对应分片查询全部轨迹点（按 seq 升序）。
     */
    public RecordWithPointsDTO getRecordWithPoints(Long recordId) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        SportRecordDTO recordDto = new SportRecordDTO();
        BeanUtils.copyProperties(record, recordDto);

        List<TrackPointDTO> points = trackPointMapper.selectList(new LambdaQueryWrapper<TrackPoint>()
                        .eq(TrackPoint::getRecordId, recordId)
                        .eq(TrackPoint::getUserId, record.getUserId())
                        .orderByAsc(TrackPoint::getSeq))
                .stream().map(this::toDto).toList();

        return new RecordWithPointsDTO(recordDto, points);
    }

    /**
     * 轨迹分页查询（规范「分片分页查询」场景）。
     * WHERE 携带 user_id → 路由单分片；若查询条件不含分片键，ShardingSphere 广播全部分片，
     * 代理对 COUNT/LIMIT 分片重写并在内存合并——分页插件绑定代理数据源后跨分片结果完整。
     * 按 archived 标志冷热路由（课题 3）：归档分支同口径查 track_point_archive。
     */
    public Page<TrackPointDTO> pagePoints(Long recordId, Long caller, long page, long size) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        // 读取面授权（TASK-221）：以 assertCanView 门判断——caller=null（鉴权关闭降级）恒放行、
        // 归属人恒放行、PRIVATE 非归属人 403、FRIENDS 需互为好友、PUBLIC 恒放行。
        // 取代了此前的「caller==归属人」断言：PUBLIC/FRIENDS 记录因此对相应读取者可读（预期产品变更）。
        assertCanView(record, caller);
        // TASK-261 R2a（G4）：分页双钳制（沿 pageMyRecords/pageFeed 先例）——page 下限 1、
        // size 夹 [1,500]，归档分支与热分支共用钳制后参数；防超大一页拉全轨迹
        // （ShardingSphere 广播 COUNT/LIMIT 内存合并放大）。500 对齐 B4 前端轨迹图
        // 500 点/页消费口径（MAX_POINTS_PAGE_SIZE）。
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_POINTS_PAGE_SIZE);
        Page<TrackPoint> result;
        if (Objects.equals(record.getArchived(), 1)) {
            Page<TrackPointArchive> archPage = trackPointArchiveMapper.selectPage(new Page<>(safePage, safeSize),
                    new LambdaQueryWrapper<TrackPointArchive>()
                            .eq(TrackPointArchive::getRecordId, recordId)
                            .eq(TrackPointArchive::getUserId, record.getUserId())
                            .orderByAsc(TrackPointArchive::getSeq));
            result = new Page<>(archPage.getCurrent(), archPage.getSize(), archPage.getTotal());
            result.setRecords(archPage.getRecords().stream().map(this::toHotPoint).toList());
        } else {
            result = trackPointMapper.selectPage(new Page<>(safePage, safeSize),
                    new LambdaQueryWrapper<TrackPoint>()
                            .eq(TrackPoint::getRecordId, recordId)
                            .eq(TrackPoint::getUserId, record.getUserId())
                            .orderByAsc(TrackPoint::getSeq));
        }
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

    /**
     * 记录详情基础指标（TASK-245 B22）：返回单条记录的运动类型/距离/时长/起止时间/状态/可见性。
     *
     * <p>归属校验（与 deleteRecord/appeal 同款，见 ADR-0007）：caller 非空且非记录归属人 → 403 越权；
     * caller=null（鉴权关闭降级态）放行，零改动降级旧行为。详情读数不对 FRIENDS/PUBLIC 陌生人开放
     * （本端点服务于「本人详情卡片」场景，与可见性修改同一归属门）。</p>
     *
     * @param recordId 记录 id
     * @param caller   调用者 userId（鉴权开启时为网关注入，关闭时为 null）
     */
    public SportRecordDTO getRecordDetail(Long recordId, Long caller) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (caller != null && !caller.equals(record.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        SportRecordDTO dto = new SportRecordDTO();
        BeanUtils.copyProperties(record, dto);
        return dto;
    }

    /**
     * 原地修改记录可见性（TASK-245 B22/B29）：仅允许本人修改，值域 0(PUBLIC)/1(FRIENDS)/2(PRIVATE)。
     *
     * <p>归属校验：caller 非空且非记录归属人 → 403 越权；合法值域外 → IllegalArgumentException（控制器映射 400）。
     * 更新主表 {@code sport_record.visibility} 单列即持久化（LambdaUpdateWrapper 单列 SET，不整行回写
     * status/version——selectById 快照与 verify statusCallback 乐观锁迁移并发时，整行回写会把
     * 旧 status/version 盖回真行，G2/TASK-260 R1 照 E30/TASK-256 修法杜绝；可见性非状态机字段，无乐观锁参与）。</p>
     *
     * @param caller     调用者 userId（鉴权开启时为网关注入，关闭时为 null）
     * @param recordId   记录 id
     * @param visibility 目标可见性（0/1/2）
     * @return 修改后的记录详情
     */
    public SportRecordDTO updateVisibility(Long caller, Long recordId, Integer visibility) {
        if (visibility == null || RecordVisibility.fromCode(visibility) == null) {
            throw new IllegalArgumentException("非法可见性：" + visibility);
        }
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (caller != null && !caller.equals(record.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        // G2（TASK-260 R1，照 E30/TASK-256 修法）：可见性单列 UPDATE，不整行回写——
        // 旧实现 selectById 快照整行 updateById，与 verify statusCallback 乐观锁迁移并发时，
        // 毫秒级窗口内可用 VERIFYING/v0 旧快照覆盖已迁移的 PASSED/v1（status/version 一并回退）；
        // 单列 UPDATE 天然原子，SET 列不含 status/version，旧快照无从覆盖状态机字段
        sportRecordMapper.update(null, new LambdaUpdateWrapper<SportRecord>()
                .eq(SportRecord::getId, recordId)
                .set(SportRecord::getVisibility, visibility));
        SportRecordDTO dto = new SportRecordDTO();
        BeanUtils.copyProperties(record, dto);
        dto.setVisibility(visibility); // 局部变量携带新值（不整行回写，E30 同款）
        return dto;
    }

    /**
     * 记录申诉逆向追踪（TASK-245 B26）：返回该记录关联的申诉进度信息（仅本人或管理员可查）。
     *
     * <p>申诉单数据本体在 verify_db（verify-service 侧），本服务仅在申诉提交路径把 {@code REJECTED→APPEALING}
     * 迁移持久化于 {@code sport_record.status}。故申诉「存在性 + 进度」由记录状态权威推导：</p>
     * <ul>
     *   <li>{@code status=APPEALING(4)} → 申诉已提交、待人工复核（对应 AppealStatus.PENDING）；</li>
     *   <li>{@code status=RE_PASSED(5)} → 终判改判通过（AppealStatus.RE_PASSED）；</li>
     *   <li>{@code status=RE_CONFIRMED(6)} → 终判维持拒绝（AppealStatus.RE_CONFIRMED）；</li>
     *   <li>其余状态 → 无申诉单，返回的 {@link AppealDTO#getStatus()} 为 null 作为「无申诉」信号。
     *       前端据此决定是否渲染进度条与禁用重复提交（杜绝 3005 重复建单冲突）。</li>
     * </ul>
     *
     * <p>申诉理由与管理员复核结论（recheckResult）存于 verify_db，需 verify-service 开放按 recordId
     * 查询端点后方可透出；当前白名单不含 verify-service，渲染面以进度与结论态为主（文档如实记录该局限）。</p>
     *
     * @param recordId 记录 id
     * @param caller   调用者 userId（鉴权开启时为网关注入，关闭时为 null）
     * @return 申诉进度对象（status 为 AppealStatus 码；无申诉时 status=null）
     */
    public AppealDTO getAppeal(Long recordId, Long caller) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (caller != null && !caller.equals(record.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        AppealDTO appeal = new AppealDTO();
        appeal.setRecordId(recordId);
        appeal.setUserId(record.getUserId());
        Integer status = record.getStatus();
        if (RecordStatus.APPEALING.getCode() == status) {
            appeal.setStatus(AppealStatus.PENDING.getCode());
        } else if (RecordStatus.RE_PASSED.getCode() == status) {
            appeal.setStatus(AppealStatus.RE_PASSED.getCode());
        } else if (RecordStatus.RE_CONFIRMED.getCode() == status) {
            appeal.setStatus(AppealStatus.RE_CONFIRMED.getCode());
        }
        return appeal;
    }

    /**
     * 我的记录分页查询（TASK-211 个人统计面板数据面，缺省无过滤重载）。
     *
     * <p>归属过滤即身份：以控制器解析出的 {@code userId} 作为唯一查询条件
     * （{@code WHERE user_id=?}），构造上天然无越权——用户只能拿到自己的记录，
     * 无需逐行归属断言。按创建时间倒序返回记录概要。</p>
     *
     * <p>分页边界：{@code page} 下限 1，{@code size} 夹在 [1, 500]（默认 20 由
     * 控制器 {@code @RequestParam(defaultValue="20")} 承担）。500 上限只约束列表单页容量；
     * 首页统计卡与每日里程自 TASK-231 起由 {@link #myStats} 全量聚合承担，不受本上限影响。</p>
     *
     * @param userId 归属用户（控制器按身份语义解析，非空）
     * @param page   页码（下限 1）
     * @param size   每页条数（1..500）
     * @return 分页结果（{@code Page<RecordSummaryDTO>}），元素按 createdAt 倒序
     */
    public Page<RecordSummaryDTO> pageMyRecords(Long userId, long page, long size) {
        return pageMyRecords(userId, page, size, null, null, null, null);
    }

    /**
     * 我的记录可选条件分页查询（TASK-224 add-my-records-filter）。
     *
     * <p>归属过滤即身份：以控制器解析出的 {@code userId} 作为核心查询条件
     * （{@code WHERE user_id=?}），构造上天然无越权。动态过滤参数全可选（null 跳过），
     * 保持与既有 user_id + orderByDesc(createdAt) + 钳制合成；不新增排序维度。</p>
     *
     * @param userId    归属用户（控制器按身份语义解析，非空）
     * @param page      页码（下限 1）
     * @param size      每页条数（1..500）
     * @param sportType 运动类型（可选枚举，1=RUNNING, 2=CYCLING, 3=WALKING；非法值抛 IllegalArgumentException）
     * @param status    审核状态（可选状态码 0..7；非法值抛 IllegalArgumentException）
     * @param startDate 开始日期下界（可选，按 COALESCE(start_time, created_at) >= startDate 00:00:00 过滤；
     *                  start_time=NULL 存量行回退 created_at 归窗，G11/TASK-260 R4 与 myStats/goal 口径统一）
     * @param endDate   开始日期上界（可选，按 COALESCE(start_time, created_at) <= endDate 23:59:59.999999999 过滤；
     *                  start>end 抛 IllegalArgumentException）
     * @return 分页结果（{@code Page<RecordSummaryDTO>}），元素按 createdAt 倒序
     */
    public Page<RecordSummaryDTO> pageMyRecords(Long userId, long page, long size,
                                                Integer sportType, Integer status,
                                                LocalDate startDate, LocalDate endDate) {
        if (sportType != null && SportType.fromCode(sportType) == null) {
            throw new IllegalArgumentException("非法运动类型：" + sportType);
        }
        if (status != null && !isValidStatus(status)) {
            throw new IllegalArgumentException("非法记录状态：" + status);
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_MY_RECORDS_SIZE);
        LambdaQueryWrapper<SportRecord> wrapper = new LambdaQueryWrapper<SportRecord>()
                .eq(SportRecord::getUserId, userId);
        if (sportType != null) {
            wrapper.eq(SportRecord::getSportType, sportType);
        }
        if (status != null) {
            wrapper.eq(SportRecord::getStatus, status);
        }
        // G11（TASK-260 R4）：日期过滤按活动时间口径 COALESCE(start_time, created_at)——
        // 与 myStats L937 / GoalService L194 统一：start_time=NULL 存量行（TASK-235 前旧数据）
        // 回退 created_at 归窗，不再被裸 start_time 过滤整行漏掉；
        // 函数索引 idx_user_coalesce_time 使该表达式保持 sargable（E23 R5）
        if (startDate != null) {
            wrapper.apply("COALESCE(start_time, created_at) >= {0}",
                    startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.apply("COALESCE(start_time, created_at) <= {0}",
                    endDate.atTime(LocalTime.MAX));
        }
        wrapper.orderByDesc(SportRecord::getCreatedAt);

        Page<SportRecord> result = sportRecordMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        Page<RecordSummaryDTO> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(result.getRecords().stream().map(this::toSummary).toList());
        return dtoPage;
    }

    /**
     * 好友动态 feed（TASK-222 add-friend-feed，跨服务聚合读路径，单查无 join、零 schema）。
     *
     * <p>过滤即身份（与 /my 同构）：本方法不返回任何会被门矩阵拒读的记录，而是按
     * caller 语义直接构造查询集——</p>
     * <ul>
     *   <li><b>auth on（caller 非空）</b>：候选归属人 = self ∪ friendIds（
     *       {@code user_id IN (...)}，friendIds 经 user-service {@code listFriendIds}
     *       一次内部调用拉齐）；再按可见性约束 {@code user_id = self OR visibility IN (0,1)}
     *       ——本人记录任意可见性、好友记录仅 PUBLIC/FRIENDS，陌生人记录（即使 PUBLIC）
     *       不支持入 feed（feed ≠ explore）。</li>
     *   <li><b>degrade（caller=null，auth.enabled=false）</b>：PUBLIC(0) 全站时间线，不区分归属
     *       （沿 assertCanView caller=null 放行降级态先例，TASK-204）。</li>
     * </ul>
     *
     * <p>排序 createdAt DESC，分页边界同 /my（page 下限 1，size 夹 [1,500]，默认 20）。
     * 昵称解析仅对当页 distinct author_user_id 批量调用 user-service {@code listUsersByIds}；
     * user-service 不可用（软降级空列表）→ 昵称空，web 回退 authorUserId 字符串渲染，不报错。</p>
     *
     * @param caller 读取者（auth on 时非空；degrade 时 null）
     * @param page   页码（下限 1）
     * @param size   每页条数（1..500）
     * @return feed 分页结果（{@code Page<FeedItemDTO>}），元素按 createdAt 倒序
     */
    public Page<FeedItemDTO> pageFeed(Long caller, long page, long size) {
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_MY_RECORDS_SIZE);
        Page<SportRecord> result;
        if (caller == null) {
            // degrade：PUBLIC 全站时间线（不区分归属）
            result = sportRecordMapper.selectPage(new Page<>(safePage, safeSize),
                    new LambdaQueryWrapper<SportRecord>()
                            .eq(SportRecord::getVisibility, RecordVisibility.PUBLIC.getCode())
                            .orderByDesc(SportRecord::getCreatedAt));
        } else {
            // auth on：候选归属人 = self ∪ friendIds（fail-safe：friendIds 空 → 仅本人）
            java.util.List<Long> friendIds = listFeedFriendIds(caller);
            java.util.Set<Long> authors = new java.util.LinkedHashSet<>();
            authors.add(caller);
            if (friendIds != null) {
                authors.addAll(friendIds);
            }
            result = sportRecordMapper.selectPage(new Page<>(safePage, safeSize),
                    new LambdaQueryWrapper<SportRecord>()
                            .in(SportRecord::getUserId, authors)
                            .and(w -> w.eq(SportRecord::getUserId, caller)
                                    .or()
                                    .in(SportRecord::getVisibility,
                                            RecordVisibility.PUBLIC.getCode(),
                                            RecordVisibility.FRIENDS.getCode()))
                            .orderByDesc(SportRecord::getCreatedAt));
        }
        Page<FeedItemDTO> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(buildFeedItems(result.getRecords()));
        return dtoPage;
    }

    /** 拉取本人好友 ID 集合；user-service 软降级/异常 → 空集（feed 退化为仅本人，fail-safe）。 */
    private java.util.List<Long> listFeedFriendIds(Long caller) {
        try {
            Result<java.util.List<Long>> resp = userApi.listFriendIds(caller);
            return resp == null ? java.util.List.of()
                    : resp.getData() == null ? java.util.List.of() : resp.getData();
        } catch (Exception e) {
            log.warn("好友 ID 拉取异常，feed 退化为仅本人：caller={}", caller, e);
            return java.util.List.of();
        }
    }

    /** feed 条目组装：Summary 字段 + 作者字段，昵称按当页 distinct author 批量补齐（feign 软降级空 → 昵称 null）。 */
    private java.util.List<FeedItemDTO> buildFeedItems(java.util.List<SportRecord> records) {
        java.util.List<Long> authorIds = records.stream()
                .map(SportRecord::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> nickMap = authorIds.isEmpty() ? Map.of() : fetchNicknames(authorIds);
        return records.stream().map(rec -> {
            FeedItemDTO dto = new FeedItemDTO();
            dto.setId(rec.getId());
            dto.setSportType(rec.getSportType());
            dto.setDistance(rec.getDistance());
            dto.setStatus(rec.getStatus());
            dto.setVisibility(rec.getVisibility());
            dto.setCreatedAt(rec.getCreatedAt());
            dto.setAuthorUserId(rec.getUserId());
            if (rec.getUserId() != null) {
                dto.setAuthorNickname(nickMap.get(rec.getUserId()));
            }
            return dto;
        }).toList();
    }

    /** 批量拉作者昵称；user-service 软降级空列表 → 空 map（web 回退 userId 字符串）。 */
    private Map<Long, String> fetchNicknames(java.util.List<Long> authorIds) {
        try {
            Result<java.util.List<UserDTO>> resp = userApi.listUsersByIds(authorIds);
            if (resp == null || resp.getData() == null) {
                return Map.of();
            }
            Map<Long, String> map = new java.util.HashMap<>();
            for (UserDTO u : resp.getData()) {
                if (u.getId() != null && u.getNickname() != null) {
                    map.put(u.getId(), u.getNickname());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("作者昵称批量拉取异常，web 将回退 authorUserId：authors={}", authorIds, e);
            return Map.of();
        }
    }

    /**
     * 我的连续打卡统计（TASK-220 个人仪表盘数据面，归属即身份）。
     *
     * <p>统计口径（与 {@link com.sportverify.api.record.dto.MyStreakDTO} 一致）：
     * 仅计入通过终态（PASSED / RE_PASSED）的记录——单列投影只取
     * (start_time, created_at)（个人量级，无分页）；映射为自然日
     * {@code toLocalDate()} 时<b>优先取 startTime 归日、null 回退 createdAt</b>
     * （TASK-235 时间口径对齐，与 TASK-234 目标服务 COALESCE 口径统一——
     * 离线补传/跨零点上传记录按运动开始时间归属自然日，不再按上传时间误判断签）；
     * 同日多条去重后交由 {@link StreakCalculator} 计算当前/最长连续天数，
     * today 取服务器时钟 {@code LocalDate.now()}。归属过滤即身份：以控制器解析出
     * 的 {@code userId} 作为唯一查询条件（{@code WHERE user_id=?}），构造上天然无越权。</p>
     *
     * @param userId 归属用户（控制器按身份语义解析，非空）
     * @return 连续打卡统计（current 当前连续天数 / longest 历史最长连续天数）
     */
    public MyStreakDTO myStreak(Long userId) {
        List<LocalDate> checkinDates = sportRecordMapper.selectList(
                        new LambdaQueryWrapper<SportRecord>()
                                .select(SportRecord::getStartTime, SportRecord::getCreatedAt)
                                .eq(SportRecord::getUserId, userId)
                                .in(SportRecord::getStatus,
                                        List.of(RecordStatus.PASSED.getCode(), RecordStatus.RE_PASSED.getCode())))
                .stream()
                .map(record -> {
                    // 时间口径（TASK-235）：startTime 优先，null 回退 createdAt；双 null 防御性跳过
                    LocalDateTime anchor = record.getStartTime() != null
                            ? record.getStartTime() : record.getCreatedAt();
                    return anchor == null ? null : anchor.toLocalDate();
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        int[] streak = StreakCalculator.compute(checkinDates, LocalDate.now());
        MyStreakDTO dto = new MyStreakDTO();
        dto.setCurrent(streak[0]);
        dto.setLongest(streak[1]);
        return dto;
    }

    /**
     * 个人核心统计（TASK-231 服务端聚合端点 + TASK-232 SQL 聚合下推，归属即身份）。
     *
     * <p>与列表彻底解耦：查询条件仅为归属人（{@code WHERE user_id=?}，构造上天然无越权），
     * <b>不过滤状态、不分页</b>——totalCount / totalDistance / passCount 覆盖该用户全部记录，
     * 修掉了此前前端「size=500 单次拉取 + 浏览器内存聚合」的截断与翻页耦合两处技术债。</p>
     *
     * <p>查询形态（TASK-232 SQL 聚合下推 + TASK-235 时间口径对齐）：全量指标由
     * {@code selectAggByUserId} 单行聚合（{@code COUNT(*)} / {@code COALESCE(SUM(distance),0)} /
     * {@code COUNT(CASE WHEN status IN (PASSED,RE_PASSED) THEN 1 END)}）在数据库端算出，
     * 不再把该用户全部记录投影到 JVM 内存汇总；近 30 天日粒度只查
     * {@code user_id=? AND COALESCE(start_time, created_at) >= windowStart}（时间窗收窄，
     * 仅窗口内行做内存按日累加——窗口口径由 created_at 改为活动时间，与 TASK-234
     * 目标服务统一，离线补传/跨零点上传记录按运动时间归日、不再误判断签）。</p>
     *
     * <p>口径：totalDistance 与 dailyDistances 各值保留两位小数（HALF_UP，null 距离按 0 计）；
     * passCount 仅计通过终态 PASSED(2) / RE_PASSED(5)（与 {@link #myStreak} 同款入账口径）；
     * passRate 为 {@code "%.1f%%"} 格式化串，无记录时为 {@code 0%}；dailyDistances 以服务器时钟
     * {@code LocalDate.now()} 为窗口右端，恒返回 {@link #STATS_DAILY_WINDOW_DAYS} 个自然日
     * （升序、无记录日补 0），按 COALESCE(start_time, created_at) 落入自然日汇总，
     * 窗口外的记录只计入总量。</p>
     *
     * @param userId 归属用户（控制器按身份语义解析，非空）
     * @return 个人核心统计（全量口径）
     */
    public MyStatsDTO myStats(Long userId) {
        // 1. 全量指标：数据库单行聚合（不拉记录行），无记录时 SUM 由 COALESCE 兜 0
        RecordAggDTO agg = sportRecordMapper.selectAggByUserId(userId, PASS_STATUS_CODES);
        long totalCount = agg == null || agg.getTotalCount() == null ? 0L : agg.getTotalCount();
        long passCount = agg == null || agg.getPassCount() == null ? 0L : agg.getPassCount();
        BigDecimal totalDistance = agg == null || agg.getTotalDistance() == null
                ? BigDecimal.ZERO : agg.getTotalDistance();
        // 近 30 天窗口（升序，无记录日补 0）：LinkedHashMap 保证键序即日期序
        Map<String, BigDecimal> daily = new LinkedHashMap<>();
        LocalDate windowStart = LocalDate.now().minusDays(STATS_DAILY_WINDOW_DAYS - 1L);
        for (int i = 0; i < STATS_DAILY_WINDOW_DAYS; i++) {
            daily.put(windowStart.plusDays(i).toString(), BigDecimal.ZERO);
        }

        // 3. 日粒度：窗口内 (start_time, created_at, distance) 投影，窗口下界按活动时间口径
        //    COALESCE(start_time, created_at) >= windowStart（TASK-235 与 myStreak/目标服务统一）——
        //    离线补传/跨零点上传记录按运动开始时间归属自然日，不再按上传时间误判断签；
        //    窗口行仍受时间窗收窄（非全量拉取），窗口外记录仅计入第 1 步总量
        List<SportRecord> windowRows = sportRecordMapper.selectList(
                new LambdaQueryWrapper<SportRecord>()
                        .select(SportRecord::getStartTime, SportRecord::getCreatedAt,
                                SportRecord::getDistance)
                        .eq(SportRecord::getUserId, userId)
                        .apply("COALESCE(start_time, created_at) >= {0}",
                                windowStart.atStartOfDay()));
        for (SportRecord row : windowRows) {
            // 时间口径（TASK-235）：startTime 优先，null 回退 createdAt；双 null 防御性跳过
            LocalDateTime anchor = row.getStartTime() != null ? row.getStartTime() : row.getCreatedAt();
            if (anchor == null) {
                continue;
            }
            String dayKey = anchor.toLocalDate().toString();
            if (daily.containsKey(dayKey)) {
                BigDecimal distance = row.getDistance() == null ? BigDecimal.ZERO : row.getDistance();
                daily.put(dayKey, daily.get(dayKey).add(distance));
            }
        }

        Map<String, BigDecimal> dailyDistances = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : daily.entrySet()) {
            dailyDistances.put(entry.getKey(), entry.getValue().setScale(2, RoundingMode.HALF_UP));
        }
        MyStatsDTO dto = new MyStatsDTO();
        dto.setTotalCount(totalCount);
        dto.setTotalDistance(totalDistance.setScale(2, RoundingMode.HALF_UP));
        dto.setPassCount(passCount);
        dto.setPassRate(formatPassRate(passCount, totalCount));
        dto.setDailyDistances(dailyDistances);
        return dto;
    }

    /** 通过终态码集（PASSED(2) / RE_PASSED(5)）：myStats 聚合入参与 myStreak 入账口径同源。 */
    private static final List<Integer> PASS_STATUS_CODES =
            List.of(RecordStatus.PASSED.getCode(), RecordStatus.RE_PASSED.getCode());

    /** 通过率格式化（一位小数百分比；无记录返回 0%，与前端历史展示口径一致）。 */
    private static String formatPassRate(long passCount, long totalCount) {
        if (totalCount == 0L) {
            return "0%";
        }
        return BigDecimal.valueOf(passCount * 100.0 / totalCount)
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    /** 近 30 天每日里程窗口长度（TASK-231 个人统计聚合口径）。 */
    private static final int STATS_DAILY_WINDOW_DAYS = 30;

    /** 我的记录列表单页上限（pageMyRecords / pageFeed 分页钳制共用）。 */
    private static final int MAX_MY_RECORDS_SIZE = 500;

    /** 轨迹分页单页上限（TASK-261 R2a pagePoints 分页钳制）：对齐 B4 前端轨迹图 500 点/页消费口径。 */
    private static final int MAX_POINTS_PAGE_SIZE = 500;

    /** 实体 → 记录概要 DTO（字段：id/sportType/distance/status/visibility/createdAt）。 */
    private RecordSummaryDTO toSummary(SportRecord record) {
        RecordSummaryDTO dto = new RecordSummaryDTO();
        dto.setId(record.getId());
        dto.setSportType(record.getSportType());
        dto.setDistance(record.getDistance());
        dto.setStatus(record.getStatus());
        dto.setVisibility(record.getVisibility());
        dto.setCreatedAt(record.getCreatedAt());
        return dto;
    }

    /**
     * 校验记录状态合法性（0..7，RecordStatus 枚举域）。
     */
    private static boolean isValidStatus(Integer status) {
        if (status == null) {
            return false;
        }
        for (RecordStatus s : RecordStatus.values()) {
            if (s.getCode() == status) {
                return true;
            }
        }
        return false;
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

    /**
     * 解析提交的可见性：缺省（null）回退 PUBLIC（0，历史 & 新记录默认公开，向后兼容），
     * 枚举外取值抛 3007 拒绝（resolveSportType 同款先例，TASK-221）。
     */
    private Integer resolveVisibility(Integer code) {
        if (code == null) {
            return RecordVisibility.PUBLIC.getCode();
        }
        RecordVisibility visibility = RecordVisibility.fromCode(code);
        if (visibility == null) {
            throw new BizException(ResultCode.SPORT_TYPE_INVALID, "未知可见性：" + code);
        }
        return visibility.getCode();
    }

    // ==================== 物理推导与合理性校验（TASK-234） ====================

    /** 地球平均半径（米，与 verify-service GeoUtils 同口径）。 */
    private static final double EARTH_RADIUS_M = 6_371_000;

    /**
     * 全链业务时区（TASK-259 时区语义统一）：东八墙钟口径的唯一事实源——
     * start_time/end_time 推导落库与推导帧未来时间比对均按本时区，与
     * docker-compose.services.yml 六服务容器 TZ、mysql/postgis 中间件 TZ 及
     * JDBC serverTimezone=Asia/Shanghai 同帧对齐（前端目标用户为东八）。
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 轨迹推导结果（TASK-234）：首末点时间/时长（东八墙钟，TASK-259 起）+ 相邻点 Haversine 距离（公里，两位小数）。
     *
     * @param startTime 开始时间（东八墙钟）
     * @param endTime 结束时间（东八墙钟）
     * @param durationSeconds 运动时长（秒）
     * @param distanceKm 运动距离（公里）
     */
    private record DerivedTrackMetrics(LocalDateTime startTime, LocalDateTime endTime,
                                       int durationSeconds, BigDecimal distanceKm) {
    }

    /**
     * 由轨迹点推导关键字段（TASK-234）：时间与时长取首末点毫秒 ts（UTC 纪元毫秒，
     * 与 GenSamples/前端 toISOString 同口径，跨时区确定），按 BUSINESS_ZONE 东八
     * 墙钟落库（TASK-259 时区语义统一：与 created_at/容器 TZ/JDBC serverTimezone
     * 对齐，归日/周期窗口按东八日界）；距离按相邻点 Haversine
     * 逐段累加（与 verify-service GeoUtils 同公式），换算公里后保留两位小数
     * （对齐 sport_record.distance DECIMAL(8,2) 存储精度）。
     *
     * @throws IllegalArgumentException 首末点缺少 ts（无法推导时间/时长）时拒绝
     */
    private DerivedTrackMetrics deriveTrackMetrics(List<TrackPointDTO> points) {
        Long firstTs = points.get(0).getTs();
        Long lastTs = points.get(points.size() - 1).getTs();
        if (firstTs == null || lastTs == null) {
            throw new IllegalArgumentException("轨迹点缺少时间戳（ts），无法推导开始/结束时间与时长");
        }
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(firstTs), BUSINESS_ZONE);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastTs), BUSINESS_ZONE);
        int durationSeconds = (int) ((lastTs - firstTs) / 1000);
        double meters = 0;
        for (int i = 1; i < points.size(); i++) {
            meters += haversineMeters(points.get(i - 1), points.get(i));
        }
        BigDecimal distanceKm = BigDecimal.valueOf(meters / 1000.0).setScale(2, RoundingMode.HALF_UP);
        return new DerivedTrackMetrics(start, end, durationSeconds, distanceKm);
    }

    /** 相邻点球面距离（米，Haversine 公式；与 verify-service GeoUtils 同公式同常数）。 */
    private static double haversineMeters(TrackPointDTO a, TrackPointDTO b) {
        double lat1 = a.getLat().doubleValue();
        double lng1 = a.getLng().doubleValue();
        double lat2 = b.getLat().doubleValue();
        double lng2 = b.getLng().doubleValue();
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double dLat = radLat2 - radLat1;
        double dLng = Math.toRadians(lng2) - Math.toRadians(lng1);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(s));
    }

    /**
     * 时间合理性校验（TASK-234）：startTime 与 endTime 均存在时不得倒流；
     * startTime 不得为未来时间——显式入参按服务器本地墙钟比对（客户端同帧假设），
     * 轨迹推导值按 BUSINESS_ZONE 东八墙钟比对（推导帧自洽，TASK-259 与推导落库同帧；
     * 修前比对帧为 UTC，推导改区后若不同帧则 8 小时内真实提交被误判未来时间），
     * 两帧各自成立。
     *
     * @param startTimeDerived startTime 是否由轨迹推导（决定未来校验的墙钟帧）
     */
    private void validateTimeRationality(LocalDateTime startTime, LocalDateTime endTime,
                                         boolean startTimeDerived) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        if (startTime != null) {
            LocalDateTime now = startTimeDerived
                    ? LocalDateTime.now(BUSINESS_ZONE) : LocalDateTime.now();
            if (startTime.isAfter(now)) {
                throw new IllegalArgumentException("开始时间不能为未来时间");
            }
        }
    }

    /**
     * 记录读取面授权门（add-record-visibility，TASK-221 核心判别式）。
     *
     * <p>对「读取者 caller」作逐记录裁决，读取路径统一收敛（pagePoints / getVerifyResult /
     * 点赞三端点经 RecordLikeService 复用本方法）。门矩阵：</p>
     * <ul>
     *   <li>caller == null（鉴权关闭降级态，TASK-204 先例）→ 放行（生产严格 auth.enabled=true，
     *       见 TASK-191 严格链接，降级态仅本地基准可达）；</li>
     *   <li>caller.equals(record.getUserId())（归属人）→ 放行（owner×{PUBLIC,FRIENDS,PRIVATE} 均放行）；</li>
     *   <li>PRIVATE → 任何非归属人 403；</li>
     *   <li>FRIENDS → {@code userApi.areFriends(owner, caller)} 互为好友才放行，否则 403；</li>
     *   <li>PUBLIC 或 visibility 为 null（存量行 / 新装测试上下文）→ 任何登录读取者放行。</li>
     * </ul>
     *
     * <p>UserApi 异常/软降级 false（fail-safe 隐私，UserApiFallback 先例）→ 按「非好友」= 403。
     * 归属人短路在 null 判定之后、PRIVATE/FRIENDS 检查之前，归属人读自己任何可见性都放行。</p>
     *
     * @param record 目标记录（须已加载，含 userId 与 visibility）
     * @param caller 读取者（可能为 null，降级态）
     * @throws BizException 非归属人读 PRIVATE，或非好友读 FRIENDS 时 FORBIDDEN(1002)
     */
    public void assertCanView(SportRecord record, Long caller) {
        if (caller == null) {
            return;
        }
        if (record.getUserId() != null && caller.equals(record.getUserId())) {
            return;
        }
        RecordVisibility visibility = RecordVisibility.fromCode(record.getVisibility());
        // null（存量行/新装上下文未回填）视为 PUBLIC，向后兼容
        if (visibility == null || visibility == RecordVisibility.PUBLIC) {
            return;
        }
        if (visibility == RecordVisibility.PRIVATE) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        // FRIENDS：互为好友才放行；UserApi 异常/软降级 false → 403（失败安全）
        boolean friends;
        try {
            friends = Boolean.TRUE.equals(
                    userApi.areFriends(record.getUserId(), caller).getData());
        } catch (Exception e) {
            log.warn("好友判断异常，按非好友拒绝（fail-safe）：recordId={}, caller={}", record.getId(), caller, e);
            friends = false;
        }
        if (!friends) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 批量可见性裁决（TASK-261 R1，getLikesBatch 批内 N+1 重构授权承载）：对一批已加载记录
     * 执行与单条 {@link #assertCanView(SportRecord, Long)} 完全一致的门矩阵，返回通过校验的
     * 子集（保持入参顺序，调用方按 distinctIds 去重保序组装输出）。单条语义是「不过门抛 403」，
     * 批量语境下等价动作是「不过门剔除该记录」（getLikesBatch 契约：不可视记录安全跳过，
     * 绝不因单条记录导致整批报错）。
     *
     * <p>与逐条 {@code assertCanView} 的唯一差异在 FRIENDS 分支数据源：整批<b>至多一次</b>
     * {@code userApi.listFriendIds(caller)} 懒拉好友集，FRIENDS 判定改内存 {@code contains}——
     * 消除批内逐条 {@code areFriends} Feign 往返（最坏 N 次串行 RTT → 至多 1 次）。
     * 懒拉触发条件：caller 非空且批内存在「非归属人 + FRIENDS 可见性」记录时才拉取，
     * PUBLIC-only / 纯归属人批零额外 Feign。</p>
     *
     * <p>门矩阵四分支语义逐字保持：caller=null（鉴权关闭降级态）整批放行；归属人放行；
     * PUBLIC 或 visibility=null（存量行）放行；PRIVATE 非归属人剔除（等价逐条 403）；
     * FRIENDS 非好友剔除（等价逐条 403，含归属人缺失的存量行）。</p>
     *
     * <p>fail-safe 对齐（UserApiFallback 先例）：listFriendIds 软降级空列表 / 拉取异常 → 空集，
     * FRIENDS 记录按非好友剔除（与 {@code areFriends} fallback false 同语义，隐私失败安全）。</p>
     *
     * @param records 已加载的候选记录（顺序即期望输出顺序）
     * @param caller  读取者（可能为 null，降级态）
     * @return 通过可见性校验的记录子集（保持入参顺序，永不为 null）
     */
    public List<SportRecord> assertCanView(List<SportRecord> records, Long caller) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        if (caller == null) {
            // 降级态：单条门 caller=null 恒放行，同语义整批放行
            return new ArrayList<>(records);
        }
        List<SportRecord> authorized = new ArrayList<>(records.size());
        Set<Long> friendIds = null;   // 懒拉：首个「非归属人 + FRIENDS」记录触发，整批至多一次
        for (SportRecord record : records) {
            if (record.getUserId() != null && caller.equals(record.getUserId())) {
                authorized.add(record);   // 归属人放行（含 PRIVATE / FRIENDS）
                continue;
            }
            RecordVisibility visibility = RecordVisibility.fromCode(record.getVisibility());
            if (visibility == null || visibility == RecordVisibility.PUBLIC) {
                authorized.add(record);   // PUBLIC / 存量行 null 可见性放行
                continue;
            }
            if (visibility == RecordVisibility.PRIVATE) {
                continue;   // 等价单条门 403 → 批内剔除
            }
            // FRIENDS：懒拉好友集（整批一次），内存 contains 判定（非好友/归属人缺失 → 剔除）
            if (friendIds == null) {
                friendIds = loadFriendIdSet(caller);
            }
            if (record.getUserId() != null && friendIds.contains(record.getUserId())) {
                authorized.add(record);
            }
        }
        return authorized;
    }

    /**
     * 批量裁决好友集懒拉（fail-safe，沿 {@link #listFeedFriendIds} 先例）：
     * user-service 软降级（fallback 空列表）/ 返回缺数 / 异常 → 空集，
     * FRIENDS 记录按非好友剔除（与 areFriends fallback false 同语义，不报错不阻断）。
     */
    private Set<Long> loadFriendIdSet(Long caller) {
        try {
            Result<List<Long>> resp = userApi.listFriendIds(caller);
            if (resp == null || resp.getData() == null) {
                return Set.of();
            }
            return new HashSet<>(resp.getData());
        } catch (Exception e) {
            log.warn("批量可见性好友集拉取异常，FRIENDS 记录按非好友剔除（fail-safe）：caller={}", caller, e);
            return Set.of();
        }
    }

    /** 轨迹批量写入开关（ADR-0002 已验证优化路径；缺省 true 与 application.properties 一致，复现基线显式 false） */
    @Value("${record.track.batch-insert-enabled:true}")
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

    /** 归档实体转热表实体形态（DTO 映射复用） */
    private TrackPoint toHotPoint(TrackPointArchive a) {
        TrackPoint p = new TrackPoint();
        p.setId(a.getId());
        p.setRecordId(a.getRecordId());
        p.setUserId(a.getUserId());
        p.setSeq(a.getSeq());
        p.setLat(a.getLat());
        p.setLng(a.getLng());
        p.setTs(a.getTs());
        p.setSpeed(a.getSpeed());
        return p;
    }
}
