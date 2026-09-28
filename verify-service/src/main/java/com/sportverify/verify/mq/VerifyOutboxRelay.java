package com.sportverify.verify.mq;

import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * outbox 事件投递 relay（F03 本地消息表补偿）——判定事件的**唯一**发送出口。
 *
 * <p>定时扫 verify_event_outbox 的 PENDING 行 → 经 {@link VerifyEventProducer#syncSend} 投递
 * （行内 topic/tag/payload/eventId 原样使用，traceId 透传）→ 成功标 SENT，
 * 失败 retry_count+1 留下轮；取批按当前上限 {@code verify.outbox.max-retry}（默认 16）过滤，
 * 即 {@code retry_count < max-retry} 才算合格，耗尽行不占发送批次（避免较小 ID 的耗尽行
 * 永久遮挡后续可投递行），行本身保留供人工处理（不重投、不删除）。</p>
 *
 * <p>多实例防重：Redisson 锁 {@code verify:outbox:relay}，tryLock(0 等待)——
 * 拿不到锁说明另一实例正在跑，直接跳过本轮（周期短，无需等待）。</p>
 *
 * <p>判定链路不直发（见 VerifyOutboxService）：无积压时事件到达延迟下限由本 relay 周期（默认 5s）决定；
 * 但持续到达率超过 relay 净投递吞吐时延迟由积压主导（实测同 run callback→SENT P50 ≈68.8s ≫ 5s 周期，TASK-143），
 * 榜单侧定时结算纠偏仍是最终一致的兜底。</p>
 *
 * <p>本类同时承担 {@link EnableScheduling}：verify-service 此前无定时任务，
 * 启动类不在本任务改动清单内，故由 relay 自带调度开关。</p>
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class VerifyOutboxRelay {

    /** relay 防重锁键 */
    private static final String LOCK_KEY = "verify:outbox:relay";

    private final VerifyEventOutboxMapper outboxMapper;
    private final VerifyEventProducer verifyEventProducer;
    private final RedissonClient redissonClient;

    /** 单轮批量上限 */
    @Value("${verify.outbox.batch-size:100}")
    private int batchSize;

    /** 最大重试次数：超过后仅告警保留行，供人工排查 */
    @Value("${verify.outbox.max-retry:16}")
    private int maxRetry;

    /**
     * 周期级诊断开关（默认关闭）。开启后仅多出有界、低基数的批次/空轮汇总日志，
     * 不改变取批、锁、eventId、SENT、重试与异常传播语义，也不增加 DB/MQ 调用。
     */
    @Value("${verify.outbox.relay-diagnostics-enabled:false}")
    private boolean relayDiagnosticsEnabled;

    /** 空轮/锁竞争汇总的有界窗口（毫秒）：开启诊断后至多每窗口输出一条累计汇总。 */
    @Value("${verify.outbox.relay-diagnostics-window-ms:10000}")
    private long relayDiagnosticsWindowMs;

    /** 有界诊断（懒建于首轮调用；关闭时所有方法为空操作）。 */
    private volatile RelayDiagnostics diagnostics;

    /**
     * 定时投递一轮（默认 5s；多实例经 Redisson 锁互斥）。
     *
     * <p>诊断计时口径（TASK-146 校正、TASK-147 订正）：当开关<strong>关闭</strong>时不做任何 {@code nanoTime} 采样，
     * 不产生额外 I/O；开启时按行把「发送段」与「标记段」各自实际经过的墙钟<strong>各累计一次</strong>——
     * 发送成功/失败都只记发送段，标记成功/失败都只记标记段，失败分支<strong>不得</strong>从
     * {@code sendStart} 重算而把发送与标记重复归到发送。锁口径分两级：
     * {@code lockProcessingMs} 是<strong>锁内处理段</strong>（取锁成功→批次处理结束，尚不含解锁与随后的摘要输出）；
     * {@code lockHoldMs} 的终点在 {@code unlock()} 调用返回（或抛错被捕获）之后、摘要日志输出<strong>之前</strong>取得，
     * 故含 {@code unlock()} 调用本身、<strong>不含</strong>其后的摘要输出；{@code unlock()} 抛错被捕获时不代表锁已确实释放，
     * 该值不得无条件当作「完整占锁」或已释放的确证。</p>
     */
    @Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}",
            initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")
    public void relay() {
        RelayDiagnostics diag = diagnostics();
        boolean diagEnabled = diag.enabled();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        long lockWaitStart = diagEnabled ? System.nanoTime() : 0L;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox relay 锁获取被中断，跳过本轮");
            return;
        }
        long lockWaitMs = diagEnabled ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lockWaitStart) : 0L;
        if (!locked) {
            diag.lockSkipped().ifPresent(s -> log.info(
                    "outbox relay 诊断（空轮/竞争汇总）：emptyRounds={}, lockSkips={}",
                    s.emptyRounds(), s.lockSkips()));
            return; // 另一实例正在跑，周期短直接跳过
        }
        long holdStart = diagEnabled ? System.nanoTime() : 0L;
        List<VerifyEventOutbox> batch = null;
        long selectNanos = 0L;
        long sendNanos = 0L;
        long markNanos = 0L;
        long incrRetryNanos = 0L;
        long processingNanos = 0L;
        int success = 0;
        int failed = 0;
        int exhausted = 0;
        boolean completed = false;
        try {
            long selectStart = diagEnabled ? System.nanoTime() : 0L;
            batch = outboxMapper.selectPendingBatch(batchSize, maxRetry);
            if (diagEnabled) {
                selectNanos = System.nanoTime() - selectStart;
            }
            for (VerifyEventOutbox row : batch) {
                // 防御性兜底：取批 SQL 已按当前上限过滤耗尽行；仅当运行中上限被下调等极端情况下
                // 本批仍可能含新耗尽行，此时保留行、不投递不计数。
                if (row.getRetryCount() != null && row.getRetryCount() >= maxRetry) {
                    exhausted++;
                    log.error("outbox 事件超过最大重试次数，保留行供人工处理：id={}, eventId={}, topic={}, tag={}, retryCount={}",
                            row.getId(), row.getEventId(), row.getTopic(), row.getTag(), row.getRetryCount());
                    continue;
                }
                // 发送段：本行实际经过的发送墙钟只累计一次（成功失败都只记这一段，不在失败分支重算）
                long sendStart = diagEnabled ? System.nanoTime() : 0L;
                try {
                    // 唯一投递出口：行内 topic/tag/payload/eventId 原样交给生产者（重发不换 eventId）
                    verifyEventProducer.syncSend(row);
                } catch (Exception e) {
                    if (diagEnabled) {
                        sendNanos += System.nanoTime() - sendStart;
                    }
                    long incrStart = diagEnabled ? System.nanoTime() : 0L;
                    outboxMapper.incrRetry(row.getId());
                    if (diagEnabled) {
                        incrRetryNanos += System.nanoTime() - incrStart;
                    }
                    failed++;
                    log.warn("outbox 事件投递失败，下轮重试：id={}, eventId={}, retryCount={}",
                            row.getId(), row.getEventId(),
                            row.getRetryCount() == null ? 1 : row.getRetryCount() + 1, e);
                    continue;
                }
                if (diagEnabled) {
                    sendNanos += System.nanoTime() - sendStart;
                }
                // 标记段：标记成功/失败尝试各自只累计本段墙钟一次，不与发送段互相重复归集
                long markStart = diagEnabled ? System.nanoTime() : 0L;
                try {
                    outboxMapper.markSent(row.getId());
                    if (diagEnabled) {
                        markNanos += System.nanoTime() - markStart;
                    }
                    success++;
                    log.info("outbox 事件投递成功：id={}, eventId={}, tag={}",
                            row.getId(), row.getEventId(), row.getTag());
                } catch (Exception e) {
                    if (diagEnabled) {
                        markNanos += System.nanoTime() - markStart;
                    }
                    long incrStart = diagEnabled ? System.nanoTime() : 0L;
                    outboxMapper.incrRetry(row.getId());
                    if (diagEnabled) {
                        incrRetryNanos += System.nanoTime() - incrStart;
                    }
                    failed++;
                    log.warn("outbox 事件投递失败，下轮重试：id={}, eventId={}, retryCount={}",
                            row.getId(), row.getEventId(),
                            row.getRetryCount() == null ? 1 : row.getRetryCount() + 1, e);
                }
            }
            if (diagEnabled) {
                // 锁内处理段终点：批次处理结束，尚未输出摘要、尚未解锁
                processingNanos = System.nanoTime() - holdStart;
            }
            completed = true;
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("outbox relay 锁释放异常（租期兜底释放）");
            }
            if (completed && diagEnabled) {
                // lockHoldMs 终点：解锁调用返回（或抛错被捕获）之后、摘要输出之前取；含解锁调用、不含其后的摘要输出；
                // 解锁抛错不代表已释放，不得混称「完整占锁」或当作锁已释放的确证
                long lockHoldNanos = System.nanoTime() - holdStart;
                if (batch.isEmpty()) {
                    diag.emptyRound().ifPresent(s -> log.info(
                            "outbox relay 诊断（空轮/竞争汇总）：emptyRounds={}, lockSkips={}",
                            s.emptyRounds(), s.lockSkips()));
                } else {
                    diag.batch(batch.size(), success, failed, exhausted, lockWaitMs,
                                    TimeUnit.NANOSECONDS.toMillis(selectNanos),
                                    TimeUnit.NANOSECONDS.toMillis(sendNanos),
                                    TimeUnit.NANOSECONDS.toMillis(markNanos),
                                    TimeUnit.NANOSECONDS.toMillis(incrRetryNanos),
                                    TimeUnit.NANOSECONDS.toMillis(processingNanos),
                                    TimeUnit.NANOSECONDS.toMillis(lockHoldNanos))
                            .ifPresent(s -> log.info(
                                    "outbox relay 诊断（批次）：rows={}, success={}, failed={}, exhausted={}, "
                                            + "lockWaitMs={}, selectMs={}, sendMs={}, markMs={}, incrRetryMs={}, "
                                            + "lockProcessingMs={}, lockHoldMs={}, residualMs={}, emptyRounds={}, lockSkips={}",
                                    s.rows(), s.success(), s.failed(), s.exhausted(), s.lockWaitMs(),
                                    s.selectMs(), s.sendMs(), s.markMs(), s.incrRetryMs(), s.lockProcessingMs(),
                                    s.lockHoldMs(), s.residualMs(), s.emptyRounds(), s.lockSkips()));
                }
            }
        }
    }

    private RelayDiagnostics diagnostics() {
        RelayDiagnostics d = diagnostics;
        if (d == null) {
            d = new RelayDiagnostics(relayDiagnosticsEnabled,
                    Math.max(1L, TimeUnit.MILLISECONDS.toNanos(relayDiagnosticsWindowMs)),
                    System::nanoTime);
            diagnostics = d;
        }
        return d;
    }
}
