package com.sportverify.verify.algorithm.rule;

import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R2 加速度突变（SOFT，飞点拼接/轨迹拼接特征，规范「飞点拼接触发 R2」）。
 *
 * <p>相邻有效点加速度 {@code Δv/Δt > 3 m/s²} 出现 ≥3 次 → 命中。</p>
 */
@Component
@Order(2)
public class R2AccelRule implements Rule {

    @Override
    public String code() {
        return "R2_ACCEL";
    }

    @Override
    public RuleLevel level() {
        return RuleLevel.SOFT;
    }

    @Override
    public RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules) {
        double threshold = rules.getR2().getAccel();   // 默认 3.0 m/s²
        int minCount = rules.getR2().getMinCount();    // 默认 3 次
        if (points.size() < 2) {
            return null;
        }
        int count = 0;
        double maxA = 0;
        for (int i = 1; i < points.size(); i++) {
            Point prev = points.get(i - 1);
            Point cur = points.get(i);
            if (cur.getDtSeconds() <= 0) {
                continue;
            }
            double a = (cur.getSpeed() - prev.getSpeed()) / cur.getDtSeconds();
            maxA = Math.max(maxA, a);
            if (a > threshold) {
                count++;
            }
        }
        if (count < minCount) {
            return null;
        }
        return new RuleHit(code(), level(),
                String.format("accel spikes %d times (max %.1f m/s²)", count, maxA));
    }
}
