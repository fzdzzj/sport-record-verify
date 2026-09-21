package com.sportverify.leaderboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 事务管理器接线断言：本服务不声明事务配置类，完全交回 Boot 自动装配。
 *
 * <p>这类缺陷只有起上下文才暴露：历史上这里自建过一个漏写 {@code setDataSource} 的
 * {@code new DataSourceTransactionManager()}，它又以同名 bean 顶掉
 * {@code DataSourceTransactionManagerAutoConfiguration}（其 bean 方法带
 * {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)}），服务一起就抛
 * {@code BeanCreationException: Property 'dataSource' is required}，而当时全模块测试仍全绿
 * （与 {@link CacheConfigTest} 同源教训）。</p>
 *
 * <p>所以断言不停在"容器里有个叫 transactionManager 的 bean"，而是验证实例：容器里唯一的
 * {@link PlatformTransactionManager} 必须真绑在容器这个 {@link DataSource} 上，
 * 而不是一个"看起来有、一用就抛"的空壳。bean 名区分不出手搓与自动装配，只有
 * {@code getDataSource()} 的引用相等做得到。</p>
 */
class TransactionManagerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withConfiguration(AutoConfigurations.of(
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class));

    @Test
    void transactionManagerIsAutoConfiguredAndBoundToContainerDataSource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PlatformTransactionManager.class);
            assertThat(context.getBean(PlatformTransactionManager.class))
                    .isInstanceOf(DataSourceTransactionManager.class);
            assertThat(((DataSourceTransactionManager) context.getBean(PlatformTransactionManager.class))
                    .getDataSource()).isSameAs(context.getBean(DataSource.class));
        });
    }
}
