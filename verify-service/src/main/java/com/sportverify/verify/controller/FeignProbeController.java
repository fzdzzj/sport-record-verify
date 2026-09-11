package com.sportverify.verify.controller;

import com.sportverify.api.record.RecordApi;
import com.sportverify.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feign 探活演示端点。
 *
 * <p>骨架阶段用于验证「服务间 Feign 契约 + 注册发现」链路：</p>
 * <pre>
 *   客户端 → gateway(/verify/**) → verify-service → RecordApi(Feign) → record-service
 * </pre>
 * <p>后续校验引擎变更中，本模式即用于经 record-api 拉取轨迹做真实性判定。</p>
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class FeignProbeController {

    /** record-service 契约（api 模块，接口与实现分离） */
    private final RecordApi recordApi;

    /**
     * 跨服务探活：verify-service 经 record-api 调用 record-service 健康端点。
     */
    @GetMapping("/probe/record")
    public Result<String> probeRecord() {
        return recordApi.health();
    }
}
