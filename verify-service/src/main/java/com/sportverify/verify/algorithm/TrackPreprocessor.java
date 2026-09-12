package com.sportverify.verify.algorithm;

import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.PreprocessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 预处理 · 漂移过滤（审批版 §5.2 Step1 / 规范「漂移预处理」）。
 *
 * <p>逐点计算与上一有效点的瞬时速度 {@code v = haversine / Δt}：</p>
 * <ul>
 *   <li>{@code v > V_DRIFT（默认 20 m/s，GPS 跳变特征）} 或 {@code Δt < 0.1s} → 标记漂移并剔除；</li>
 *   <li>保留原始数组用于审计；</li>
 *   <li>{@code driftRatio = 剔除数/总点数 > 30%} → 记软证据 PREPROCESS_SUSPICIOUS（由引擎汇总）。</li>
 * </ul>
 */
@Slf4j
@Component
public class TrackPreprocessor {

    /** 最小时间间隔（秒）：小于该值视为无效采样（GPS 重复上报），规范默认 Δt<0.1s 即漂移 */
    private static final double MIN_DT_SECONDS = 0.1;

    /** 高漂移比例阈值（driftRatio > 30% 记软证据，规范场景「高漂移比例记软证据」） */
    private static final double SUSPICIOUS_RATIO = 0.3;

    /**
     * 执行漂移过滤。
     *
     * @param dtoPoints 客户端提交的原始轨迹点（DTO）
     * @param vDrift    漂移速度阈值（默认 20 m/s，Nacos verify.rules.v-drift 可配）
     */
    public PreprocessResult preprocess(List<TrackPointDTO> dtoPoints, double vDrift) {
        List<Point> original = new ArrayList<>(dtoPoints.size());
        List<Point> valid = new ArrayList<>(dtoPoints.size());
        Point lastValid = null;
        int driftRemoved = 0;

        for (TrackPointDTO p : dtoPoints) {
            // 无效点（坐标/时间缺失）直接按漂移剔除，避免脏数据进入规则链
            if (p.getLat() == null || p.getLng() == null || p.getTs() == null) {
                original.add(new Point(0, 0, 0, 0, 0, 0)); // 占位（不参与审计统计细节）
                driftRemoved++;
                continue;
            }
            Point point = new Point(
                    p.getSeq() == null ? valid.size() + driftRemoved + 1 : p.getSeq(),
                    p.getLat().doubleValue(), p.getLng().doubleValue(), p.getTs(), 0, 0);
            original.add(point);

            if (lastValid == null) {
                // 首点无前序，必然保留为有效点
                valid.add(point);
                lastValid = point;
                continue;
            }
            // 与上一有效点计算瞬时速度与时间差（规范「逐点计算瞬时速度」）
            double dt = (point.getTs() - lastValid.getTs()) / 1000.0;
            double dist = GeoUtils.distance(lastValid.getLat(), lastValid.getLng(),
                    point.getLat(), point.getLng());
            double speed = dt > 0 ? dist / dt : Double.MAX_VALUE;
            point.setDtSeconds(dt);
            point.setSpeed(speed);

            // 漂移判定：Δt < 0.1s（重复上报）或 v > V_DRIFT（GPS 跳变）
            if (dt < MIN_DT_SECONDS || speed > vDrift) {
                driftRemoved++;
                // 漂移点不更新 lastValid：后续点继续与「上一有效点」比较
                continue;
            }
            valid.add(point);
            lastValid = point;
        }

        PreprocessResult result = new PreprocessResult();
        result.setOriginalPoints(original);
        result.setValidPoints(valid);
        result.setPointsTotal(original.size());
        result.setDriftRemoved(driftRemoved);
        result.setDriftRatio(original.isEmpty() ? 0 : (double) driftRemoved / original.size());
        result.setSuspicious(result.getDriftRatio() > SUSPICIOUS_RATIO);
        log.info("预处理完成：total={}, driftRemoved={}, driftRatio={}, suspicious={}",
                result.getPointsTotal(), result.getDriftRemoved(),
                String.format("%.3f", result.getDriftRatio()), result.isSuspicious());
        return result;
    }
}
