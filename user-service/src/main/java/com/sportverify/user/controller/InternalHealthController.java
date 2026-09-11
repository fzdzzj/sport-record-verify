package com.sportverify.user.controller;

import com.sportverify.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部健康探活端点。
 *
 * <p>供网关探活与其他服务经 user-api Feign 调用（契约见 api 模块）。</p>
 */
@RestController
@RequestMapping("/internal")
public class InternalHealthController {

    /**
     * 健康探活：返回服务名，证明实例存活且注册链路由通。
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("user-service is alive");
    }
}
