package com.sportverify.record.enums;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 勋章类型枚举（TASK-269 运动徽章与成就体系，9 大核心成就定义）。
 *
 * <p>每枚勋章由五元组元数据描述：中文名、达成描述、分类（MILESTONE 里程碑 / STREAK 连续打卡 /
 * SPECIAL 特色成就）、阶梯（BRONZE 青铜 / SILVER 白银 / GOLD 黄金 / PLATINUM 铂金）、
 * 图标（emoji 展示字符）与目标数值（{@code targetValue}，按勋章语义分别为公里数或天数）。</p>
 *
 * <p>达成判定统一口径：勋章当前值 &ge; 目标值即解锁（读时结算在 {@code BadgeService} 完成，
 * 单次记录达成与历史追溯共用同一比较式）。{@code code} 即枚举名，与
 * {@code user_badge.badge_code} 列值一致（VARCHAR(64)）。</p>
 */
@Getter
public enum BadgeType {

    /** 初出茅庐：完成首次有效运动记录 */
    FIRST_STEP("初出茅庐", "完成首次有效运动记录", "MILESTONE", "BRONZE", "🥉", "1.0"),
    /** 十公里突破：单次跑步有效里程达到 10 公里 */
    TEN_KM_RUN("十公里突破", "单次跑步有效里程达到 10 公里", "MILESTONE", "SILVER", "🥈", "10.0"),
    /** 半程马拉松：单次跑步有效里程达到 21.0975 公里 */
    HALF_MARATHON("半程马拉松", "单次跑步有效里程达到 21.0975 公里", "MILESTONE", "GOLD", "🥇", "21.0975"),
    /** 马拉松大师：单次跑步有效里程达到 42.195 公里 */
    FULL_MARATHON("马拉松大师", "单次跑步有效里程达到 42.195 公里", "MILESTONE", "PLATINUM", "💎", "42.195"),
    /** 七日连胜：连续打卡天数达到 7 天 */
    STREAK_7_DAYS("七日连胜", "连续打卡天数达到 7 天", "STREAK", "SILVER", "🔥", "7.0"),
    /** 月度恒星：连续打卡天数达到 30 天 */
    STREAK_30_DAYS("月度恒星", "连续打卡天数达到 30 天", "STREAK", "GOLD", "⭐", "30.0"),
    /** 百公里俱乐部：历史累计运动总里程突破 100 公里 */
    HUNDRED_KM_CLUB("百公里俱乐部", "历史累计运动总里程突破 100 公里", "MILESTONE", "GOLD", "🏆", "100.0"),
    /** 破风达人：单次骑行有效里程达到 30 公里 */
    CYCLING_MASTER("破风达人", "单次骑行有效里程达到 30 公里", "MILESTONE", "SILVER", "🚴", "30.0"),
    /** 夜行使者：在夜间（21:00-05:00）完成一次 5km 以上有效运动 */
    NIGHT_RUNNER("夜行使者", "在夜间（21:00-05:00）完成一次 5km 以上有效运动", "SPECIAL", "BRONZE", "🌙", "5.0");

    /** 勋章中文名 */
    private final String displayName;

    /** 达成描述 */
    private final String description;

    /** 分类：MILESTONE 里程碑 / STREAK 连续打卡 / SPECIAL 特色成就 */
    private final String category;

    /** 阶梯：BRONZE 青铜 / SILVER 白银 / GOLD 黄金 / PLATINUM 铂金 */
    private final String tier;

    /** 图标（emoji 展示字符） */
    private final String icon;

    /** 目标数值（公里数或天数，按勋章语义） */
    private final BigDecimal targetValue;

    BadgeType(String displayName, String description, String category, String tier, String icon, String targetValue) {
        this.displayName = displayName;
        this.description = description;
        this.category = category;
        this.tier = tier;
        this.icon = icon;
        this.targetValue = new BigDecimal(targetValue);
    }

    /**
     * 按勋章编码解析枚举（即枚举名的精确匹配）。
     *
     * @param code 勋章编码（{@code user_badge.badge_code} 列值）
     * @return 对应枚举；编码为空或不在域内返回 null（由调用方决定拒绝语义）
     */
    public static BadgeType fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        for (BadgeType type : values()) {
            if (type.name().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
