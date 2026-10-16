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
 *
 * <p>边界语义（E25，TASK-256）：循环自 i=2 起，首区间（i=1）的 prev 为首个采样点——首点经
 * {@link com.sportverify.verify.algorithm.TrackPreprocessor} 回填时保持缺省 0 哨兵
 * （speed=0/dt=0），以其参与计算必伪造超阈尖峰；纳入会污染 minCount 证据计数、把
 * minCount=3 的有效门槛降为 2，故首区间不计入，minCount 恢复真实门槛、证据计数净化。</p>
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
    public RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules,
                            VerifyProperties.Rules.RuleThreshold typeThreshold) {
        double threshold = typeThreshold.getR2().getAccel();   // 默认 3.0 m/s²
        int minCount = typeThreshold.getR2().getMinCount();    // 默认 3 次
        if (points.size() < 2) {
            return null;
        }
        int count = 0;
        double maxA = 0;
        // E25：自 i=2 起，跳过首区间（prev=首点哨兵 speed/dt=0）以避免伪尖峰污染
        for (int i = 2; i < points.size(); i++) {
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
