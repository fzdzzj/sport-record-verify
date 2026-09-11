package com.sportverify.api.user;

import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * user-service Feign 契约（user-api）。
 *
 * <p>骨架阶段仅提供健康探活接口；好友列表查询（榜单好友过滤用）随
 * 好友功能变更在此接口上增量扩展。</p>
 */
@FeignClient(name = "user-service", path = "/internal")
public interface UserApi {

    /**
     * 健康探活：其他服务经本接口确认 user-service 存活。
     */
    @GetMapping("/health")
    Result<String> health();
}
