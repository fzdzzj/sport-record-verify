package com.sportverify.verify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.sportverify.api.record.RecordApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.AppealStatus;
import com.sportverify.api.verify.Verdict;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.AppealReviewDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.verify.algorithm.EvidenceJsonBuilder;
import com.sportverify.verify.algorithm.VerifyEngine;
import com.sportverify.verify.algorithm.model.VerdictResult;
import com.sportverify.verify.config.VerifyProperties;
import com.sportverify.verify.entity.Appeal;
import com.sportverify.verify.entity.VerificationResult;
import com.sportverify.verify.mapper.AppealMapper;
import com.sportverify.verify.mapper.VerificationResultMapper;
import com.sportverify.verify.mq.VerifyEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 校验服务（规范「校验状态机」「判定聚合」「终判改判/维持拒绝」）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>判定主流程：预处理 + R1-R4 → 证据落 verification_result → 发事件 → Feign 回调 record 迁移状态；</li>
 *   <li>灰度路由：规则集按 userId%100 采样——命中灰度走 rule_version 库内快照，未命中走基线；</li>
 *   <li>幂等：verification_result 主键 record_id + Caffeine 缓存，重复触发不重算；</li>
 *   <li>申诉：建申诉单（record_id 唯一）/ 管理员终判（乐观锁 + 回调 + 事件）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyService {

    /** 判定结果本地缓存前缀（Caffeine 1min TTL，压测 P95 达标关键：命中缓存不重算） */
    private static final String RESULT_CACHE_PREFIX = "verify:result:";

    private final VerifyEngine verifyEngine;
    private final VerificationResultMapper verificationResultMapper;
    private final AppealMapper appealMapper;
    private final RecordApi recordApi;
    private final VerifyEventProducer verifyEventProducer;
    private final VerifyProperties verifyProperties;
    private final RuleVersionService ruleVersionService;
    private final Cache<String, Object> caffeineCache;
    private final ObjectMapper objectMapper;

    /**
     * 对记录执行校验判定（幂等可重放，规范「校验幂等」）：
     * <ol>
     *   <li>结果已终判且 record 状态已同步 → 返回缓存结果（不重算）；</li>
     *   <li>结果已终判但 record 仍 VERIFYING（历史回调失败）→ 仅补偿回调；</li>
     *   <li>未终判 → 初始化占位 → 拉轨迹 → 引擎判定 → 落库 → 发事件 → 回调迁移。</li>
     * </ol>
     *
     * <p>无本地长事务（ADR-0009）：路径含 Feign 拉轨迹、MQ 发事件、回调 record 状态，
     * 禁止把远程调用/消息发送包进本地事务，也不因此引入分布式事务框架。幂等占位 + 补偿回调保证最终一致。</p>
     *
     * <p>并发重入权衡（TASK-123 裁定哲学承续，2026-09-23 文档化接受）：本方法对同一 recordId
     * 不加互斥，MQ 消费重投与 Feign 直调降级（人工重放）并发时可双判定、双发事件、双回调。
     * 收敛不靠锁，靠既有三层机制兜住：</p>
     * <ol>
     *   <li>判定落库为 record_id 主键幂等写入：占位 INSERT IGNORE、终判 upsert 只覆盖不新增
     *       （VerificationResultMapper.initVerifying/upsert），双判定不产生第二行；</li>
     *   <li>record 侧状态回调：状态已等于目标态幂等跳过，乐观锁冲突（状态或版本已变更）
     *       返回 3003 → 消费端删去重键交 MQ 退避重投，重入本方法读到终判走
     *       {@link #reconcileCallback 补偿回调} 收敛；</li>
     *   <li>榜单侧：per-record 互斥锁 + 贡献锚点行 INSERT IGNORE/乐观 UPDATE，重复 VERIFIED
     *       事件只加分一次（LeaderboardService.applyVerified），无双份加分路径。</li>
     * </ol>
     * <p>重开条件：上述任一兜底被移除或实证失效，或出现可复现错态（重复加分/扣分），
     * 再立项可配 Redisson 锁；当前无正确性缺陷实证，不做无实证优化。</p>
     */
    public VerdictResult verify(Long recordId) {
        VerdictResult cached = readCachedResult(recordId);
        if (cached != null && cached.isFinal()) {
            reconcileCallback(recordId, cached); // 补偿回调，保证 record 状态最终一致
            return cached;
        }

        // 校验中占位（INSERT IGNORE，幂等）：对应状态机副作用「写 verification_result(VERIFYING)」
        verificationResultMapper.initVerifying(recordId);

        // 拉取记录与轨迹（record-api Feign；record-service 内部先解析 user_id 再路由分片）
        SportRecordDTO record = recordApi.getRecord(recordId).getData();
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        List<TrackPointDTO> points = recordApi.listPoints(recordId).getData();

        // 引擎判定：规则集经灰度路由——floorMod(userId,100)<gray_ratio 用 rule_version 库内
        // 灰度快照（不受 Nacos 瞬时变更影响），未命中走基线（ACTIVE 快照/Nacos 实时配置）；
        // 阈值维度与灰度正交：命中版本后按记录的 sportType 取对应 R1-R4 阈值（缺省回退 RUNNING）
        SportType sportType = SportType.fromCode(record.getSportType());
        VerdictResult result = verifyEngine.verify(points == null ? List.of() : points,
                ruleVersionService.getActiveRulesForUser(record.getUserId()),
                sportType == null ? SportType.RUNNING : sportType);
        result.setRecordId(recordId);

        // 证据落库（record_id 主键 upsert；重复判定只覆盖不新增）
        verificationResultMapper.upsert(toEntity(result));

        // 发 VERIFIED / REJECTED 事件（Tag 区分，规范「校验事件与幂等」）
        verifyEventProducer.publish(result.getVerdict(), recordId, record.getUserId());

        // 回调 record-service 迁移 VERIFYING → PASSED/REJECTED（乐观锁，冲突 3003 触发重试）
        callbackStatus(recordId, RecordStatus.VERIFYING.getCode(),
                verdictToRecordStatus(result.getVerdict()), record.getVersion());

        // 缓存判定结果（Caffeine 1min TTL）
        caffeineCache.put(RESULT_CACHE_PREFIX + recordId, result);
        return result;
    }

    /**
     * 结果已终判但 record 状态未同步时补偿回调（幂等：record 已终态则直接返回）。
     *
     * <p>两个收敛场景（压测变更「熔断降级转人工」引入后者）：</p>
     * <ul>
     *   <li>record 仍 VERIFYING：历史回调失败/竞态 → 按 VERIFYING → 终态补迁；</li>
     *   <li>record 已 MANUAL_REVIEW（转人工）：判定实际已完成（慢调用被熔断超时
     *       误判为失败后，verify 侧仍完成了判定）→ 以 verify_db 终判为权威，
     *       迁移 MANUAL_REVIEW → PASSED/REJECTED，转人工记录自动收敛，无需人工介入。</li>
     * </ul>
     */
    private void reconcileCallback(Long recordId, VerdictResult result) {
        SportRecordDTO record = recordApi.getRecord(recordId).getData();
        if (record == null || record.getStatus() == null) {
            return;
        }
        int status = record.getStatus();
        if (status != RecordStatus.VERIFYING.getCode() && status != RecordStatus.MANUAL_REVIEW.getCode()) {
            return;
        }
        callbackStatus(recordId, status,
                verdictToRecordStatus(result.getVerdict()), record.getVersion());
    }

    /** 状态回调：非 0 响应视为失败抛出（交由消费端重试/死信） */
    private void callbackStatus(Long recordId, Integer fromStatus, Integer toStatus, Integer version) {
        Result<Void> resp = recordApi.statusCallback(recordId,
                new StatusCallbackDTO(recordId, fromStatus, toStatus, version));
        if (resp == null || resp.getCode() != 0) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID,
                    "状态回调失败：" + (resp == null ? "无响应" : resp.getMessage()));
        }
        log.info("状态回调成功：recordId={}, {} → {}", recordId, fromStatus, toStatus);
    }

    /**
     * 查询校验结果（record 查询判定时经 verify-api 拉取；未终判返回 VERIFYING 占位）。
     */
    public VerificationResultDTO getVerificationResult(Long recordId) {
        VerificationResult row = verificationResultMapper.selectById(recordId);
        VerificationResultDTO dto = new VerificationResultDTO();
        if (row == null) {
            dto.setRecordId(recordId);
            dto.setVerdict(Verdict.VERIFYING.getCode());
            return dto;
        }
        BeanUtils.copyProperties(row, dto);
        return dto;
    }

    /**
     * 创建申诉单（规范「申诉提交」：建 appeal 单 record_id 唯一）。
     * 唯一键冲突 → 幂等返回既有申诉单。
     */
    public AppealDTO createAppeal(AppealCreateDTO dto) {
        Appeal appeal = new Appeal();
        appeal.setRecordId(dto.getRecordId());
        appeal.setUserId(dto.getUserId());
        appeal.setReason(dto.getReason());
        appeal.setStatus(AppealStatus.PENDING.getCode());
        appeal.setCreatedAt(LocalDateTime.now());
        try {
            appealMapper.insert(appeal);
        } catch (DuplicateKeyException e) {
            Appeal existing = appealMapper.selectOne(new LambdaQueryWrapper<Appeal>()
                    .eq(Appeal::getRecordId, dto.getRecordId()));
            if (existing == null) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "申诉单唯一键冲突但查询失败");
            }
            log.info("申诉单已存在，幂等返回：recordId={}", dto.getRecordId());
            return toDto(existing);
        }
        return toDto(appeal);
    }

    /**
     * 管理员终判（规范「终判改判」「终判维持拒绝」）：
     * appeal PENDING → RE_PASSED/RE_CONFIRMED（乐观锁）→
     * 回调 record 迁移 APPEALING → RE_PASSED/RE_CONFIRMED → 发 VERIFIED/REJECTED 事件。
     *
     * <p>无本地长事务（ADR-0009）：申诉行更新后仍有 Feign 回调与 MQ；跨服务窗口为已知残余，
     * 本路径不用本地事务假装远程一起原子，也不上 Seata。依赖幂等回调与事件重放收敛。</p>
     */
    public AppealDTO reviewAppeal(Long appealId, AppealReviewDTO dto) {
        Appeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new BizException(ResultCode.APPEAL_NOT_FOUND);
        }
        if (appeal.getStatus() != AppealStatus.PENDING.getCode()) {
            throw new BizException(ResultCode.APPEAL_STATUS_INVALID, "仅 PENDING 申诉单可终判");
        }
        int targetStatus = dto.isPass() ? AppealStatus.RE_PASSED.getCode() : AppealStatus.RE_CONFIRMED.getCode();
        int rows = appealMapper.updateStatus(appealId, AppealStatus.PENDING.getCode(),
                targetStatus, dto.getOperator(), dto.getRecheckResult());
        if (rows == 0) {
            throw new BizException(ResultCode.APPEAL_STATUS_INVALID, "并发冲突：申诉单状态已变更");
        }
        appeal.setStatus(targetStatus);
        appeal.setOperator(dto.getOperator());
        appeal.setRecheckResult(dto.getRecheckResult());

        // 回调 record 迁移（先取最新版本作为乐观锁条件；必须处于 APPEALING）
        SportRecordDTO record = recordApi.getRecord(appeal.getRecordId()).getData();
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != RecordStatus.APPEALING.getCode()) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "记录不在 APPEALING 状态");
        }
        callbackStatus(appeal.getRecordId(), RecordStatus.APPEALING.getCode(),
                dto.isPass() ? RecordStatus.RE_PASSED.getCode() : RecordStatus.RE_CONFIRMED.getCode(),
                record.getVersion());

        // 发事件：改判通过 → VERIFIED；维持拒绝 → REJECTED
        verifyEventProducer.publish(dto.isPass() ? Verdict.PASSED : Verdict.REJECTED,
                appeal.getRecordId(), appeal.getUserId());
        return toDto(appeal);
    }

    // ===== 私有工具 =====

    /** 读取判定结果：本地缓存优先，其次 DB（重复消费/重放不重算） */
    private VerdictResult readCachedResult(Long recordId) {
        Object cached = caffeineCache.getIfPresent(RESULT_CACHE_PREFIX + recordId);
        if (cached instanceof VerdictResult vr && vr.isFinal()) {
            return vr;
        }
        VerificationResult row = verificationResultMapper.selectById(recordId);
        if (row == null || row.getVerdict() == null || row.getVerdict() == Verdict.VERIFYING.getCode()) {
            return null;
        }
        return VerdictResult.builder()
                .recordId(recordId)
                .verdict(Verdict.fromCode(row.getVerdict()))
                .score(row.getScore() == null ? 0 : row.getScore())
                .build();
    }

    /** 判定结果 → verification_result 实体（rule_hits 为证据 JSON） */
    private VerificationResult toEntity(VerdictResult result) {
        VerificationResult entity = new VerificationResult();
        entity.setRecordId(result.getRecordId());
        entity.setVerdict(result.getVerdict().getCode());
        entity.setScore(result.getScore());
        try {
            entity.setRuleHits(EvidenceJsonBuilder.build(result, objectMapper));
        } catch (Exception e) {
            log.error("证据 JSON 序列化失败：recordId={}", result.getRecordId(), e);
            entity.setRuleHits("{}");
        }
        entity.setCheckedAt(LocalDateTime.now());
        return entity;
    }

    /** verdict → record 终态（PASSED/REJECTED ↔ PASSED/REJECTED；终判复用同映射） */
    private Integer verdictToRecordStatus(Verdict verdict) {
        return verdict == Verdict.PASSED ? RecordStatus.PASSED.getCode() : RecordStatus.REJECTED.getCode();
    }

    private AppealDTO toDto(Appeal appeal) {
        AppealDTO dto = new AppealDTO();
        BeanUtils.copyProperties(appeal, dto);
        return dto;
    }
}
