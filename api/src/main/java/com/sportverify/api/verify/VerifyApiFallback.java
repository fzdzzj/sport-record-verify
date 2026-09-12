package com.sportverify.api.verify;

import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * verify-service Feign 熔断降级工厂（压测变更 spec「熔断降级转人工」）。
 *
 * <p>消费方 record-service 开启 {@code spring.cloud.openfeign.circuitbreaker.enabled}
 * 后，verify-service 不可用（连接拒绝 / 超时 / 熔断器 OPEN）时由本工厂接管：
 * 统一抛 {@link BizException}(4001)，调用方据此感知降级——</p>
 * <ul>
 *   <li>提交链路：MQ 发布失败的降级直调 {@code triggerVerify} 抛出 4001 →
 *       record 侧将记录迁移 MANUAL_REVIEW（转人工），主链路不挂；</li>
 *   <li>查询/申诉链路：异常穿透至 GlobalExceptionHandler，统一返回 4001
 *       （与既有「校验服务不可用」语义一致，不静默吞错）。</li>
 * </ul>
 *
 * <p>放置于 api 模块：{@code @FeignClient(fallbackFactory)} 要求与契约接口同模块编译可见；
 * 降级行为 = 4001 语义，与契约共同演进。</p>
 */
@Slf4j
@Component
public class VerifyApiFallback implements FallbackFactory<VerifyApi> {

    @Override
    public VerifyApi create(Throwable cause) {
        return new VerifyApi() {

            @Override
            public Result<String> health() {
                throw degrade("health", cause);
            }

            @Override
            public Result<com.sportverify.api.verify.dto.VerificationResultDTO> getVerificationResult(Long recordId) {
                throw degrade("getVerificationResult:" + recordId, cause);
            }

            @Override
            public Result<com.sportverify.api.verify.dto.AppealDTO> createAppeal(
                    com.sportverify.api.verify.dto.AppealCreateDTO dto) {
                throw degrade("createAppeal:" + dto.getRecordId(), cause);
            }

            @Override
            public Result<Void> triggerVerify(Long recordId) {
                throw degrade("triggerVerify:" + recordId, cause);
            }
        };
    }

    /** 统一降级语义：4001 校验服务不可用（不泄漏内部异常细节，堆栈只进日志） */
    private static BizException degrade(String op, Throwable cause) {
        log.warn("verify-service 熔断降级：op={}, cause={}", op, cause == null ? "unknown" : cause.toString());
        return new BizException(ResultCode.VERIFY_SERVICE_UNAVAILABLE, "校验服务熔断降级：" + op);
    }
}
