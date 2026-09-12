package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mq.RecordEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 校验降级服务（压测变更 spec「熔断降级转人工」+「提交主链路不挂」）。
 *
 * <p>verify-service 不可用时的两条兜底路径，保证记录不无限滞留 VERIFYING：</p>
 * <ul>
 *   <li><b>即时降级</b>：MQ 发布失败 → Feign 直调 {@code triggerVerify} 也失败
 *       （verify 熔断 OPEN / 连接拒绝，经 VerifyApiFallback 抛 4001）→ 调用方转入本服务，
 *       乐观锁迁移 VERIFYING → MANUAL_REVIEW（转人工终态，主链路不挂）；</li>
 *   <li><b>滞留补偿</b>（定时任务）：MQ 正常但 verify 停机时消息无人消费，
 *       记录会长时间滞留 VERIFYING——补偿任务扫描「校验中超过阈值」的记录，
 *       先探活：verify 已恢复则重发 SUBMITTED 事件补判（幂等重放），
 *       仍不可用则转人工。阈值与开关可配（Nacos 可调）。</li>
 * </ul>
 *
 * <p>并发口径：迁移一律走乐观锁（{@code status=VERIFYING AND version=?}），
 * 与 verify 判定回调天然互斥——先到者赢，后到者影响 0 行幂等跳过，
 * 不会出现「已 PASSED 又被改转人工」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyDegradeService {

    /** 单轮补偿扫描上限（防止 verify 长期不可用时任务空转全表） */
    private static final int SCAN_LIMIT = 100;

    private final SportRecordMapper sportRecordMapper;
    private final RecordEventProducer recordEventProducer;
    private final VerifyApi verifyApi;

    /** 滞留阈值（秒）：VERIFYING 超过该时长触发补偿（本地演示 120s，生产建议 300s+） */
    @Value("${record.verify-degrade.stuck-seconds:120}")
    private long stuckSeconds;

    /** 补偿任务开关（压测期间可关闭，避免干扰指标统计） */
    @Value("${record.verify-degrade.compensate-enabled:true}")
    private boolean compensateEnabled;

    /**
     * 熔断降级转人工：VERIFYING → MANUAL_REVIEW（乐观锁）。
     *
     * <p>影响 0 行 = 记录已被 verify 回调推进（PASSED/REJECTED）或已转人工 → 幂等跳过。
     * 转人工是终态：恢复后由补偿任务的「重发事件」路径补判，或人工在管理端处理。</p>
     *
     * @param recordId 记录ID
     * @param cause    触发降级的异常（verify 熔断 4001 / 连接失败），仅入日志
     */
    public void degradeToManualReview(Long recordId, Throwable cause) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            log.warn("降级转人工失败，记录不存在：recordId={}", recordId);
            return;
        }
        if (record.getStatus() == null || record.getStatus() != RecordStatus.VERIFYING.getCode()) {
            // 已被回调推进或已转人工：无需降级（乐观语义幂等）
            log.info("记录已离开 VERIFYING，跳过转人工：recordId={}, status={}", recordId, record.getStatus());
            return;
        }
        int rows = sportRecordMapper.updateStatus(recordId,
                RecordStatus.VERIFYING.getCode(), RecordStatus.MANUAL_REVIEW.getCode(), record.getVersion());
        if (rows == 0) {
            log.info("转人工迁移冲突（并发已被推进），幂等跳过：recordId={}", recordId);
            return;
        }
        log.warn("记录转人工（verify 熔断降级）：recordId={}, cause={}",
                recordId, cause == null ? "unknown" : cause.toString());
    }

    /**
     * 滞留补偿（每 60s 一轮，首跑延迟 90s 给正常异步校验留窗口）：
     * <ol>
     *   <li>滞留 VERIFYING 超阈值的记录：探活 verify →
     *       活着则重发 SUBMITTED 事件补判（verify 幂等不重算），仍不可用则转人工；</li>
     *   <li>历史 MANUAL_REVIEW 记录：探活 verify →
     *       活着则重发事件让 verify 以终判结果对账收敛（reconcileCallback 迁移回终态），
     *       覆盖「慢调用被熔断误判转人工、实际判定随后完成」的自愈；仍不可用保持转人工。</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void compensateStuckVerifying() {
        if (!compensateEnabled) {
            return;
        }
        int republished = 0;
        int degraded = 0;

        // 1) VERIFYING 滞留补偿
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(stuckSeconds);
        List<SportRecord> stuck = sportRecordMapper.selectList(new LambdaQueryWrapper<SportRecord>()
                .eq(SportRecord::getStatus, RecordStatus.VERIFYING.getCode())
                .lt(SportRecord::getCreatedAt, threshold)
                .orderByAsc(SportRecord::getId)
                .last("LIMIT " + SCAN_LIMIT));
        if (!stuck.isEmpty()) {
            log.warn("发现滞留 VERIFYING 记录 {} 条（超 {}s），开始补偿", stuck.size(), stuckSeconds);
            for (SportRecord record : stuck) {
                try {
                    verifyApi.health().getData(); // 探活：verify 不可用时经 Fallback 抛 4001
                    if (recordEventProducer.publishSubmitted(record.getId(), record.getUserId())) {
                        republished++;
                        log.info("滞留记录已重发校验事件：recordId={}", record.getId());
                    } else {
                        degradeToManualReview(record.getId(), null);
                        degraded++;
                    }
                } catch (Exception e) {
                    degradeToManualReview(record.getId(), e);
                    degraded++;
                }
            }
        }

        // 2) 转人工记录自愈（verify 恢复后按终判对账收敛；每轮限量，避免恢复瞬间重放风暴）
        List<SportRecord> manual = sportRecordMapper.selectList(new LambdaQueryWrapper<SportRecord>()
                .eq(SportRecord::getStatus, RecordStatus.MANUAL_REVIEW.getCode())
                .orderByAsc(SportRecord::getId)
                .last("LIMIT " + SCAN_LIMIT));
        if (!manual.isEmpty()) {
            try {
                verifyApi.health().getData();
            } catch (Exception e) {
                log.info("verify 仍不可用，{} 条转人工记录继续等待人工/后续自愈", manual.size());
                return;
            }
            for (SportRecord record : manual) {
                if (recordEventProducer.publishSubmitted(record.getId(), record.getUserId())) {
                    republished++;
                    log.info("转人工记录已重放（等待 verify 终判对账收敛）：recordId={}", record.getId());
                }
            }
        }
        if (republished > 0 || degraded > 0) {
            log.info("降级补偿完成：重发 {} 条，转人工 {} 条", republished, degraded);
        }
    }
}
