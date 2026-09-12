package com.sportverify.api.mapmatch;

import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * mapmatch-service Feign 熔断降级工厂（规范「匹配降级」，沿用 VerifyApiFallback 模式）。
 *
 * <p>消费方 verify-service 开启 {@code spring.cloud.openfeign.circuitbreaker.enabled}
 * 后，mapmatch-service 不可用（连接拒绝 / 超时 / 熔断器 OPEN）时由本工厂接管：
 * 统一抛 {@link BizException}(4005)。R5 离路规则捕获后<b>降级为「不命中」</b>返回 null——
 * 路网匹配是增强证据而非强依赖，宁可放过不可阻断校验主链路；降级事件以 warn 日志暴露。</p>
 *
 * <p>放置于 api 模块：{@code @FeignClient(fallbackFactory)} 要求与契约接口同模块编译可见；
 * 降级行为 = 4005 语义，与契约共同演进。</p>
 */
@Slf4j
@Component
public class MapMatchApiFallback implements FallbackFactory<MapMatchApi> {

    @Override
    public MapMatchApi create(Throwable cause) {
        return new MapMatchApi() {

            @Override
            public Result<MapMatchResultDTO> match(MapMatchRequestDTO request) {
                // 不泄漏内部异常细节：cause 只进日志，对外统一 4005 语义
                log.warn("mapmatch-service 熔断降级：match(points={}), cause={}",
                        request == null || request.getPoints() == null ? 0 : request.getPoints().size(),
                        cause == null ? "unknown" : cause.toString());
                throw new BizException(ResultCode.MAPMATCH_SERVICE_UNAVAILABLE, "匹配服务熔断降级：match");
            }
        };
    }
}
