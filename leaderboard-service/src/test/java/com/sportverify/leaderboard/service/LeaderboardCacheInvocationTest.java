package com.sportverify.leaderboard.service;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.FriendDTO;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.controller.LeaderboardController;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 总榜缓存真实入口判别式：必须通过 Spring 容器中的代理和正常 Controller 入口验证。
 * 直接 new LeaderboardService 的 Mockito 测试会绕过 @Cacheable 代理，不能替代本测试。
 */
class LeaderboardCacheInvocationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CachingTestConfiguration.class);

    @Test
    void controllerOverallQueriesSameSizeOnlyReadZSetOnceThroughSpringProxy() {
        runner.run(context -> {
            LeaderboardService service = context.getBean(LeaderboardService.class);
            LeaderboardController controller = context.getBean(LeaderboardController.class);
            ZSetOperations<String, String> zSet = context.getBean(ZSetOperations.class);
            HttpServletRequest request = mock(HttpServletRequest.class);

            assertThat(AopUtils.isAopProxy(service))
                    .as("判别式必须取得 Spring 缓存代理，而不是原始 service 实例")
                    .isTrue();

            when(zSet.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 9L))
                    .thenReturn(new LinkedHashSet<>(
                            java.util.Set.of(ZSetOperations.TypedTuple.of("100", 42.5d))));

            controller.leaderboard("overall", null, 10, request);
            controller.leaderboard("overall", null, 10, request);

            verify(zSet).reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 9L);
        });
    }

    @Test
    void friendQueriesBypassOverallCacheAndCallFriendServiceEachTime() {
        runner.run(context -> {
            LeaderboardService service = context.getBean(LeaderboardService.class);
            UserApi userApi = context.getBean(UserApi.class);
            ZSetOperations<String, String> zSet = context.getBean(ZSetOperations.class);
            FriendDTO friend = new FriendDTO();
            friend.setUserId(100L);

            when(userApi.listFriends(7L, 1L, 1000L))
                    .thenReturn(Result.success(new PageResult<>(1, 1000, 1, List.of(friend))));
            when(zSet.reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 499L))
                    .thenReturn(new LinkedHashSet<>(
                            java.util.Set.of(ZSetOperations.TypedTuple.of("100", 42.5d))));

            service.top("friend", 7L, 10);
            service.top("friend", 7L, 10);

            verify(userApi, times(2)).listFriends(7L, 1L, 1000L);
            verify(zSet, times(2))
                    .reverseRangeWithScores(LeaderboardService.OVERALL_ZSET_KEY, 0, 499L);
        });
    }

    @Test
    void friendServiceFailureStillReturnsEmptyBoardThroughProxy() {
        runner.run(context -> {
            LeaderboardService service = context.getBean(LeaderboardService.class);
            UserApi userApi = context.getBean(UserApi.class);
            ZSetOperations<String, String> zSet = context.getBean(ZSetOperations.class);
            when(userApi.listFriends(7L, 1L, 1000L)).thenThrow(new IllegalStateException("user-service down"));

            assertThat(service.top("friend", 7L, 10)).isEmpty();

            verify(userApi).listFriends(7L, 1L, 1000L);
            verifyNoInteractions(zSet);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CachingTestConfiguration {

        @Bean(name = "hierarchicalCacheManager")
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("leaderboard:overall");
        }

        @Bean
        ZSetOperations<String, String> zSetOperations() {
            return mock(ZSetOperations.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(ZSetOperations<String, String> zSetOperations) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.opsForZSet()).thenReturn(zSetOperations);
            return redis;
        }

        @Bean
        UserApi userApi() {
            return mock(UserApi.class);
        }

        @Bean
        LeaderboardService leaderboardService(StringRedisTemplate redis, UserApi userApi) {
            return new LeaderboardService(
                    mock(SportRecordMapper.class),
                    mock(LeaderboardContributionMapper.class),
                    mock(LeaderboardDailySummaryMapper.class),
                    redis,
                    mock(org.redisson.api.RedissonClient.class),
                    userApi);
        }

        @Bean
        LeaderboardController leaderboardController(LeaderboardService service) {
            return new LeaderboardController(service);
        }
    }
}
