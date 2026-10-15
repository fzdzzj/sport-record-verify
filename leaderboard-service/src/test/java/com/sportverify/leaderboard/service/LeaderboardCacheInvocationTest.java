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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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
    void friendQueriesThroughSpringProxyUseLocalCache_secondCallHitsCache() {
        runner.run(context -> {
            LeaderboardService service = context.getBean(LeaderboardService.class);
            UserApi userApi = context.getBean(UserApi.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            ZSetOperations<String, String> zSet = context.getBean(ZSetOperations.class);
            FriendDTO friend = new FriendDTO();
            friend.setUserId(100L);

            when(userApi.listFriends(7L, 1L, 1000L))
                    .thenReturn(Result.success(new PageResult<>(1, 1000, 1, List.of(friend))));
            // TASK-239：好友榜改为 Redis Pipeline 单次往返批量点查（ZSCORE），不再走 ZSet 整榜扫描
            when(redis.executePipelined(any(RedisCallback.class)))
                    .thenReturn(List.of((Object) 42.5d));

            service.top("friend", 7L, 10);
            service.top("friend", 7L, 10);

            // TASK-246 B23：好友榜引入 15s 本地 Caffeine 缓存防穿透——第二次命中缓存，
            // Feign 好友列表与 Redis Pipeline 仅各触发一次（不再每请求穿透 user-service）
            verify(userApi, times(1)).listFriends(7L, 1L, 1000L);
            verify(redis, times(1)).executePipelined(any(RedisCallback.class));
            // 好友榜仍不经"总体榜"Spring @Cacheable（本地缓存独立）；且不触碰 ZSet 操作
            verifyNoInteractions(zSet);
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

    /**
     * TASK-248 B23 空榜不缓存核心防背锅语义：降级空榜（user-service 不可用）不得进入 15s 本地 Caffeine 缓存，
     * 依赖恢复后第二次调用必须立刻重新穿透拉取真实数据，不被旧空榜背锅。
     */
    @Test
    void emptyBoardFromDegradation_isNotCached_secondCallRefreshesOnRecovery() {
        runner.run(context -> {
            LeaderboardService service = context.getBean(LeaderboardService.class);
            UserApi userApi = context.getBean(UserApi.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            // 首次：user-service 不可用 → topFriends 降级空榜
            when(userApi.listFriends(7L, 1L, 1000L))
                    .thenThrow(new IllegalStateException("user-service down"));
            assertThat(service.top("friend", 7L, 10)).isEmpty();

            // 重打桩坑（沿 LeaderboardServiceTest L380 先例）：对已 thenThrow 的桩必须用 doReturn，
            // when 写法会先触发旧桩抛异常。恢复后返回 1 位好友，Redis Pipeline 点查得分为 [42.5]。
            FriendDTO friend = new FriendDTO();
            friend.setUserId(100L);
            doReturn(Result.success(new PageResult<>(1, 1000, 1, List.of(friend))))
                    .when(userApi).listFriends(7L, 1L, 1000L);
            doReturn(List.of((Object) 42.5d))
                    .when(redis).executePipelined(any(RedisCallback.class));

            // 第二次：空榜未进缓存 → 重新穿透 → 恢复后立刻拉出真实数据（非空）
            assertThat(service.top("friend", 7L, 10)).isNotEmpty();

            // 穿透计数：好友列表 Feign 两次都真实穿透（空榜未进缓存）
            verify(userApi, times(2)).listFriends(7L, 1L, 1000L);
            // 首次降级在 fetchAllFriendIds 第一步即抛异常，未触达 Pipeline；仅恢复路径执行一次点查。
            // 若该分支被误改（去掉 isEmpty 判定、错误回填空缓存），第二次将命中缓存导致此处 verify 失败。
            verify(redis, times(1)).executePipelined(any(RedisCallback.class));
        });
    }

    /**
     * TASK-248 R2 同口径：无好友空榜（空页 total=0）同样不进入本地缓存，连续两次调用均真实穿透、均返回空。
     */
    @Test
    void emptyBoardFromNoFriends_isNotCached_secondCallPenetratesAgain() {
        runner.run(context -> {
            LeaderboardService service = context.getBean(LeaderboardService.class);
            UserApi userApi = context.getBean(UserApi.class);

            // 无好友路径：返回空页（total=0）
            when(userApi.listFriends(8L, 1L, 1000L))
                    .thenReturn(Result.success(new PageResult<>(1, 1000, 0, List.of())));

            assertThat(service.top("friend", 8L, 10)).isEmpty();
            assertThat(service.top("friend", 8L, 10)).isEmpty();

            // 两次均穿透 → 空榜未进缓存
            verify(userApi, times(2)).listFriends(8L, 1L, 1000L);
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
