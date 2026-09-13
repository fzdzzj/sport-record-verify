package com.sportverify.verify.algorithm.rule;

import com.sportverify.api.mapmatch.MapMatchApi;
import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.verify.algorithm.RuleLevel;
import com.sportverify.verify.algorithm.model.Point;
import com.sportverify.verify.algorithm.model.RuleHit;
import com.sportverify.verify.config.VerifyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R5 离路规则（首个依赖外部空间服务的规则，规范「R5 离路规则」，见 ADR-0006）。
 *
 * <p>R1-R4 检测「轨迹自身反常」（匀速/加速度/停留/距离），识别不了「轨迹不在任何
 * 真实道路上」的悬浮伪造——R5 把轨迹点交给 mapmatch-service 吸附到真实 OSM 路网，
 * 以离路比例 offRoadRatio 与阈值比较：超 SOFT 阈值（默认 0.5）记软证据、
 * 超 HARD 阈值（默认 0.8，整段悬浮海面/楼顶）记硬证据。</p>
 *
 * <p>降级口径（规范「匹配降级」）：mapmatch 不可用（熔断 OPEN/超时/连接拒绝经
 * FallbackFactory 抛 4005）时 R5 降级为「不命中」返回 null——校验主链路是 ODR
 * 主路径，R5 是增强证据而非强依赖，宁可放过不可阻断；降级同时记 warn 供监控。</p>
 *
 * <p>级别说明：接口 {@link #level()} 返回固定级别供元数据用途，R5 默认 SOFT
 * （可灰度观察，复用 rule_version 机制）；极端偏离在 evaluate 内按阈值动态升级 HARD。</p>
 */
@Slf4j
@Component
@Order(5) // 规则链按 R1→R5 顺序执行，排在 R4 之后
@RequiredArgsConstructor
public class R5OffRoadRule implements Rule {

    /** 离路比例阈值取自 mapmatch 的 offRoadRatio 口径（0-1），超阈值才命中（边界值不命中防抖动） */
    private final MapMatchApi mapMatchApi;

    @Override
    public String code() {
        return "R5_OFFROAD";
    }

    @Override
    public RuleLevel level() {
        return RuleLevel.SOFT;
    }

    @Override
    public RuleHit evaluate(List<Point> points, VerifyProperties.Rules rules,
                            VerifyProperties.Rules.RuleThreshold typeThreshold) {
        // R5 与运动类型弱相关，维持通用：阈值取全局 rules（含 R5 配置），typeThreshold 忽略
        VerifyProperties.R5 r5 = rules.getR5();
        // 点数不足不匹配：短轨迹无统计意义，且避免提交远端调用的无谓开销
        if (points == null || points.size() < r5.getMinPoints()) {
            return null;
        }

        // 组装匹配请求：把预处理后的有效点转为 mapmatch 契约（seq/lat/lng/ts 原样透传）
        MapMatchRequestDTO request = new MapMatchRequestDTO();
        request.setPoints(points.stream().map(this::toMatchPoint).toList());

        MapMatchResultDTO result;
        try {
            result = mapMatchApi.match(request).getData();
        } catch (Exception e) {
            // 熔断降级：mapmatch 不可用 → R5 不命中，不阻断校验主链路（规范「服务不可用降级」）
            log.warn("R5 降级不命中：mapmatch 不可用，cause={}", e.getMessage());
            return null;
        }
        if (result == null) {
            log.warn("R5 降级不命中：mapmatch 返回空结果");
            return null;
        }

        double ratio = result.getOffRoadRatio();
        String detail = String.format(
                "off-road ratio %.2f over %d/%d points, avg %.0fm, max %.0fm",
                ratio, Math.round(ratio * result.getSampledPoints()), result.getSampledPoints(),
                result.getAvgOffRoadDistance(), result.getMaxOffRoadDistance());

        // 极端偏离（超 HARD 阈值）升级硬证据：整段悬浮作弊特征明确，直接拒绝
        if (ratio > r5.getHardOffRoadRatio()) {
            return new RuleHit(code(), RuleLevel.HARD, detail);
        }
        // 超 SOFT 阈值记软证据：参与 SOFT 计分/soft-only-reject 策略
        if (ratio > r5.getOffRoadRatio()) {
            return new RuleHit(code(), level(), detail);
        }
        return null;
    }

    /** 引擎点模型 → mapmatch 契约点（经纬度直接透传，匹配服务不信任 speed 等客户端字段） */
    private MapMatchPointDTO toMatchPoint(Point p) {
        MapMatchPointDTO dto = new MapMatchPointDTO();
        dto.setSeq(p.getSeq());
        dto.setLat(p.getLat());
        dto.setLng(p.getLng());
        dto.setTs(p.getTs());
        return dto;
    }
}
