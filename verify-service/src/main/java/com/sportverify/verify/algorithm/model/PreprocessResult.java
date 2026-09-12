package com.sportverify.verify.algorithm.model;

import lombok.Data;

import java.util.List;

/**
 * 预处理结果（规范「漂移预处理」）。
 *
 * <p>{@code originalPoints} 保留原始数组用于审计（不剔除），
 * {@code validPoints} 为剔除漂移点后的规则链输入。</p>
 */
@Data
public class PreprocessResult {

    /** 原始轨迹点（保留待审计，规范「原始数组保留待审计」） */
    private List<Point> originalPoints;

    /** 剔除漂移点后的有效点（规则链输入） */
    private List<Point> validPoints;

    /** 总点数 */
    private int pointsTotal;

    /** 剔除漂移点数 */
    private int driftRemoved;

    /** 漂移比例 = 剔除数 / 总点数 */
    private double driftRatio;

    /** driftRatio > 30% → 记软证据 PREPROCESS_SUSPICIOUS */
    private boolean suspicious;
}
