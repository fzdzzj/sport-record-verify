package com.sportverify.verify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 校验服务启动类。
 *
 * <p>校验引擎 + 申诉（骨架阶段仅健康端点、Feign 探活与中间件接入）。</p>
 * <p>启用 Feign 客户端：经 record-api 拉取轨迹做真实性判定（算法随后续变更落地）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@EnableFeignClients(basePackages = "com.sportverify.api")
public class VerifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerifyApplication.class, args);
    }
}
