package com.sportverify.record.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交事务分段计时（TASK-139 插桩，性能归因专用，默认关闭）。
 *
 * <p>只为把归因推断的 T_tx 拆成可测量段（幂等 select / insert 主表 / 轨迹写入 /
 * updateStatus（若仍在）/ commit 刷盘），不是业务能力：开关
 * {@code record.submit.tx-timing-enabled} 默认 false，关闭时请求侧零分配零写入
 * （共享 no-op 收集器）。开启时每完成一次提交把各段样本并入聚合，每
 * {@value #LOG_EVERY} 次在日志打一行可解析 snapshot（前缀 {@code SUBMIT_TX_TIMING}
 * + JSON：总样本数与各段样本数/P50/P95/max，单位微秒）。</p>
 *
 * <p>实现约束（TASK-139 任务书）：不新增依赖、不新增业务 URL；样本只在提交成功
 * 收口时并入聚合（回滚丢弃），聚合仅统计开关开启期间的提交。</p>
 */
@Slf4j
public class SubmitTxTiming {

    /** snapshot 打印周期（按收口样本数计） */
    private static final int LOG_EVERY = 100;

    /** 关闭态共享收集器：span/flush 全部短路，零额外分配 */
    private static final Rec NOOP = new Rec(null);

    /** 各段纳秒样本（段名 → 样本表）；仅 {@code merge} 在锁内写入 */
    private final Map<String, List<Long>> segments = new LinkedHashMap<>();

    /** 已收口请求数 */
    private long samples;

    /** 每请求开始：disabled 返回共享 no-op（零分配零写入） */
    public Rec begin(boolean enabled) {
        return enabled ? new Rec(this) : NOOP;
    }

    /** 可解析 snapshot：总样本数与各段样本数/P50/P95/max（微秒） */
    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder(256).append("{\"samples\":").append(samples);
        for (Map.Entry<String, List<Long>> e : segments.entrySet()) {
            appendSegment(sb, e.getKey(), e.getValue());
        }
        return sb.append('}').toString();
    }

    /** 提交成功收口：并样本 + 周期打 snapshot；仅此一处持锁，失败路径不进 */
    private synchronized void mergeAndClose(Map<String, Long> spans) {
        samples++;
        for (Map.Entry<String, Long> e : spans.entrySet()) {
            segments.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
        if (samples % LOG_EVERY == 0) {
            log.info("SUBMIT_TX_TIMING {}", snapshot());
        }
    }

    private static void appendSegment(StringBuilder sb, String name, List<Long> values) {
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

        /** 事务提交成功后的收口点（afterCommit 内调用；回滚不调用） */
        public void flush() {
            if (host != null && !spans.isEmpty()) {
                host.mergeAndClose(spans);
            }
        }
    }
}
