package com.sportverify.leaderboard;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 独立榜单服务启动类（服务数 4→5 的架构重构，见 ADR-0005）。
 *
 * <p>榜单职责从 record-service 平移至此（LeaderboardService/Controller/EventConsumer/
 * Contribution 全套）：记录读写留在 record-service，榜单读热与事件沉淀独立成服务，
 * 读多写少可独立扩缩容，且榜单故障面与记录主链路隔离。</p>
 * <p>{@code scanBasePackages = "com.sportverify"}：加载 common 的统一响应/全局异常处理器
 * 与 api 模块的契约支撑；{@code @EnableFeignClients} 启用 api 契约
 * （好友榜/昵称补齐经 UserApi 调 user-service）；{@code @MapperScan} 扫描榜单域持久层
 * （贡献表 leaderboard_contribution 复用 record_db，与 record-service 共库不共职责）；
 * {@code @EnableScheduling} 启用快照结算定时任务（对账纠偏 ZSet）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@EnableFeignClients(basePackages = "com.sportverify.api")
@MapperScan("com.sportverify.leaderboard.mapper")
@EnableScheduling
public class LeaderboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeaderboardApplication.class, args);
    }
}
