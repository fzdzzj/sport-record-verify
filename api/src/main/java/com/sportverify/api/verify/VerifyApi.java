package com.sportverify.api.verify;

import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * verify-service Feign 契约（verify-api，接口与实现分离）。
 *
 * <p>消费方为 record-service：查询判定结果（verify_db）、创建申诉单。
 * 实现位于 verify-service 的 InternalVerifyController（{@code /internal} 前缀）。</p>
 *
 * <p>熔断降级（压测变更 spec「熔断降级转人工」）：verify 不可用时由
 * {@link VerifyApiFallback} 统一抛 4001，调用方（提交链路）据此转人工，主链路不挂。</p>
 */
@FeignClient(name = "verify-service", path = "/internal", fallbackFactory = VerifyApiFallback.class)
public interface VerifyApi {

    /**
     * 健康探活：record-service 等消费方经本接口确认 verify-service 存活。
     */
    @GetMapping("/health")
    Result<String> health();

    /**
     * 查询校验结果（判定数据在 verify_db，record 查询判定时经本接口拉取）。
     * 尚未终判时返回 verdict=VERIFYING 占位。
     */
    @GetMapping("/verify-results/{recordId}")
    Result<VerificationResultDTO> getVerificationResult(@PathVariable("recordId") Long recordId);

    /**
     * 创建申诉单（record_id 唯一；重复创建幂等返回既有申诉单）。
     */
    @PostMapping("/appeals")
    Result<AppealDTO> createAppeal(@RequestBody AppealCreateDTO dto);

    /**
     * 触发校验（MQ 降级兜底 / 人工重放路径，幂等：按 recordId 不重算）。
     * 正常链路由 SUBMITTED 事件消费触发；本接口仅在 MQ 不可用或补数重放时使用
     * （规范「校验服务降级」主题，错误码 4001）。
     */
    @PostMapping("/records/{recordId}/verify")
    Result<Void> triggerVerify(@PathVariable("recordId") Long recordId);
}
