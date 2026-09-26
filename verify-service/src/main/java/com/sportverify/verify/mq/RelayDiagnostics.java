package com.sportverify.verify.mq;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * outbox relay 周期级诊断（默认关闭、有界、低基数）。
 *
 * <p>只汇总单调时钟下的批内分段耗时与行数计数，不参与取批/投递/标记/重试判定，
 * 也不输出 payload、eventId、用户标识或密钥；关闭时所有方法立即返回空，不产生输出，
 * 不触发额外 DB/MQ 调用，也不改变 {@link VerifyOutboxRelay} 的原有语义。</p>
 *
 * <p>有界性：非空批次每个批次至多一条汇总；空轮与锁竞争按固定窗口（{@code windowNanos}）
 * 至多一条累计汇总，窗口内只累加计数，不逐轮打日志。计数在任一汇总输出后归零。</p>
 *
 * <p>耗时口径：{@code syncSend} 与 Mapper 各段是<strong>混合墙钟</strong>（含序列化、网络/代理、
 * 确认、连接获取与客户端等待），不得当作纯 MQ 或纯 SQL 执行耗时；{@code residualMs} 是
 * 锁持有总墙钟减去各段合计后的剩余量。</p>
 */
final class RelayDiagnostics {

    /** 一次非空批次的耗时与结果汇总（毫秒；residual = 锁持有 - 各段合计，非负）。 */
    record BatchSummary(int rows, int success, int failed, int exhausted,
                        long lockWaitMs, long selectMs, long sendMs, long markMs,
                        long incrRetryMs, long lockHoldMs, long residualMs,
                        long emptyRounds, long lockSkips) {
    }

    /** 空轮/锁竞争的有界汇总（自上次输出以来的累计计数）。 */
    record IdleSummary(long emptyRounds, long lockSkips) {
    }

    private final boolean enabled;
    private final long windowNanos;
    private final LongSupplier clock;

    private long emptyRounds;
    private long lockSkips;
    private Long lastIdleNanos;

    RelayDiagnostics(boolean enabled, long windowNanos, LongSupplier clock) {
        this.enabled = enabled;
        this.windowNanos = windowNanos;
        this.clock = clock;
    }

    boolean enabled() {
        return enabled;
    }

    /** 拿不到防重锁的一轮：累加计数，窗口到点才输出累计汇总。 */
    Optional<IdleSummary> lockSkipped() {
        if (!enabled) {
            return Optional.empty();
        }
        lockSkips++;
        return emitIdleIfDue();
    }

    /** 空批一轮：累加计数，窗口到点才输出累计汇总。 */
    Optional<IdleSummary> emptyRound() {
        if (!enabled) {
            return Optional.empty();
        }
        emptyRounds++;
        return emitIdleIfDue();
    }

    /**
     * 非空批次：输出一条汇总（含自上次汇总以来的空轮/竞争计数），并把计数归零。
     *
     * @return 关闭时为空；开启时稳定给出非负分段耗时
     */
    Optional<BatchSummary> batch(int rows, int success, int failed, int exhausted,
                                 long lockWaitMs, long selectMs, long sendMs, long markMs,
                                 long incrRetryMs, long lockHoldMs) {
        if (!enabled) {
            return Optional.empty();
        }
        long residualMs = lockHoldMs - (selectMs + sendMs + markMs + incrRetryMs);
        BatchSummary summary = new BatchSummary(rows, success, failed, exhausted,
                lockWaitMs, selectMs, sendMs, markMs, incrRetryMs, lockHoldMs,
                Math.max(0L, residualMs), emptyRounds, lockSkips);
        emptyRounds = 0;
        lockSkips = 0;
        lastIdleNanos = clock.getAsLong();
        return Optional.of(summary);
    }

    private Optional<IdleSummary> emitIdleIfDue() {
        long now = clock.getAsLong();
        if (lastIdleNanos != null && now - lastIdleNanos < windowNanos) {
            return Optional.empty();
        }
        lastIdleNanos = now;
        IdleSummary summary = new IdleSummary(emptyRounds, lockSkips);
        emptyRounds = 0;
        lockSkips = 0;
        return Optional.of(summary);
    }
}