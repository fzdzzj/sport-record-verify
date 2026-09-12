package com.sportverify.verify.algorithm.rule;

import com.sportverify.verify.algorithm.GeoUtils;
import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * R3 停留检测（HARD，原地抖动刷时长特征，规范「原地抖动触发 R3」）。
 *
 * <p>存在连续 ≥5min 位移 &lt; 5m 的段（相对段起点位移 &lt; 5m），
 * 且停留段总时长占比 &gt; 40% 总时长 → 命中。</p>
 */
@Component
@Order(3)
public class R3StayRule implements Rule {

    @Override
    public String code() {
        return "R3_STAY";
    }

    @Override
    public RuleLevel level() {
        return RuleLevel.HARD;
    }

    @Override
    public RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules) {
        if (points.size() < 2) {
            return null;
        }
        long totalMs = points.get(points.size() - 1).getTs() - points.get(0).getTs();
        if (totalMs <= 0) {
            return null;
        }
        long minStayMs = rules.getR3().getMinMinutes() * 60_000L; // 默认 5min
        double maxMeters = rules.getR3().getMaxMeters();          // 默认 5m

        // 扫描「位移 <5m」的连续段：以段起点为基准，落在 5m 内的点归入当前段
        List<long[]> staySegments = new ArrayList<>(); // {时长ms, 起始seq, 结束seq}
        int segStart = 0;
        for (int i = 1; i < points.size(); i++) {
            double disp = GeoUtils.distance(points.get(segStart).getLat(), points.get(segStart).getLng(),
                    points.get(i).getLat(), points.get(i).getLng());
            if (disp >= maxMeters) {
                collectSegment(points, segStart, i - 1, minStayMs, maxMeters, staySegments);
                segStart = i;
            }
        }
        collectSegment(points, segStart, points.size() - 1, minStayMs, maxMeters, staySegments);

        long staySum = staySegments.stream().mapToLong(s -> s[0]).sum();
        double ratio = (double) staySum / totalMs;
        if (ratio <= rules.getR3().getSegmentRatio()) {
            return null;
        }
        long maxStayMs = staySegments.stream().mapToLong(s -> s[0]).max().orElse(0);
        return new RuleHit(code(), level(),
                String.format("stay segments %d, total %.1f min (%.0f%% of track), max %.1f min",
                        staySegments.size(), staySum / 60_000.0, ratio * 100, maxStayMs / 60_000.0));
    }

    /** 收集满足「时长 ≥5min 且位移 <5m」的停留段 */
    private void collectSegment(List<Point> points, int start, int end,
                                long minStayMs, double maxMeters, List<long[]> segments) {
        if (end <= start) {
            return;
        }
        long dur = points.get(end).getTs() - points.get(start).getTs();
        double disp = GeoUtils.distance(points.get(start).getLat(), points.get(start).getLng(),
                points.get(end).getLat(), points.get(end).getLng());
        if (dur >= minStayMs && disp < maxMeters) {
            segments.add(new long[]{dur, points.get(start).getSeq(), points.get(end).getSeq()});
        }
    }
}
