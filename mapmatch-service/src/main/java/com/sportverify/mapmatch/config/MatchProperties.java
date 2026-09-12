package com.sportverify.mapmatch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 匹配算法参数（{@code mapmatch.match.*}，Nacos 可配）。
 *
 * <p>R5 在校验引擎内同步调用本服务，三个参数共同收敛单次匹配的延迟上界：
 * 采样上限封顶空间查询次数（200 点），查询半径封顶单点候选边扫描范围，
 * 索引预筛 + Java 精算两段式下单点查询为毫秒级（GIST 索引，见 ADR-0006）。</p>
 */
@Data
@ConfigurationProperties(prefix = "mapmatch.match")
public class MatchProperties {

    /** 采样点上限：超长轨迹均匀抽稀到该值再匹配（防单请求超时，提案「固定点数上限」） */
    private int maxSampledPoints = 200;

    /** 吸附阈值（米）：点到最近道路边垂距 ≤ 该值视为「在路网上」。
     *  25m = 民用 GPS 误差（10-20m）+ OSM 路网线画偏移余量，真实轨迹误判率低 */
    private double snapThresholdMeters = 25;

    /** 候选边查询半径（米）：空间索引预筛半径，同时是离路距离的上报封顶值——
     *  无候选边时按该半径计离路距离（悬浮点不报无穷大，指标保持有界可解释） */
    private double searchRadiusMeters = 300;
}
