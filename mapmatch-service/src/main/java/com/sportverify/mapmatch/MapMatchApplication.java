package com.sportverify.mapmatch;

import com.sportverify.mapmatch.config.MatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 道路拓扑匹配服务启动类（第 6 个微服务，服务数 5→6，见 ADR-0006）。
 *
 * <p>职责：承载真实 OSM 路网数据（PostGIS 空间库）与最近边投影匹配算法，
 * 暴露 {@code POST /match} 返回轨迹偏离路网指标（matchedRatio / offRoadRatio /
 * avgOffRoadDistance / maxOffRoadDistance）；verify-service 的 R5 离路规则经
 * MapMatchApi（Feign）远程调用本服务，空间匹配职责独立成服务——空间数据与算法
 * 可独立扩缩容/独立演进（换 HMM 算法不动校验引擎），且匹配抖动可被 R5 侧熔断隔离。</p>
 * <p>{@code scanBasePackages = "com.sportverify"}：加载 common 的统一响应/全局异常
 * 处理器与 api 模块的契约支撑（与其他 5 个服务保持同一约定）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@EnableConfigurationProperties(MatchProperties.class)
public class MapMatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapMatchApplication.class, args);
    }
}
