package com.sportverify.verify.algorithm.rule;

import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R1 速度合理性（HARD，匀速刷里程特征，规范「匀速刷里程触发 R1」）。
 *
 * <p>滑动窗口（默认 10 点）平均速度 &gt; 5.5 m/s，且该高均速状态持续 ≥10 个窗口
 * （≈ 连续 19 点以上）→ 命中；证据含窗口均值与起止点序号。</p>
 */
@Component
@Order(1) // 规则链按 R1→R4 顺序执行
public class R1SpeedRule implements Rule {

    @Override
    public String code() {
        return "R1_SPEED";
    }

    @Override
    public RuleLevel level() {
        return RuleLevel.HARD;
    }

    @Override
    public RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules) {
        int window = rules.getR1().getWindowPoints();     // 默认 10 点
        int minWindows = rules.getR1().getMinWindows();   // 持续窗口数下限（默认 10）
        double threshold = rules.getR1().getSpeed();      // 默认 5.5 m/s
        if (points.size() < window) {
            return null;
        }

        // 1) 逐窗口计算平均速度：窗口覆盖 points[i-window+1..i]，取其内部 window-1 个瞬时速度均值
        double[] windowAvg = new double[points.size()];
        for (int i = window - 1; i < points.size(); i++) {
            double sum = 0;
            for (int j = i - window + 2; j <= i; j++) {
                sum += points.get(j).getSpeed();
            }
            windowAvg[i] = sum / (window - 1);
        }

        // 2) 找最长连续高均速窗口段（run），并记录段内最大窗口均值
        int bestStart = -1;
        int bestLen = 0;
        double bestAvg = 0;
        int curStart = -1;
        int curLen = 0;
        double curMax = 0;
        for (int i = window - 1; i < points.size(); i++) {
            if (windowAvg[i] > threshold) {
                if (curLen == 0) {
                    curStart = i;
                    curMax = windowAvg[i];
                }
                curLen++;
                curMax = Math.max(curMax, windowAvg[i]);
                if (curLen > bestLen) {
                    bestLen = curLen;
                    bestStart = curStart;
                    bestAvg = curMax;
                }
            } else {
                curLen = 0;
                curMax = 0;
            }
        }

        // 3) 持续窗口数达到下限 → 命中（证据：窗口均值 + 覆盖点数 + 起止 seq）
        if (bestLen < minWindows) {
            return null;
        }
        int startSeq = points.get(bestStart - window + 1).getSeq();
        int endSeq = points.get(bestStart + bestLen - 1).getSeq();
        int covered = bestLen + window - 1; // 覆盖点数 = 窗口段数 + 窗口宽 - 1
        return new RuleHit(code(), level(),
                String.format("window avg %.1f m/s over %d points (seq %d-%d)",
                        bestAvg, covered, startSeq, endSeq));
    }
}
