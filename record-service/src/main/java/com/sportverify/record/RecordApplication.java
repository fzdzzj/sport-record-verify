package com.sportverify.record;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运动记录服务启动类。
 *
 * <p>记录提交/查询 + 状态机（校验引擎变更落地）：轨迹分片存储、request_id 幂等、
 * 乐观锁状态迁移、RocketMQ 事件发布；经 verify-api 查询判定与建申诉单。</p>
 * <p>启用 Feign 客户端（api 模块契约）；Mapper 扫描 record 域持久层；
 * {@code @EnableScheduling} 启用点赞异步批量落库（flush）/对账与校验降级补偿定时任务。
 * 榜单职责已拆分至独立 leaderboard-service（服务数 4→5，见 ADR-0005），
 * 本服务保留记录分片 + 点赞，不再订阅 VERIFIED/REJECTED 沉淀榜单（防双写）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@EnableFeignClients(basePackages = "com.sportverify.api")
@MapperScan("com.sportverify.record.mapper")
@EnableScheduling
public class RecordApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordApplication.class, args);
    }
}
