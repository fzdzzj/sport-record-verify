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
 * 确认、连接获取与客户端等待），不得当作纯 MQ 或纯 SQL 执行耗时。锁口径分两级、不得混称：
 * {@code lockProcessingMs} 为<strong>锁内处理段</strong>（取锁成功→批次处理结束，尚不含解锁与随后的摘要输出）；
 * {@code lockHoldMs} 的终点在 {@code unlock()} 调用返回（或抛错被捕获）之后、摘要日志输出<strong>之前</strong>取得，
 * 故含 {@code unlock()} 调用本身、不含其后的摘要输出；{@code unlock()} 抛错被捕获时不代表锁已确实释放，
 * 该值不得无条件称为「完整占锁」或当作已释放的确证；
 * {@code residualMs} = 锁内处理段减去各段合计后的剩余量（串行下钳到非负）。
 * 并发投递（{@code sendConcurrency} &gt; 1）时 sendMs/markMs/incrRetryMs
 * 是<strong>各线程墙钟的聚合和（线程时间）</strong>，不再是单条时间轴上的墙钟，
 * 故此时 {@code residualMs} 不再钳非负、<strong>可能为负</strong>，
 * 不得读作「未归因的墙钟」.</p>
 */
final class RelayDiagnostics {

    /**
     * 一次非空批次的耗时与结果汇总（毫秒）.
     *
     * <p>{@code lockProcessingMs} = 锁内处理段；{@code lockHoldMs} = 取锁成功到 {@code unlock()} 调用之后（返回或抛错被捕获）、
     * 摘要输出之前的经过时间（含解锁调用、不含摘要输出；解锁抛错不代表已释放）；
     * {@code residualMs} = 锁内处理段 - (取批 + 发送 + 标记 + 失败计数)，串行下非负；
     * {@code sendConcurrency} = 本批生效的投递并发数：为 1 时段值是主线程墙钟、
     * residualMs 钳非负（既有语义不变）；&gt; 1 时 sendMs/markMs/incrRetryMs 是
     * 各线程墙钟的聚合和（线程时间），residualMs 可能为负，
     * 不得读作「未归因的墙钟」.</p>
     *
     * @param rows             本批行数
     * @param success          成功行数
     * @param failed           失败行数
     * @param exhausted        耗尽行数
     * @param lockWaitMs       取锁等待耗时
     * @param selectMs         取批耗时
     * @param sendMs           发送段耗时（并发下为线程时间聚合）
     * @param markMs           标记段耗时（并发下为线程时间聚合）
     * @param incrRetryMs      失败计数段耗时（并发下为线程时间聚合）
     * @param lockProcessingMs 锁内处理段耗时
     * @param lockHoldMs       含解锁调用的持锁耗时
     * @param residualMs       残差（串行钳非负；并发可为负）
     * @param emptyRounds      自上次汇总以来的空轮数
     * @param lockSkips        自上次汇总以来的锁竞争次数
     * @param sendConcurrency  本批生效的投递并发数
     */
    record BatchSummary(int rows, int success, int failed, int exhausted,
                        long lockWaitMs, long selectMs, long sendMs, long markMs,
                        long incrRetryMs, long lockProcessingMs, long lockHoldMs,
                        long residualMs, long emptyRounds, long lockSkips,
                        int sendConcurrency) {
    }

    /**
     * 空轮/锁竞争的有界汇总（自上次输出以来的累计计数）.
     *
     * @param emptyRounds 自上次汇总以来的空轮数
     * @param lockSkips   自上次汇总以来的锁竞争次数
     */
    record IdleSummary(long emptyRounds, long lockSkips) {
    }

    private final boolean enabled;
    private final long windowNanos;
    private final LongSupplier clock;

    private long emptyRounds;
    private long lockSkips;
    private Long lastIdleNanos;

    RelayDiagnostics(final boolean enabled, final long windowNanos, final LongSupplier clock) {
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
     * 非空批次：输出一条汇总（含自上次汇总以来的空轮/竞争计数），并把计数归零.
     *
     * @param rows             本批行数
     * @param success          成功行数
     * @param failed           失败行数
     * @param exhausted        耗尽行数
     * @param lockWaitMs       取锁等待耗时
     * @param selectMs         取批耗时
     * @param sendMs           发送段耗时（并发下为线程时间聚合）
     * @param markMs           标记段耗时（并发下为线程时间聚合）
     * @param incrRetryMs      失败计数段耗时（并发下为线程时间聚合）
     * @param lockProcessingMs 锁内处理段（取锁成功→批次处理结束，不含解锁与随后的摘要输出）
     * @param lockHoldMs       取锁成功→{@code unlock()} 调用之后（返回或抛错被捕获）、摘要输出之前的经过时间（含解锁调用，不含摘要输出）
     * @param sendConcurrency  本批生效的投递并发数（1 = 串行，各段为主线程墙钟）
     * @return 关闭时为空；开启时各段耗时非负，residualMs 串行下钳非负（既有口径）、
     *         并发下为线程时间聚合的剩余量，可能为负（见类注）
     */
    Optional<BatchSummary> batch(final int rows, final int success, final int failed,
                                 final int exhausted,
                                 final long lockWaitMs, final long selectMs, final long sendMs,
                                 final long markMs, final long incrRetryMs,
                                 final long lockProcessingMs, final long lockHoldMs,
                                 final int sendConcurrency) {
        if (!enabled) {
            return Optional.empty();
        }
        long residualMs = lockProcessingMs - (selectMs + sendMs + markMs + incrRetryMs);
        if (sendConcurrency == 1) {
            // 串行：各段是同一时钟嵌套区间的子段，剩余量恒非负；钳制仅作既有防御，口径不变。
            // 并发下段值为各线程墙钟的聚合和（线程时间），可超锁内墙钟，不得钳负——
            // 否则「residualMs 可能为负，不得读作未归因墙钟」的并发口径就无从表达。
            residualMs = Math.max(0L, residualMs);
        }
        BatchSummary summary = new BatchSummary(rows, success, failed, exhausted,
                lockWaitMs, selectMs, sendMs, markMs, incrRetryMs, lockProcessingMs, lockHoldMs,
                residualMs, emptyRounds, lockSkips, sendConcurrency);
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
