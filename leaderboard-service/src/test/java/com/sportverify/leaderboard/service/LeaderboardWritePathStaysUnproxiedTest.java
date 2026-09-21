package com.sportverify.leaderboard.service;

import com.sportverify.api.user.UserApi;
import com.sportverify.leaderboard.controller.LeaderboardController;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ADR-0009 事务边界哨兵：榜单写路径的 bean 不得被 Spring 代理。
 *
 * <p>依据 {@code docs/adr/0009-事务边界.md:37-38}——{@link LeaderboardService#applyVerified}
 * 与 {@code rollbackOnRejected} 是"一行贡献表 + Redis ZSet"的跨存储写，禁止把 ZSet 纳入本地事务，
 * {@code settleAndReconcile} 保持最终一致；{@code :53} 禁止批量给 Service 铺
 * {@code @Transactional}。</p>
 *
 * <p>这里按生产装配方式起上下文（Boot 的 {@code AopAutoConfiguration}/
 * {@code TransactionAutoConfiguration}/{@code DataSourceTransactionManagerAutoConfiguration}，
 * 代理工厂与 {@code proxyTargetClass} 口径同生产），放入真实写路径 bean
 * {@link LeaderboardService}（三条写路径的宿主）和它的调用方 {@link LeaderboardController}，
 * 断言两者都没被代理。一旦有人补上 {@code @Transactional}，本类立刻变红，逼作者直面
 * "加了注解就改变了 MQ 重投与 Redisson 锁/提交顺序"这件事，而不是静默把注解点亮。</p>
 */
class LeaderboardWritePathStaysUnproxiedTest {

    private final LeaderboardService leaderboardService = new LeaderboardService(
            mock(SportRecordMapper.class), mock(LeaderboardContributionMapper.class),
            mock(LeaderboardDailySummaryMapper.class),
            mock(StringRedisTemplate.class), mock(RedissonClient.class), mock(UserApi.class));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(LeaderboardService.class, () -> leaderboardService)
            .withBean(LeaderboardController.class, () -> new LeaderboardController(leaderboardService));

    @Test
    void leaderboardWritePathBeansStayUnproxied() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isAopProxy(context.getBean(LeaderboardService.class)))
                    .as("LeaderboardService 被代理了：说明有 @Transactional 进了事务边界，违反 ADR-0009 榜单最终一致边界")
                    .isFalse();
            assertThat(AopUtils.isAopProxy(context.getBean(LeaderboardController.class)))
                    .as("LeaderboardController 被代理了：说明有 @Transactional 进了事务边界，违反 ADR-0009 榜单最终一致边界")
                    .isFalse();
        });
    }
}
