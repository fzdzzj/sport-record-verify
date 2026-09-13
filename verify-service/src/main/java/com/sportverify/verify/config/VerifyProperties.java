package com.sportverify.verify.config;

import com.sportverify.api.record.SportType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 校验规则阈值配置（规范「规则阈值可配置」+「阈值按类型分维度」，见 ADR-0004 §5）。
 *
 * <p>默认值与审批版 §5.2 一致（V_DRIFT=20、R1=5.5、R2=3、R3 段占比 40%、R4=3.0）；
 * 自类型分维度后，R1-R4 阈值按运动类型组织在 {@code verify.rules.by-sport-type.<TYPE>.*}，
 * RUNNING 沿用 5.5，CYCLING 速度上限放宽至 15（骑行正常 6~8 m/s 不再误判，见规范场景「骑行不误杀」）。
 * 读取优先级：Nacos 配置 &gt; application.yml（spring.config.import 导入的远程配置源排在
 * 本地文件之前）。阈值解析经 {@link Rules#threshold(SportType)}：缺省/未知类型回退 RUNNING，不越界。</p>
 */
@Data
@ConfigurationProperties(prefix = "verify")
public class VerifyProperties {

    /** 规则阈值（verify.rules.*）：R1-R4 按运动类型分维度；R5 通用不随类型变化 */
    private Rules rules = new Rules();

    /** 判定策略（verify.policy.*） */
    private Policy policy = new Policy();

    @Data
    public static class Rules {
        /** 漂移速度阈值 m/s（GPS 跳变特征，各类型共用） */
        private double vDrift = 20.0;
        /**
         * 各运动类型 R1-R4 阈值表（key=SportType.name）。结构从「单一套阈值」升级为
         * 「按类型分维度」：同一校验逻辑、不同类型各自独立标定，互不影响。
         * 缺 key（如旧配置未配该类型）时回退 RUNNING（历史默认）。
         */
        private Map<String, RuleThreshold> bySportType = new LinkedHashMap<>();
        /**
         * 旧结构单套阈值（兼容层，勿直接改）：早期 rules_json / 配置为扁平 {@code r1.speed} 等
         * 单套结构、无类型维度；保留字段使旧快照反序列化仍可读，阈缺失类型维度时回退此单套值
         * （{@code legacyThresholdOrElse(RUNNING)}），保证灰度数据可回滚、不越界。
         */
        private R1 r1 = new R1();
        /** 见 {@link #r1}（旧结构单套阈值兼容） */
        private R2 r2 = new R2();
        /** 见 {@link #r1}（旧结构单套阈值兼容） */
        private R3 r3 = new R3();
        /** 见 {@link #r1}（旧结构单套阈值兼容） */
        private R4 r4 = new R4();
        /** R5 离路规则（空间真实性，远程调 mapmatch-service，见 ADR-0006；与运动类型弱相关，暂维持通用） */
        private R5 r5 = new R5();

        /** 单类型 R1-R4 阈值集 */
        @Data
        public static class RuleThreshold {
            /** R1 速度规则 */
            private R1 r1 = new R1();
            /** R2 加速度规则 */
            private R2 r2 = new R2();
            /** R3 停留规则 */
            private R3 r3 = new R3();
            /** R4 距离一致性规则 */
            private R4 r4 = new R4();
        }

        /**
         * 按运动类型取阈值集（判定入口每次调用）：
         * <ol>
         *   <li>耗时命中 {@code by-sport-type} 内该类型配置 → 用类型专属阈值；</li>
         *   <li>否则回退单套阈值（旧结构/未配类型的缺省，默认为 RUNNING=5.5）。</li>
         * </ol>
         * 保证缺 key 的类型（如旧配置只配了 RUNNING）仍以默认阈值判定，不越界不放宽。
         */
        public RuleThreshold threshold(SportType type) {
            String key = type == null ? SportType.RUNNING.name() : type.name();
            RuleThreshold t = bySportType.get(key);
            if (t != null) {
                return t;
            }
            return legacyThreshold();
        }

        /** 组装旧结构单套阈值（兼容层）：复用扁平 r1-r4 字段，缺省即 RUNNING 默认值 */
        private RuleThreshold legacyThreshold() {
            RuleThreshold t = new RuleThreshold();
            t.setR1(r1);
            t.setR2(r2);
            t.setR3(r3);
            t.setR4(r4);
            return t;
        }
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