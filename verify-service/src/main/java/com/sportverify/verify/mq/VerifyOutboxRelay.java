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
 * <p>判定链路不直发（见 VerifyOutboxService）：事件到达延迟由本 relay 周期（默认 5s）决定，
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

    /** 定时投递一轮（默认 5s；多实例经 Redisson 锁互斥） */
    @Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}",
            initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")
    public void relay() {
        RelayDiagnostics diag = diagnostics();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        long lockWaitStart = System.nanoTime();
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox relay 锁获取被中断，跳过本轮");
            return;
        }
        long lockWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lockWaitStart);
        if (!locked) {
            diag.lockSkipped().ifPresent(s -> log.info(
                    "outbox relay 诊断（空轮/竞争汇总）：emptyRounds={}, lockSkips={}",
                    s.emptyRounds(), s.lockSkips()));
            return; // 另一实例正在跑，周期短直接跳过
        }
        long holdStart = System.nanoTime();
        try {
            long selectStart = System.nanoTime();
            List<VerifyEventOutbox> batch = outboxMapper.selectPendingBatch(batchSize, maxRetry);
            long selectNanos = System.nanoTime() - selectStart;

            long sendNanos = 0L;
            long markNanos = 0L;
            long incrRetryNanos = 0L;
            int success = 0;
            int failed = 0;
            int exhausted = 0;
            for (VerifyEventOutbox row : batch) {
                // 防御性兜底：取批 SQL 已按当前上限过滤耗尽行；仅当运行中上限被下调等极端情况下
                // 本批仍可能含新耗尽行，此时保留行、不投递不计数。
                if (row.getRetryCount() != null && row.getRetryCount() >= maxRetry) {
                    exhausted++;
                    log.error("outbox 事件超过最大重试次数，保留行供人工处理：id={}, eventId={}, topic={}, tag={}, retryCount={}",
                            row.getId(), row.getEventId(), row.getTopic(), row.getTag(), row.getRetryCount());
                    continue;
                }
                long sendStart = System.nanoTime();
                try {
                    // 唯一投递出口：行内 topic/tag/payload/eventId 原样交给生产者（重发不换 eventId）
                    verifyEventProducer.syncSend(row);
                    sendNanos += System.nanoTime() - sendStart;
                    long markStart = System.nanoTime();
                    outboxMapper.markSent(row.getId());
                    markNanos += System.nanoTime() - markStart;
                    success++;
                    log.info("outbox 事件投递成功：id={}, eventId={}, tag={}",
                            row.getId(), row.getEventId(), row.getTag());
                } catch (Exception e) {
                    sendNanos += System.nanoTime() - sendStart;
                    long incrStart = System.nanoTime();
                    outboxMapper.incrRetry(row.getId());
                    incrRetryNanos += System.nanoTime() - incrStart;
                    failed++;
                    log.warn("outbox 事件投递失败，下轮重试：id={}, eventId={}, retryCount={}",
                            row.getId(), row.getEventId(),
                            row.getRetryCount() == null ? 1 : row.getRetryCount() + 1, e);
                }
            }
            long lockHoldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdStart);
            if (batch.isEmpty()) {
                diag.emptyRound().ifPresent(s -> log.info(
                        "outbox relay 诊断（空轮/竞争汇总）：emptyRounds={}, lockSkips={}",
                        s.emptyRounds(), s.lockSkips()));
            } else {
                diag.batch(batch.size(), success, failed, exhausted, lockWaitMs,
                                TimeUnit.NANOSECONDS.toMillis(selectNanos),
                                TimeUnit.NANOSECONDS.toMillis(sendNanos),
                                TimeUnit.NANOSECONDS.toMillis(markNanos),
                                TimeUnit.NANOSECONDS.toMillis(incrRetryNanos), lockHoldMs)
                        .ifPresent(s -> log.info(
                                "outbox relay 诊断（批次）：rows={}, success={}, failed={}, exhausted={}, "
                                        + "lockWaitMs={}, selectMs={}, sendMs={}, markMs={}, incrRetryMs={}, "
                                        + "lockHoldMs={}, residualMs={}, emptyRounds={}, lockSkips={}",
                                s.rows(), s.success(), s.failed(), s.exhausted(), s.lockWaitMs(),
                                s.selectMs(), s.sendMs(), s.markMs(), s.incrRetryMs(), s.lockHoldMs(),
                                s.residualMs(), s.emptyRounds(), s.lockSkips()));
            }
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("outbox relay 锁释放异常（租期兜底释放）");
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
