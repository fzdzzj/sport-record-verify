package com.sportverify.verify.mq;

import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>批内并发投递（TASK-160，默认关闭）：{@code verify.outbox.relay-send-concurrency}
 * 为 1（默认）时不创建任何线程池/线程，走与引入前逐字等价的串行循环；大于 1 时才在
 * 首次使用时懒建固定大小的 daemon 线程池，把<strong>已取到的批次</strong>按列表下标
 * {@code i % N} 切成不重不漏的 N 份并行投递（取批 SQL、批次上限、重试上限、锁与周期
 * 全部不变，整批仍在 Redisson 锁内完成）。串行与并发共用同一个单行处理体，
 * 逐行可靠投递语义只有一份实现.</p>
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

    /** 发送线程池停止时的有界等待秒数（超时即 shutdownNow）. */
    private static final long SEND_POOL_TERMINATION_WAIT_SECONDS = 10L;

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
     * 批内并发投递数（默认 1 = 串行，不创建任何线程池对象，与引入前逐字等价）.
     *
     * <p>不设上限，但连接池默认 10（application.yml 未设 hikari maximum-pool-size）、
     * RocketMQ 消费线程 32~40，N 个 relay worker 与消费者争同一个池；
     * 实用上界受连接池约束，未经同负载 A/B 测量不得调大.</p>
     */
    @Value("${verify.outbox.relay-send-concurrency:1}")
    private int relaySendConcurrency;

    /** 批内并发发送线程池（懒建，仅并发 &gt; 1 时存在；串行路径永远为 null）. */
    private volatile ExecutorService sendExecutor;

    /** 配置值无效（&lt; 1）的钳位告警只打一条. */
    private volatile boolean sendConcurrencyWarned;

    /**
     * 分块批量标记 SENT 开关（默认 false 关闭）.
     *
     * <p>默认关闭：关闭时与引入前逐字等价，不调用 {@code markSentBatch}，逐行调用 {@code markSent}。
     * 开启后将成功发送行的 id 累积到局部列表，每满 chunk 或批末一次性批量标记。
     * 授权语义变化包括：崩溃重复投递窗口上界由 1 行扩大为 chunk-size；SENT 独立连接可见性推迟至 chunk 标记；
     * sent_at 批内同值偏离 DDL 注释；chunk 标记 SQL 真失败整块重投并逐 id incrRetry。
     * 与 relay-send-concurrency 正交，未经生产基线与锁内吞吐判别不得开启.</p>
     */
    @Value("${verify.outbox.relay-batch-mark-enabled:false}")
    private boolean relayBatchMarkEnabled;

    /**
     * 分块批量标记的 chunk 上界大小（默认 25，钳位在 [1, batch-size]）.
     *
     * <p>非法配置值（&lt; 1 或 &gt; batchSize）自动钳位到有效区间并记录一次 WARN，绝不抛出异常。
     * 每个 chunk 内发送成功的行数达到此值时立即执行一次 markSentBatch.</p>
     */
    @Value("${verify.outbox.relay-batch-mark-chunk-size:25}")
    private int relayBatchMarkChunkSize;

    /** 配置值无效（&lt; 1 或 &gt; batch-size）的钳位告警只打一条. */
    private volatile boolean batchMarkChunkSizeWarned;

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
     *
     * <p>并发投递口径（TASK-160）：{@code relay-send-concurrency}
     * &gt; 1 时批内并发；此时 {@code sendMs/markMs/incrRetryMs}
     * 是<strong>各线程墙钟的聚合和（线程时间）</strong>，
     * 不再是单条时间轴上的墙钟，因此
     * {@code residualMs = lockProcessingMs − Σ段} <strong>可能为负</strong>，
     * 不得再读作「未归因的墙钟」（TASK-146 同类归因错误的
     * 复发预防）。{@code selectNanos/lockWaitMs/lockProcessingMs/lockHoldMs}
     * 仍由主线程单点计时，口径不变.</p>
     */
    @Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}",
            initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")
    public void relay() {
        RelayDiagnostics diag = diagnostics();
        boolean diagEnabled = diag.enabled();
        int sendConcurrency = effectiveSendConcurrency();
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
        RelayTotals totals = new RelayTotals();
        long processingNanos = 0L;
        boolean completed = false;
        try {
            long selectStart = diagEnabled ? System.nanoTime() : 0L;
            batch = outboxMapper.selectPendingBatch(batchSize, maxRetry);
            if (diagEnabled) {
                selectNanos = System.nanoTime() - selectStart;
            }
            if (!relayBatchMarkEnabled) {
                // 串行路径：与引入前逐字等价（sendConcurrency==1 时不创建任何线程池对象）
                if (batch.isEmpty() || sendConcurrency == 1) {
                    for (VerifyEventOutbox row : batch) {
                        sendRow(row, diagEnabled, totals);
                    }
                } else {
                    deliverConcurrently(batch, sendConcurrency,
                            diagEnabled, totals);
                }
            } else {
                int chunkSize = effectiveBatchMarkChunkSize();
                if (batch.isEmpty() || sendConcurrency == 1) {
                    deliverBatchMarkSerial(batch, chunkSize,
                            diagEnabled, totals);
                } else {
                    deliverBatchMarkConcurrently(batch, sendConcurrency,
                            chunkSize, diagEnabled, totals);
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
                    String concurrencyNote = "";
                    if (sendConcurrency > 1) {
                        concurrencyNote = "；并发下 sendMs/markMs/incrRetryMs"
                                + " 为各线程墙钟的聚合和（线程时间），"
                                + "residualMs 可能为负，不得读作未归因墙钟";
                    }
                    String batchMarkNote = "";
                    if (relayBatchMarkEnabled) {
                        batchMarkNote = String.format(
                                ", markBatchCalls=%d, markBatchRows=%d",
                                totals.markBatchCalls, totals.markBatchRows);
                    }
                    String summaryFormat = "outbox relay 诊断（批次）：rows={}, "
                            + "success={}, failed={}, exhausted={}, "
                            + "lockWaitMs={}, selectMs={}, sendMs={}, "
                            + "markMs={}, incrRetryMs={}, "
                            + "lockProcessingMs={}, lockHoldMs={}, "
                            + "residualMs={}, "
                            + "emptyRounds={}, lockSkips={}, sendConcurrency={}"
                            + concurrencyNote
                            + batchMarkNote;
                    diag.batch(batch.size(), totals.success, totals.failed,
                            totals.exhausted, lockWaitMs,
                            TimeUnit.NANOSECONDS.toMillis(selectNanos),
                            TimeUnit.NANOSECONDS.toMillis(totals.sendNanos),
                            TimeUnit.NANOSECONDS.toMillis(totals.markNanos),
                            TimeUnit.NANOSECONDS.toMillis(
                                     totals.incrRetryNanos),
                            TimeUnit.NANOSECONDS.toMillis(processingNanos),
                            TimeUnit.NANOSECONDS.toMillis(lockHoldNanos),
                            sendConcurrency)
                            .ifPresent(s -> log.info(summaryFormat,
                                    s.rows(), s.success(), s.failed(),
                                    s.exhausted(), s.lockWaitMs(), s.selectMs(),
                                    s.sendMs(), s.markMs(), s.incrRetryMs(),
                                    s.lockProcessingMs(), s.lockHoldMs(),
                                    s.residualMs(), s.emptyRounds(),
                                    s.lockSkips(),
                                    s.sendConcurrency()));
                }
            }
        }
    }

    /**
     * 生效并发数：配置 &lt; 1 时钳到 1（仅首轮打一条 WARN，不抛异常导致服务起不来），默认 1.
     *
     * @return 生效并发数（非法配置钳位后的值）
     */
    private int effectiveSendConcurrency() {
        int configured = relaySendConcurrency;
        if (configured >= 1) {
            return configured;
        }
        if (!sendConcurrencyWarned) {
            sendConcurrencyWarned = true;
            log.warn("verify.outbox.relay-send-concurrency={} 无效（< 1），"
                    + "已钳到 1 走串行投递", configured);
        }
        return 1;
    }

    /**
     * 生效分块大小：配置值无效（&lt; 1 或 &gt; batchSize）钳位到 [1, batchSize]，仅首轮打一条 WARN.
     *
     * @return 生效 chunk 大小
     */
    private int effectiveBatchMarkChunkSize() {
        int configured = relayBatchMarkChunkSize;
        int max = Math.max(1, batchSize);
        if (configured < 1) {
            if (!batchMarkChunkSizeWarned) {
                batchMarkChunkSizeWarned = true;
                log.warn("verify.outbox.relay-batch-mark-chunk-size={} 无效（< 1），"
                        + "已钳到 1", configured);
            }
            return 1;
        }
        if (configured > max) {
            if (!batchMarkChunkSizeWarned) {
                batchMarkChunkSizeWarned = true;
                log.warn("verify.outbox.relay-batch-mark-chunk-size={} 无效（> batch-size={}），"
                        + "已钳到 {}", configured, max, max);
            }
            return max;
        }
        return configured;
    }

    /**
     * 统一的单行处理体：串行与并发、逐行标记与分块标记四条投递路径共用的唯一实现，
     * 逐行可靠投递语义只有一份，不存在漂移.
     *
     * <p>三段落结构：① 耗尽行防御性拦截；② 发送段；③ 标记段.逐行不变式：先恰好
     * 一次 {@code syncSend}，发送失败恰好一次 {@code incrRetry} 后跳过本行；标记段
     * {@code pendingIds == null} 时成功后恰好一次 {@code markSent}、抛错恰好一次
     * {@code incrRetry}，否则成功行 id 累积到 {@code pendingIds} 且满 {@code chunkSize}
     * 调 {@code flushBatchMark}；耗尽行不投递不标记仅告警；eventId 永不重新生成、
     * topic/tag/payload/traceId 原样透传.</p>
     *
     * @param row         本行 outbox 数据（原样透传给生产者与 Mapper）
     * @param chunkSize   分块标记 chunk 大小（逐行标记模式下忽略）
     * @param pendingIds  分块标记局部待标记 id 列表（逐行标记模式传 {@code null}）
     * @param diagEnabled 诊断开关（关闭时不做任何 nanoTime 采样）
     * @param totals      累计器（串行为主线程直用，并发为 worker 局部实例）
     */
    private void sendRow(final VerifyEventOutbox row, final int chunkSize,
                         final List<Long> pendingIds, final boolean diagEnabled,
                         final RelayTotals totals) {
        // 防御性兜底：取批 SQL 已按当前上限过滤耗尽行；仅当运行中上限被下调等极端情况下
        // 本批仍可能含新耗尽行，此时保留行、不投递不计数。
        if (row.getRetryCount() != null && row.getRetryCount() >= maxRetry) {
            totals.exhausted++;
            log.error("outbox 事件超过最大重试次数，保留行供人工处理："
                    + "id={}, eventId={}, topic={}, tag={}, retryCount={}",
                    row.getId(), row.getEventId(), row.getTopic(),
                    row.getTag(), row.getRetryCount());
            return;
        }
        // 发送段：本行实际经过的发送墙钟只累计一次（成功失败都只记这一段，不在失败分支重算）
        long sendStart = diagEnabled ? System.nanoTime() : 0L;
        try {
            // 唯一投递出口：行内 topic/tag/payload/eventId 原样交给生产者（重发不换 eventId）
            verifyEventProducer.syncSend(row);
        } catch (Exception e) {
            if (diagEnabled) {
                totals.sendNanos += System.nanoTime() - sendStart;
            }
            long incrStart = diagEnabled ? System.nanoTime() : 0L;
            outboxMapper.incrRetry(row.getId());
            if (diagEnabled) {
                totals.incrRetryNanos += System.nanoTime() - incrStart;
            }
            totals.failed++;
            log.warn("outbox 事件投递失败，下轮重试：id={}, eventId={}, retryCount={}",
                    row.getId(), row.getEventId(),
                    row.getRetryCount() == null ? 1
                            : row.getRetryCount() + 1, e);
            return;
        }
        if (diagEnabled) {
            totals.sendNanos += System.nanoTime() - sendStart;
        }
        // 标记段：逐行标记模式各自累计本段墙钟一次且与发送段互不重复归集；
        // 分块标记模式仅收集成功 id，满 chunk 才 flush。
        if (pendingIds == null) {
            long markStart = diagEnabled ? System.nanoTime() : 0L;
            try {
                outboxMapper.markSent(row.getId());
                if (diagEnabled) {
                    totals.markNanos += System.nanoTime() - markStart;
                }
                totals.success++;
                log.info("outbox 事件投递成功：id={}, eventId={}, tag={}",
                        row.getId(), row.getEventId(), row.getTag());
            } catch (Exception e) {
                if (diagEnabled) {
                    totals.markNanos += System.nanoTime() - markStart;
                }
                long incrStart = diagEnabled ? System.nanoTime() : 0L;
                outboxMapper.incrRetry(row.getId());
                if (diagEnabled) {
                    totals.incrRetryNanos += System.nanoTime() - incrStart;
                }
                totals.failed++;
                log.warn("outbox 事件投递失败，下轮重试：id={}, eventId={}, retryCount={}",
                        row.getId(), row.getEventId(),
                        row.getRetryCount() == null ? 1
                                : row.getRetryCount() + 1, e);
            }
            return;
        }
        pendingIds.add(row.getId());
        if (pendingIds.size() >= chunkSize) {
            flushBatchMark(pendingIds, diagEnabled, totals);
        }
    }

    /**
     * 逐行标记模式的便捷重载：等价于 {@code sendRow(row, 0, null, diagEnabled, totals)}.
     *
     * @param row         本行 outbox 数据（原样透传给生产者与 Mapper）
     * @param diagEnabled 诊断开关（关闭时不做任何 nanoTime 采样）
     * @param totals      累计器（串行为主线程直用，并发为 worker 局部实例）
     */
    private void sendRow(final VerifyEventOutbox row, final boolean diagEnabled,
                         final RelayTotals totals) {
        sendRow(row, 0, null, diagEnabled, totals);
    }

    /**
     * 批内并发投递：把<strong>已取到</strong>的批次按列表下标 {@code i % N} 切成
     * 不重不漏的 N 份（绝不改取批 SQL——{@code id % N} 谓词不可用索引，且与主规格
     * 「按 ID 顺序批量读取」冲突），提交给懒建线程池恰好 N 个任务，全部 join 后才
     * 返回（整批仍在 Redisson 锁内，锁语义不变）.
     *
     * <p>诊断：每个 worker 用自己的局部 {@link RelayTotals} 累计，join 后由本线程
     * 求和——刻意不引入 AtomicLong/锁；因此并发下 sendMs/markMs/incrRetryMs 是
     * 各线程墙钟的聚合和（线程时间），residualMs 可能为负，不得读作未归因的墙钟.</p>
     *
     * @param batch           本轮已取到的批次（selectPendingBatch 的返回值）
     * @param sendConcurrency 生效并发数（&gt; 1，由调用方保证）
     * @param diagEnabled     诊断开关（关闭时不做任何 nanoTime 采样）
     * @param totals          本轮累计器（各 worker 局部量在此求和）
     */
    private void deliverConcurrently(final List<VerifyEventOutbox> batch,
                                     final int sendConcurrency, final boolean diagEnabled,
                                     final RelayTotals totals) {
        ExecutorService pool = sendPool(sendConcurrency);
        List<List<VerifyEventOutbox>> shards = new ArrayList<>(sendConcurrency);
        for (int s = 0; s < sendConcurrency; s++) {
            shards.add(new ArrayList<>());
        }
        for (int i = 0; i < batch.size(); i++) {
            shards.get(i % sendConcurrency).add(batch.get(i));
        }
        List<RelayTotals> shardTotals = new ArrayList<>(shards.size());
        List<Future<?>> futures = new ArrayList<>(shards.size());
        for (int s = 0; s < shards.size(); s++) {
            List<VerifyEventOutbox> shard = shards.get(s);
            RelayTotals workerTotals = new RelayTotals();
            shardTotals.add(workerTotals);
            futures.add(pool.submit(
                    () -> runShard(shard, diagEnabled, workerTotals)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("outbox relay 并发投递等待子列表完成时被中断，停止等待剩余子列表");
                break;
            } catch (ExecutionException e) {
                log.warn("outbox relay 并发投递子列表任务意外终止"
                        + "（worker 循环体已兜住单行异常，此处为兜底）", e.getCause());
            }
        }
        for (RelayTotals workerTotals : shardTotals) {
            totals.merge(workerTotals);
        }
    }

    /**
     * 单个 worker 的子列表循环：逐行调用共用处理体；循环体兜住单行异常，
     * 绝不允许异常逃出 worker（否则一行坏数据会废掉整个子列表）.
     *
     * @param shard       本 worker 负责的子列表（下标取模切分的一份）
     * @param diagEnabled 诊断开关（关闭时不做任何 nanoTime 采样）
     * @param totals      本 worker 的局部累计器
     */
    private void runShard(final List<VerifyEventOutbox> shard, final boolean diagEnabled,
                          final RelayTotals totals) {
        for (VerifyEventOutbox row : shard) {
            try {
                sendRow(row, diagEnabled, totals);
            } catch (Exception e) {
                log.warn("outbox relay 并发投递单行异常，已隔离，继续同子列表其余行："
                                + "id={}, eventId={}",
                        row.getId(), row.getEventId(), e);
            }
        }
    }

    /**
     * 串行分块批量标记投递：成功发送行的 id 累积到局部列表，每满 chunk 或批末一次性 markSentBatch.
     *
     * @param batch       本轮已取到的批次
     * @param chunkSize   生效的 chunk 大小
     * @param diagEnabled 诊断开关
     * @param totals      本轮累计器
     */
    private void deliverBatchMarkSerial(final List<VerifyEventOutbox> batch,
                                        final int chunkSize,
                                        final boolean diagEnabled,
                                        final RelayTotals totals) {
        List<Long> pendingIds = new ArrayList<>(chunkSize);
        for (VerifyEventOutbox row : batch) {
            sendRow(row, chunkSize, pendingIds, diagEnabled, totals);
        }
        if (!pendingIds.isEmpty()) {
            flushBatchMark(pendingIds, diagEnabled, totals);
        }
    }

    /**
     * 刷出已累积的成功 id 列表并执行批量条件标记.
     *
     * <p>affected &lt; ids.size() 仅记录 WARN（可能已被并发标记或已耗尽），不抛出不 incrRetry；
     * 抛出异常则对 chunk 内每个 id 逐一执行一次 incrRetry，记录 ERROR，异常不外逃.</p>
     *
     * @param pendingIds  局部待标记 id 列表（处理完毕后清空）
     * @param diagEnabled 诊断开关
     * @param totals      累计器
     */
    private void flushBatchMark(final List<Long> pendingIds,
                                final boolean diagEnabled,
                                final RelayTotals totals) {
        if (pendingIds.isEmpty()) {
            return;
        }
        List<Long> idsToMark = new ArrayList<>(pendingIds);
        pendingIds.clear();
        int count = idsToMark.size();
        long markStart = diagEnabled ? System.nanoTime() : 0L;
        try {
            int affected = outboxMapper.markSentBatch(idsToMark);
            if (diagEnabled) {
                totals.markNanos += System.nanoTime() - markStart;
                totals.markBatchCalls++;
                totals.markBatchRows += count;
            }
            if (affected < count) {
                log.warn("outbox relay 批量标记 SENT 部分命中："
                        + "affected={}, expected={}, diff={}",
                        affected, count, count - affected);
            }
            totals.success += count;
            log.info("outbox 事件分块批量标记成功：rows={}, affected={}",
                    count, affected);
        } catch (Exception e) {
            if (diagEnabled) {
                totals.markNanos += System.nanoTime() - markStart;
                totals.markBatchCalls++;
                totals.markBatchRows += count;
            }
            log.error("outbox relay 批量标记 SENT 异常，对该 chunk 每个 id 逐一 incrRetry："
                    + "chunkSize={}", count, e);
            for (Long id : idsToMark) {
                long incrStart = diagEnabled ? System.nanoTime() : 0L;
                try {
                    outboxMapper.incrRetry(id);
                } catch (Exception ex) {
                    log.warn("outbox relay 批量标记异常后补偿 incrRetry 失败：id={}",
                            id, ex);
                }
                if (diagEnabled) {
                    totals.incrRetryNanos += System.nanoTime() - incrStart;
                }
                totals.failed++;
            }
        }
    }

    /**
     * 批内并发分块批量投递：每个 worker 维护独立的局部 pendingIds 列表，在 worker 内部按 chunk flush.
     *
     * @param batch           本轮已取到的批次
     * @param sendConcurrency 生效并发数
     * @param chunkSize       生效 chunk 大小
     * @param diagEnabled     诊断开关
     * @param totals          本轮累计器
     */
    private void deliverBatchMarkConcurrently(
            final List<VerifyEventOutbox> batch,
            final int sendConcurrency,
            final int chunkSize,
            final boolean diagEnabled,
            final RelayTotals totals) {
        ExecutorService pool = sendPool(sendConcurrency);
        List<List<VerifyEventOutbox>> shards = new ArrayList<>(sendConcurrency);
        for (int s = 0; s < sendConcurrency; s++) {
            shards.add(new ArrayList<>());
        }
        for (int i = 0; i < batch.size(); i++) {
            shards.get(i % sendConcurrency).add(batch.get(i));
        }
        List<RelayTotals> shardTotals = new ArrayList<>(shards.size());
        List<Future<?>> futures = new ArrayList<>(shards.size());
        for (int s = 0; s < shards.size(); s++) {
            List<VerifyEventOutbox> shard = shards.get(s);
            RelayTotals workerTotals = new RelayTotals();
            shardTotals.add(workerTotals);
            futures.add(pool.submit(() -> runBatchMarkShard(
                    shard, chunkSize, diagEnabled, workerTotals)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("outbox relay 并发批量标记投递等待子列表完成时被中断，停止等待剩余子列表");
                break;
            } catch (ExecutionException e) {
                log.warn("outbox relay 并发批量标记投递子列表任务意外终止"
                        + "（worker 循环体已兜底，此处为顶层防御）", e.getCause());
            }
        }
        for (RelayTotals workerTotals : shardTotals) {
            totals.merge(workerTotals);
        }
    }

    /**
     * 单个 worker 的分块子列表循环：单行异常隔离，尾块保证 flush.
     *
     * @param shard       本 worker 负责的子列表
     * @param chunkSize   生效 chunk 大小
     * @param diagEnabled 诊断开关
     * @param totals      本 worker 局部累计器
     */
    private void runBatchMarkShard(final List<VerifyEventOutbox> shard,
                                   final int chunkSize,
                                   final boolean diagEnabled,
                                   final RelayTotals totals) {
        List<Long> pendingIds = new ArrayList<>(chunkSize);
        for (VerifyEventOutbox row : shard) {
            try {
                sendRow(row, chunkSize, pendingIds, diagEnabled, totals);
            } catch (Exception e) {
                log.warn("outbox relay 并发批量标记投递单行异常，已隔离，继续同子列表其余行："
                                + "id={}, eventId={}",
                        row.getId(), row.getEventId(), e);
            }
        }
        if (!pendingIds.isEmpty()) {
            try {
                flushBatchMark(pendingIds, diagEnabled, totals);
            } catch (Exception e) {
                log.warn("outbox relay 并发批量标记尾块 flush 异常：chunkSize={}",
                        pendingIds.size(), e);
            }
        }
    }

    /**
     * 懒建发送线程池：仅并发 &gt; 1 且本批非空时的投递会调用到本方法；volatile 持有、
     * 整个生命周期只建一次（不得每轮新建）；daemon 线程 + 可识别名字前缀，避免泄漏
     * 线程阻塞 JVM 退出、便于线程转储定位.
     *
     * @param sendConcurrency 池大小（生效并发数）
     * @return 懒建的发送线程池（此后复用同一实例）
     */
    private ExecutorService sendPool(final int sendConcurrency) {
        ExecutorService pool = sendExecutor;
        if (pool != null) {
            return pool;
        }
        synchronized (this) {
            if (sendExecutor == null) {
                AtomicInteger seq = new AtomicInteger();
                ThreadFactory factory = task -> {
                    Thread thread = new Thread(task,
                            "verify-outbox-relay-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                };
                sendExecutor = Executors.newFixedThreadPool(
                        sendConcurrency, factory);
                log.info("outbox relay 已创建批内并发发送线程池：sendConcurrency={}",
                        sendConcurrency);
            }
            pool = sendExecutor;
        }
        return pool;
    }

    /**
     * 停止懒建的发送线程池（串行路径从未创建，直接返回）：
     * shutdown → 有界等待 → 超时 shutdownNow；关闭异常只 WARN 不抛.
     */
    @PreDestroy
    public void shutdownSendExecutor() {
        ExecutorService pool = sendExecutor;
        if (pool == null) {
            return;
        }
        try {
            pool.shutdown();
            if (!pool.awaitTermination(
                    SEND_POOL_TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("outbox relay 发送线程池 {}s 内未结束，执行 shutdownNow",
                        SEND_POOL_TERMINATION_WAIT_SECONDS);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("outbox relay 发送线程池关闭等待被中断，执行 shutdownNow");
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("outbox relay 发送线程池关闭异常（只告警不抛出）", e);
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

    /**
     * 单轮的行级计数与分段耗时累计（纳秒）.
     *
     * <p>串行路径由主线程直接累计；并发路径每个 worker 持有自己的实例、join 后由
     * 主线程求和——刻意不引入 AtomicLong/锁（局部量求和既正确又零争用）.
     * 因此并发模式下 {@code sendNanos/markNanos/incrRetryNanos} 是各线程墙钟的
     * 聚合和（线程时间），不是单条时间轴上的墙钟.</p>
     */
    private static final class RelayTotals {

        /** 各 worker 发送段墙钟的局部累计（纳秒）. */
        private long sendNanos;
        /** 各 worker 标记段墙钟的局部累计（纳秒）. */
        private long markNanos;
        /** 各 worker 失败计数段墙钟的局部累计（纳秒）. */
        private long incrRetryNanos;
        /** 本轮成功行数（发送 + 标记都成功）. */
        private int success;
        /** 本轮失败行数（发送或标记抛错且已 incrRetry）. */
        private int failed;
        /** 本轮耗尽行数（重试达上限的防御分支）. */
        private int exhausted;
        /** 批量标记 SQL 调用次数（仅开启批量标记且开启诊断时累计）. */
        private int markBatchCalls;
        /** 批量标记涉及的 outbox 行数累计（仅开启批量标记且开启诊断时累计）. */
        private int markBatchRows;

        /**
         * 把一个 worker 的局部累计并进本轮合计（全部 join 后由主线程单线程调用）.
         *
         * @param other 待合并的 worker 累计器
         */
        private void merge(final RelayTotals other) {
            this.sendNanos += other.sendNanos;
            this.markNanos += other.markNanos;
            this.incrRetryNanos += other.incrRetryNanos;
            this.success += other.success;
            this.failed += other.failed;
            this.exhausted += other.exhausted;
            this.markBatchCalls += other.markBatchCalls;
            this.markBatchRows += other.markBatchRows;
        }
    }
}
