package com.sportverify.verify;

import com.sportverify.verify.config.VerifyProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 校验服务启动类。
 *
 * <p>校验引擎 + 申诉（校验引擎变更落地）：预处理漂移过滤、R1-R4 规则链、
 * 判定聚合、事件消费（SETNX 幂等 + DLQ）、管理员终判。</p>
 * <p>启用 Feign 客户端（record-api 拉轨迹/回调）；Mapper 扫描 verify 域持久层；
 * 校验阈值配置 {@code verify.rules.*}（Nacos 可覆盖）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@EnableFeignClients(basePackages = "com.sportverify.api")
@EnableConfigurationProperties(VerifyProperties.class)
@MapperScan("com.sportverify.verify.mapper")
public class VerifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerifyApplication.class, args);
    }
}
