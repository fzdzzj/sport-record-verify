package com.sportverify.verify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 校验规则阈值配置（规范「规则阈值可配置」）。
 *
 * <p>默认值与审批版 §5.2 一致（V_DRIFT=20、R1=5.5、R2=3、R3 段占比 40%、R4=3.0）；
 * Nacos 配置 {@code verify.rules.*} 优先级高于本地 application.yml（spring.config.import
 * 导入的远程配置源排在本地文件之前），改配置即生效——引擎每次判定实时读取，
 * 无需 @RefreshScope 重绑（配合 Caffeine 缓存 TTL 实现 60s 内灰度生效）。</p>
 */
@Data
@ConfigurationProperties(prefix = "verify")
public class VerifyProperties {

    /** 规则阈值（verify.rules.*） */
    private Rules rules = new Rules();

    /** 判定策略（verify.policy.*） */
    private Policy policy = new Policy();

    @Data
    public static class Rules {
        /** 漂移速度阈值 m/s（GPS 跳变特征） */
        private double vDrift = 20.0;
        /** R1 速度规则 */
        private R1 r1 = new R1();
        /** R2 加速度规则 */
        private R2 r2 = new R2();
        /** R3 停留规则 */
        private R3 r3 = new R3();
        /** R4 距离一致性规则 */
        private R4 r4 = new R4();
        /** R5 离路规则（空间真实性，远程调 mapmatch-service，见 ADR-0006） */
        private R5 r5 = new R5();
    }

    @Data
    public static class Policy {
        /** 仅 SOFT 命中时是否拒绝（默认拒绝；宽松开关属后续灰度变更，本变更只提供可配置项） */
        private boolean softOnlyReject = true;
    }

    @Data
    public static class R1 {
        /** 滑动窗口平均速度阈值 m/s */
        private double speed = 5.5;
        /** 滑动窗口点数 */
        private int windowPoints = 10;
        /** 高均速持续窗口数下限（持续 ≥10 个窗口 ≈ 连续 19 点以上，规范「持续 ≥10 点」） */
        private int minWindows = 10;
    }

    @Data
    public static class R2 {
        /** 加速度突变阈值 m/s² */
        private double accel = 3.0;
        /** 触发次数下限 */
        private int minCount = 3;
    }

    @Data
    public static class R3 {
        /** 停留段最小时长（分钟） */
        private int minMinutes = 5;
        /** 停留段位移上限（米） */
        private double maxMeters = 5.0;
        /** 停留段总时长占比阈值（>40% 命中） */
        private double segmentRatio = 0.4;
    }

    @Data
    public static class R4 {
        /** 累计轨迹距离 / 起终点直线距离 比值阈值 */
        private double maxRatio = 3.0;
    }

    @Data
    public static class R5 {
        /**
         * SOFT 阈值：离路比例（offRoadRatio，0-1）超过该值记软证据。
         * 依据：真实轨迹绝大多数点吸附在路网上（mapmatch 吸附阈值 25m 已含 GPS 误差与
         * 路网画线余量），城区沿路轨迹实测 offRoadRatio≈0；50% 为作弊判定下限的同时，
         * 给沿河绿道/公园弱路网区留足余量（R5 误判治理见 ADR-0006）
         */
        private double offRoadRatio = 0.5;
        /**
         * HARD 阈值：离路比例超过该值升级硬证据（整段悬浮海面/楼顶/无路网区，
         * 作弊特征明确直接拒绝）；HARD-SOFT 之间为灰度观察带
         */
        private double hardOffRoadRatio = 0.8;
        /** 最小匹配点数：有效点低于该值跳过 R5（短轨迹匹配无统计意义，防误判） */
        private int minPoints = 5;
    }
}
