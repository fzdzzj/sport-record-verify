package com.sportverify.record.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交事务分段计时（TASK-139 插桩，性能归因专用，默认关闭）。
 *
 * <p>只为把归因推断的 T_tx 拆成可测量段（幂等 select / insert 主表 / 轨迹写入 /
 * updateStatus（若仍在）/ commit 刷盘，TASK-140 增 connWait 物理连接获取等待），
 * 不是业务能力：开关 {@code record.submit.tx-timing-enabled} 默认 false，关闭时
 * 请求侧零分配零写入（共享 no-op 收集器）。开启时每完成一次提交把各段样本并入
 * 聚合，每 {@value #LOG_EVERY} 次在日志打一行可解析 snapshot（前缀
 * {@code SUBMIT_TX_TIMING} + JSON：总样本数、保留上限与各段样本数/P50/P95/max，
 * 单位微秒）。</p>
 *
 * <p>实现约束（TASK-139 任务书 + TASK-140 修订）：不新增依赖、不新增业务 URL；
 * 样本只在提交成功收口时并入聚合（回滚丢弃），聚合仅统计开关开启期间的提交。
 * 各段驻留样本有界：只保留最近 {@value #SAMPLE_CAP} 个（TASK-140 修复开启态无界
 * 保留——一次 2010 请求负载不触发淘汰，长驻服务的驻留内存有已声明上界，snapshot
 * 的 {@code cap} 字段即该上界；{@code n} 为保留窗口内的样本数）。connWait 段由
 * 内层池包装类（{@code config.TimingHikariDataSource}）在真实物理连接获取点计时，
 * 经 {@link #armConnWait}/{@link #disarmConnWait} 线程级桥接归到当前提交请求——
 * 只有计时开启的提交请求线程被武装，其他端点/后台线程不产生样本。</p>
 */
@Slf4j
public class SubmitTxTiming {

    /** snapshot 打印周期（按收口样本数计） */
    private static final int LOG_EVERY = 100;

    /** 各段驻留样本上限（保留窗口 = 最近 N 个成功收口样本；TASK-140 有界化） */
    static final int SAMPLE_CAP = 4096;

    /** 关闭态共享收集器：span/flush 全部短路，零额外分配 */
    private static final Rec NOOP = new Rec(null);

    /** 各段纳秒样本（段名 → 有界样本表，淘汰最旧）；仅 {@code merge} 在锁内写入 */
    private final Map<String, ArrayDeque<Long>> segments = new LinkedHashMap<>();

    /** 已收口请求数 */
    private long samples;

    /** connWait 桥接（TASK-140）：物理连接获取等待归到当前武装的提交请求 Rec */
    private static final ThreadLocal<Rec> CONN_WAIT_LISTENER = new ThreadLocal<>();

    /** 每请求开始：disabled 返回共享 no-op（零分配零写入） */
    public Rec begin(boolean enabled) {
        return enabled ? new Rec(this) : NOOP;
    }

    /** 武装：此后该线程上的物理连接获取等待归到 rec（计时开启的提交入口调用） */
    public static void armConnWait(Rec rec) {
        CONN_WAIT_LISTENER.set(rec);
    }

    /** 解除武装：事务完结（含回滚）后调用，避免其他端点/后台线程的获取被误归 */
    public static void disarmConnWait() {
        CONN_WAIT_LISTENER.remove();
    }

    /** 当前线程是否已武装（仅诊断可观测；武装与否由提交入口管理） */
    public static boolean connWaitArmed() {
        return CONN_WAIT_LISTENER.get() != null;
    }

    /** 内层池包装类获取点回调：未武装线程直接返回（关闭态/其他端点零样本） */
    public static void recordConnWait(long durationNanos) {
        Rec rec = CONN_WAIT_LISTENER.get();
        if (rec != null) {
            rec.putSpan("connWait", durationNanos);
        }
    }

    /** 可解析 snapshot：总样本数、保留上限与各段样本数/P50/P95/max（微秒） */
    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder(256).append("{\"samples\":").append(samples)
                .append(",\"cap\":").append(SAMPLE_CAP);
        for (Map.Entry<String, ArrayDeque<Long>> e : segments.entrySet()) {
            appendSegment(sb, e.getKey(), e.getValue());
        }
        return sb.append('}').toString();
    }

    /** 提交成功收口：并样本（超限淘汰最旧）+ 周期打 snapshot；仅此一处持锁，失败路径不进 */
    private synchronized void mergeAndClose(Map<String, Long> spans) {
        samples++;
        for (Map.Entry<String, Long> e : spans.entrySet()) {
            ArrayDeque<Long> window = segments.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>());
            window.addLast(e.getValue());
            if (window.size() > SAMPLE_CAP) {
                window.pollFirst();
            }
        }
        if (samples % LOG_EVERY == 0) {
            log.info("SUBMIT_TX_TIMING {}", snapshot());
        }
    }

    private static void appendSegment(StringBuilder sb, String name, ArrayDeque<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        sb.append(",\"").append(name).append("\":{\"n\":").append(sorted.size());
        if (!sorted.isEmpty()) {
            sb.append(",\"p50us\":").append(sorted.get(nearestRank(sorted.size(), 0.50)) / 1000);
            sb.append(",\"p95us\":").append(sorted.get(nearestRank(sorted.size(), 0.95)) / 1000);
            sb.append(",\"maxUs\":").append(sorted.get(sorted.size() - 1) / 1000);
        }
        sb.append('}');
    }

    /** 最近秩分位下标（升序表；q=0.5 → 中位数） */
    private static int nearestRank(int size, double q) {
        return (int) Math.max(0, Math.min((long) size - 1, Math.round(q * size) - 1));
    }

    /** 单请求段收集器：段样本先落本地，成功收口时一次性并入聚合（回滚即丢弃） */
    public static final class Rec {

        private final SubmitTxTiming host;
        private final Map<String, Long> spans = new LinkedHashMap<>();

        private Rec(SubmitTxTiming host) {
            this.host = host;
        }

        /** 记一段耗时：{@code nanoStart} 为该段起点（System.nanoTime 口径） */
        public void span(String segment, long nanoStart) {
            if (host != null) {
                spans.put(segment, System.nanoTime() - nanoStart);
            }
        }

        /** connWait 桥接入口：直接记纳秒时长（同 outer 类访问，维持 host 判空短路） */
        private void putSpan(String segment, long durationNanos) {
            if (host != null) {
                spans.put(segment, durationNanos);
            }
        }

        /** 事务提交成功后的收口点（afterCommit 内调用；回滚不调用） */
        public void flush() {
            if (host != null && !spans.isEmpty()) {
                host.mergeAndClose(spans);
            }
        }
    }
}
