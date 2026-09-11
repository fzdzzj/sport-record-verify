package com.sportverify.api.verify;

import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * verify-service Feign 契约（verify-api）。
 *
 * <p>骨架阶段仅提供健康探活接口；判定接口（POST /verify/records/{recordId}）与
 * 阈值查询随校验引擎变更在此接口上增量扩展。</p>
 */
@FeignClient(name = "verify-service", path = "/internal")
public interface VerifyApi {

    /**
     * 健康探活：record-service 等消费方经本接口确认 verify-service 存活。
     */
    @GetMapping("/health")
    Result<String> health();
}
