package com.sportverify.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关服务启动类。
 *
 * <p>统一入口（默认 8080），按路径前缀把请求路由到三个业务服务：</p>
 * <ul>
 *   <li>{@code /user/**} → user-service</li>
 *   <li>{@code /record/**} → record-service</li>
 *   <li>{@code /verify/**} → verify-service</li>
 * </ul>
 * <p>路由规则见 application.yml，服务实例经 Nacos 发现（lb://）。</p>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
