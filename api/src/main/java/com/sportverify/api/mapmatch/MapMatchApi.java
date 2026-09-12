package com.sportverify.api.mapmatch;

import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * mapmatch-service Feign 契约（mapmatch-api，接口与实现分离）。
 *
 * <p>消费方为 verify-service：R5 离路规则在校验引擎内调用匹配接口，
 * 得到轨迹偏离真实路网的指标（offRoadRatio 等）后与阈值比较产出 SOFT/HARD 证据。
 * 实现位于 mapmatch-service 的 {@code /match}（网关侧暴露为 {@code /mapmatch/match}，
 * StripPrefix=1；服务间调用经 Nacos 负载均衡直连，不经网关）。</p>
 *
 * <p>熔断降级（沿用 record-service 已验证模式）：verify 侧开启
 * {@code spring.cloud.openfeign.circuitbreaker.enabled} 后，mapmatch 不可用时由
 * {@link MapMatchApiFallback} 统一抛 4005，R5 捕获后降级为「不命中」，不阻断校验主链路。</p>
 */
@FeignClient(name = "mapmatch-service", path = "/match", fallbackFactory = MapMatchApiFallback.class)
public interface MapMatchApi {

    /**
     * 道路拓扑匹配：提交轨迹点，返回吸附最近道路边后的偏离指标。
     *
     * <p>服务侧有采样点上限（默认 200）与查询半径封顶，超长轨迹自动抽稀，
     * 保证 R5 同步调用延迟有上界。</p>
     */
    @PostMapping
    Result<MapMatchResultDTO> match(@RequestBody MapMatchRequestDTO request);
}
