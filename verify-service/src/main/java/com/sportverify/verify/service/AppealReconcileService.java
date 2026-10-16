package com.sportverify.verify.service;

import com.sportverify.api.record.RecordApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.verify.AppealStatus;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.verify.entity.Appeal;
import com.sportverify.verify.mapper.AppealMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 申诉终判偏斜兜底对账服务（TASK-253 R2/E6）。
 *
 * <p><b>背景</b>：{@link VerifyService} 的终判链为「本地事务终判 appeal + outbox 事件
 * → verdict 回写 → {@code recordApi.getRecord} → statusCallback（Feign）」，本地事务提交后任一
 * Feign 失败，record 将永久卡 {@code APPEALING}：record-service 无任何 verify 事件消费者，
 * 状态迁移唯一路径是同步 Feign，且 {@code VerifyService.reconcileCallback} 白名单仅
 * VERIFYING/MANUAL_REVIEW、不覆盖 APPEALING。</p>
 *
 * <p><b>职责</b>：定时批扫终判申诉单（status IN (1,2)），逐行核对 record 是否仍卡
 * APPEALING——恰为 APPEALING 才按申诉结果补回调迁移（RE_PASSED / RE_CONFIRMED），
 * 其他任何状态（含已迁移终态）跳过零回调。</p>
 *
 * <p><b>与 {@code processAppeal} 的关系</b>（行为不变）：终判路径的同步回调失败仍抛异常供人工
 * 终判界面即时感知（{@code reviewAppeal} 零改动）；本服务只兜底历史偏斜，不参与终判主链路，
 * 最长修复延迟 = 一个对账周期（10min）。</p>
 *
 * <p><b>幂等闭环</b>：修复经 record 端乐观锁（fromStatus=APPEALING + version）回调，修复成功后
 * record 非 APPEALING，下轮扫描天然跳过；单行 Feign 异常/非 0 响应 catch 记日志后继续批内其余，
 * 遗留行下轮重扫自愈。多实例经 Redisson 锁 {@code verify:appeal:reconcile} 互斥（仅一个执行），
 * 锁服务异常跳过本轮（可用性优先）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealReconcileService {

    /** 对账周期：10min（低频兜底，启动后先给同步回调留窗口） */
    private static final long RECONCILE_FIXED_DELAY_MS = 10 * 60_000;
    /** 首跑延迟：2min（TASK-253 新增常量，非修改既有参数） */
    private static final long RECONCILE_INITIAL_DELAY_MS = 2 * 60_000;
    /** 单批扫描上限：100（TASK-253 新增常量，终判为低频人工流，批扫即可） */
    private static final int BATCH_LIMIT = 100;
    /** 防重锁键（多实例仅一个执行，沿 settleAndReconcile / reconcileLikeCounts 先例） */
    private static final String RECONCILE_LOCK_KEY = "verify:appeal:reconcile";
    /** 锁等待上限（秒）：拿不到锁直接跳过本轮（下一轮再试） */
    private static final long LOCK_WAIT_SECONDS = 3;

    private final AppealMapper appealMapper;
    private final RecordApi recordApi;
    private final RedissonClient redissonClient;

    /**
     * 兜底对账一轮：批扫终判申诉单 → 逐行核对修复（单行失败不阻断批内其余）。
     */
    @Scheduled(fixedDelay = RECONCILE_FIXED_DELAY_MS, initialDelay = RECONCILE_INITIAL_DELAY_MS)
    void reconcile() {
        RLock lock = redissonClient.getLock(RECONCILE_LOCK_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("申诉对账锁获取被中断，本轮跳过");
            return;
        } catch (Exception e) {
            // 锁服务异常跳过本轮（可用性优先，沿 settleAndReconcile 先例）
            log.warn("申诉对账锁服务异常，本轮跳过", e);
            return;
        }
        if (!locked) {
            log.debug("申诉对账锁被其他实例持有，本轮跳过");
            return;
        }
        try {
            List<Appeal> batch = appealMapper.selectFinalizedBatch(BATCH_LIMIT);
            int repaired = 0;
            int skipped = 0;
            for (Appeal appeal : batch) {
                try {
                    if (repairIfSkewed(appeal)) {
                        repaired++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // 单行 Feign 异常/非 0 响应 → 记日志继续批内其余（下轮重扫自愈）
                    log.error("申诉终判对账单行修复失败：appealId={}, recordId={}",
                            appeal.getId(), appeal.getRecordId(), e);
                }
            }
            log.info("申诉终判对账完成：扫描 {} 条，修复 {} 条，跳过 {} 条",
                    batch.size(), repaired, skipped);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 单行修复：record 恰为 APPEALING 才回调迁移（乐观锁 fromStatus=APPEALING + version）。
     *
     * @return true = 本行已修复；false = 无需修复（record 已迁移/其他状态/不存在）
     * @throws BizException 回调非 0 响应（交由调用方 catch 记日志继续批内）
     */
    private boolean repairIfSkewed(Appeal appeal) {
        Result<SportRecordDTO> resp = recordApi.getRecord(appeal.getRecordId());
        SportRecordDTO record = resp == null ? null : resp.getData();
        if (record == null || record.getStatus() == null) {
            return false;
        }
        // 恰为 APPEALING 才修复；其他任何状态（含已迁移 RE_PASSED/RE_CONFIRMED）跳过零回调
        if (record.getStatus() != RecordStatus.APPEALING.getCode()) {
            return false;
        }
        int toStatus = appeal.getStatus() == AppealStatus.RE_PASSED.getCode()
                ? RecordStatus.RE_PASSED.getCode()
                : RecordStatus.RE_CONFIRMED.getCode();
        Result<Void> callback = recordApi.statusCallback(appeal.getRecordId(),
                new StatusCallbackDTO(appeal.getRecordId(), RecordStatus.APPEALING.getCode(),
                        toStatus, record.getVersion()));
        if (callback == null || callback.getCode() != 0) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID,
                    "申诉终判对账回调失败：" + (callback == null ? "无响应" : callback.getMessage()));
        }
        log.info("申诉终判对账修复：appealId={}, recordId={}, APPEALING → {}",
                appeal.getId(), appeal.getRecordId(),
                toStatus == RecordStatus.RE_PASSED.getCode() ? "RE_PASSED" : "RE_CONFIRMED");
        return true;
    }
}
