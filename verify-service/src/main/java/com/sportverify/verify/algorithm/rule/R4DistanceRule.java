package com.sportverify.verify.algorithm.rule;

import com.sportverify.verify.algorithm.GeoUtils;
import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R4 距离一致性（SOFT，折返/绕圈刷里程特征，规范「折返刷里程触发 R4」）。
 *
 * <p>累计轨迹距离 / 起终点直线距离 &gt; 3.0 → 命中。
 * 起点≈终点且累计距离显著（典型折返）时直线距离趋零，比值视为无穷大。</p>
 */
@Component
@Order(4)
public class R4DistanceRule implements Rule {

    @Override
    public String code() {
        return "R4_DISTANCE";
    }

    @Override
    public RuleLevel level() {
        return RuleLevel.SOFT;
    }

    @Override
    public RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules) {
        if (points.size() < 2) {
            return null;
        }
        double threshold = rules.getR4().getMaxRatio(); // 默认 3.0

        // 累计轨迹距离（逐段 haversine 累加）
        double cumulative = 0;
        for (int i = 1; i < points.size(); i++) {
            cumulative += GeoUtils.distance(points.get(i - 1).getLat(), points.get(i - 1).getLng(),
                    points.get(i).getLat(), points.get(i).getLng());
        }
        // 起终点直线距离
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        double straight = GeoUtils.distance(first.getLat(), first.getLng(), last.getLat(), last.getLng());

        // 折返特征：起点≈终点（直线趋零）但累计距离显著 → 比值无穷大
        double ratio;
        if (straight < 1e-6) {
            ratio = cumulative > 1e-6 ? Double.MAX_VALUE : 0;
        } else {
            ratio = cumulative / straight;
        }
        if (ratio <= threshold) {
            return null;
        }
        return new RuleHit(code(), level(),
                String.format("cumulative %.0f m vs straight %.0f m (ratio %.2f)",
                        cumulative, straight, ratio > 1e9 ? 999.99 : ratio));
    }
}
