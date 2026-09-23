package com.sportverify.mapmatch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 匹配算法参数（{@code mapmatch.match.*}，Nacos 可配）。
 *
 * <p>R5 在校验引擎内同步调用本服务，参数共同收敛单次匹配的延迟上界：
 * 采样上限封顶参与匹配的点数（200 点），分块阈值把候选边预筛的 DB 往返数从「点数」
 * 压到「分块数」（200/50 = 最坏 4 次），查询半径封顶单块候选边扫描范围；
 * 索引预筛 + Java 精算两段式下单块查询为毫秒级（GIST 索引，见 ADR-0006）。</p>
 */
@Data
@ConfigurationProperties(prefix = "mapmatch.match")
public class MatchProperties {

    /** 采样点上限：超长轨迹均匀抽稀到该值再匹配（防单请求超时，提案「固定点数上限」） */
    private int maxSampledPoints = 200;

    /** 轨迹级预筛分块阈值（采样点数）：单块一次 bbox 预筛，块内各点复用候选集算垂距。
     *  默认 50 = 采样上限(200) 的 1/4 —— 最坏 4 次预筛往返；块再大则单块外接矩形过宽
     *  （跨城轨迹一块就覆盖整城路网，预筛退化为全表扫描），块过小则往返数随点数回升 */
    private int prefilterChunkPoints = 50;

    /** 吸附阈值（米）：点到最近道路边垂距 ≤ 该值视为「在路网上」。
     *  25m = 民用 GPS 误差（10-20m）+ OSM 路网线画偏移余量，真实轨迹误判率低 */
    private double snapThresholdMeters = 25;

    /** 候选边查询半径（米）：空间索引预筛半径，同时是离路距离的上报封顶值——
     *  无候选边时按该半径计离路距离（悬浮点不报无穷大，指标保持有界可解释） */
    private double searchRadiusMeters = 300;
}
