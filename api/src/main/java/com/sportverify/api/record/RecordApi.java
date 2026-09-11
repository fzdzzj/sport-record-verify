package com.sportverify.api.record;

import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * record-service Feign 契约（record-api）。
 *
 * <p>骨架阶段仅提供健康探活接口；业务接口（提交记录/详情/列表/点赞/榜单）随
 * 后续变更在此接口上增量扩展，实现位于 record-service。</p>
 */
@FeignClient(name = "record-service", path = "/internal")
public interface RecordApi {

    /**
     * 健康探活：verify-service 等消费方经本接口确认 record-service 存活。
     */
    @GetMapping("/health")
    Result<String> health();
}
