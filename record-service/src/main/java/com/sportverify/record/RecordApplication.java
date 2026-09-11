package com.sportverify.record;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 运动记录服务启动类。
 *
 * <p>记录提交/查询 + 榜单/点赞（骨架阶段仅健康端点与中间件接入）。</p>
 * <p>启用 Feign 客户端（api 模块契约），后续校验回调走 verify-api。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@EnableFeignClients(basePackages = "com.sportverify.api")
public class RecordApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordApplication.class, args);
    }
}
