package com.sportverify.verify.algorithm.rule;

import com.sportverify.api.record.SportType;
import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 距离一致性起征门槛单测（TASK-238）。
 *
 * <p>覆盖：① 短距离闭合轨迹（累计 &lt; 500m，起终点重合）→ 起征门槛豁免返回 null；
 * ② 长距离折返（累计 ≈ 2000m，起终点重合）→ 精准命中 R4 SOFT；
 * ③ 正常折线轨迹（累计 1000m / 直线 500m，ratio=2.0 ≤ 3.0）→ 不命中。
 * 规则级直测（不经引擎聚合），轨迹点直接构造 {@link Point}（R4 仅读 lat/lng）。</p>
 */
class R4DistanceRuleTest {

    private static VerifyProperties.Rules rules() {
        return new VerifyProperties().getRules();
    }

    /** R4 类型阈值：缺省 RUNNING 集（minCumulativeMeters 默认 500.0） */
    private static VerifyProperties.Rules.RuleThreshold threshold() {
        return new VerifyProperties().getRules().threshold(SportType.RUNNING);
    }

    /** 折返轨迹：向北 outMeters 米再原路折返（累计 = 2×outMeters，起终点严格重合） */
    private static List<Point> returnTrack(double outMeters) {
        List<Point> points = new ArrayList<>();
        int n = (int) Math.round(outMeters / 5.0) + 1; // 每段 5m，去程 n-1 段
        long base = 1_700_000_000_000L;
        for (int i = 0; i < n; i++) { // 去程：0 → outMeters
            points.add(new Point(i, i * 5.0 / 111320.0, 0, base + i * 1000L, 5.0, 1.0));
        }
        for (int i = 1; i < n; i++) { // 返程：共享折返点，回落至起点（末点=首点，直线距离 0）
            points.add(new Point(n - 1 + i, (n - 1 - i) * 5.0 / 111320.0, 0, base + (n - 1 + i) * 1000L, 5.0, 1.0));
        }
        return points;
    }

    /** 折线轨迹：北行 500m 后转向 120° 再行 500m（累计 1000m，起终点直线 500m，ratio=2.0） */
    private static List<Point> vTrack() {
        List<Point> points = new ArrayList<>();
        long base = 1_700_000_000_000L;
        double lat = 0;
        double lng = 0;
        for (int i = 0; i < 100; i++) { // 北行 500m（5 m/s）
            points.add(new Point(i, lat, lng, base + i * 1000L, 5.0, 1.0));
            lat += 5.0 / 111320.0;
        }
        for (int i = 0; i < 100; i++) { // 转向 120°（北偏东）再行 500m
            points.add(new Point(100 + i, lat, lng, base + (100 + i) * 1000L, 5.0, 1.0));
            lat += 5.0 * Math.cos(Math.toRadians(120)) / 111320.0;
            lng += 5.0 * Math.sin(Math.toRadians(120)) / (111320.0 * Math.cos(Math.toRadians(lat)));
        }
        return points;
    }

    /** 用例 1：累计 300m（< 500m 起征门槛）且起终点重合 → 返回 null（起征保护生效） */
    @Test
    void shortClosedLoop_belowMinCumulative_exempted() {
        R4DistanceRule rule = new R4DistanceRule();
        RuleHit hit = rule.evaluate(returnTrack(150), rules(), threshold());
        assertThat(hit).isNull();
    }

    /** 用例 2：累计 ≈2000m（> 500m）且起终点重合 → 命中 R4_DISTANCE（折返刷里程） */
    @Test
    void longReturnTrack_overMinCumulative_hitsR4() {
        R4DistanceRule rule = new R4DistanceRule();
        RuleHit hit = rule.evaluate(returnTrack(1000), rules(), threshold());
        assertThat(hit).isNotNull();
        assertThat(hit.getRule()).isEqualTo("R4_DISTANCE");
        assertThat(hit.getLevel()).isEqualTo(RuleLevel.SOFT);
        assertThat(hit.getDetail()).contains("cumulative").contains("straight 0 m");
    }

    /** 用例 3：累计 1000m、直线 500m（ratio=2.0 ≤ 3.0）→ 返回 null */
    @Test
    void normalTrack_ratioWithinThreshold_notHit() {
        R4DistanceRule rule = new R4DistanceRule();
        assertThat(rule.evaluate(vTrack(), rules(), threshold())).isNull();
    }
}
