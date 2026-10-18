package com.sportverify.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import com.sportverify.record.mq.LikeEventProducer;
import com.sportverify.record.mq.RecordEventProducer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期循环依赖契约测试（TASK-266）。
 *
 * <p>背景：{@code SportRecordService}（setRecordLikeService，TASK-223）与
 * {@code RecordLikeService}（setSportRecordService，TASK-221）双向 setter 注入成环，
 * Spring Boot 3.2 默认严格模式（{@code allow-circular-references=false}）下生产 jar
 * 启动即失败（UnsatisfiedDependencyException → BeanCurrentlyInCreationException）。
 * 修法：双向注入点均以 {@code @Lazy} 懒加载代理解耦（TASK-266）。</p>
 *
 * <p>防回潮判据：本测试构造严格禁止循环引用的轻量容器——环境属性显式注入
 * {@code spring.main.allow-circular-references=false} 并等价落地到 BeanFactory
 * （AnnotationConfigApplicationContext 不经 SpringApplication 引导，spring.main.*
 * 不会自动绑定，由测试读取环境属性后应用到工厂，与 application.properties 声明同键同值）。
 * 任一方向移除 {@code @Lazy} 本测试即红（TASK-266 受控红探针实测）：
 * 双向移除时 {@code refresh()} 在严格模式下抛 BeanCurrentlyInCreationException；
 * 仅移除一侧时另一侧注入的是真实实例而非懒加载代理，{@code isAopProxy} 断言失败。</p>
 *
 * <p>基础设施依赖一律经 {@code registerBean} 编程式注册（不定义任何
 * {@code @Configuration} 类）：嵌套配置类会落在 ProbeApp 等既有
 * {@code @ComponentScan("com.sportverify.record")} 测试上下文的类路径扫描面内，
 * 其 {@code @Bean} 定义与 {@code @MapperScan} 的 Mapper 注册同名冲突
 * （recordLikeMapper ConflictingBeanDefinitionException，TASK-266 实测教训）。</p>
 */
class RecordServiceCircularDependencyTest {

    /** 严格模式环境属性源名（开关载体）。 */
    private static final String STRICT_MODE_PROPERTY_SOURCE = "task266StrictMode";

    /** 循环引用开关键：与 application.properties 声明同键同值。 */
    private static final String ALLOW_CIRCULAR_PROPERTY = "spring.main.allow-circular-references";

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        // 环境属性显式注入严格模式开关（与 application.properties 同键同值）
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(STRICT_MODE_PROPERTY_SOURCE,
                        Map.of(ALLOW_CIRCULAR_PROPERTY, "false")));
        registerInfrastructureMocks();
        context.register(SportRecordService.class, RecordLikeService.class);
        // 轻量上下文无 SpringApplication 绑定：读回环境属性等价应用到 BeanFactory
        boolean allowCircular = Boolean.parseBoolean(
                context.getEnvironment().getProperty(ALLOW_CIRCULAR_PROPERTY, "true"));
        context.getDefaultListableBeanFactory().setAllowCircularReferences(allowCircular);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /** 容器在严格禁止循环引用模式下可完整刷新：两 Service 成功实例化并互相持有对方懒加载代理。 */
    @Test
    void strictContextRefreshesAndBothServicesWired() {
        // refresh() 成功即证明无启动期循环引用（严格模式下成环必抛 BeanCurrentlyInCreationException）
        context.refresh();

        SportRecordService recordService = context.getBean(SportRecordService.class);
        RecordLikeService likeService = context.getBean(RecordLikeService.class);
        assertNotNull(recordService, "SportRecordService 必须成功实例化");
        assertNotNull(likeService, "RecordLikeService 必须成功实例化");

        // 相互持有对方：注入方拿到的是 @Lazy 懒加载代理（CGLIB 子类，isAopProxy 成立）
        Object likeProxy = ReflectionTestUtils.getField(recordService, "recordLikeService");
        Object recordProxy = ReflectionTestUtils.getField(likeService, "sportRecordService");
        assertNotNull(likeProxy, "SportRecordService 必须持有 RecordLikeService 的懒加载代理");
        assertNotNull(recordProxy, "RecordLikeService 必须持有 SportRecordService 的懒加载代理");
        assertTrue(AopUtils.isAopProxy(likeProxy), "recordLikeService 注入应为 @Lazy 代理");
        assertTrue(AopUtils.isAopProxy(recordProxy), "sportRecordService 注入应为 @Lazy 代理");
    }

    /**
     * 两 Service 运行所必需的基础设施依赖：MyBatis Mapper 接口 / Feign 契约 /
     * Redis（StringRedisTemplate、RedissonClient）/ MQ 生产者 / 校验降级协作器 /
     * 事务管理器 / 指标注册表 / JSON 映射器。仅支撑容器装配，不参与断言。
     */
    private void registerInfrastructureMocks() {
        context.registerBean(SportRecordMapper.class, () -> Mockito.mock(SportRecordMapper.class));
        context.registerBean(TrackPointMapper.class, () -> Mockito.mock(TrackPointMapper.class));
        context.registerBean(TrackPointArchiveMapper.class,
                () -> Mockito.mock(TrackPointArchiveMapper.class));
        context.registerBean(RecordLikeMapper.class, () -> Mockito.mock(RecordLikeMapper.class));
        context.registerBean(RecordEventProducer.class, () -> Mockito.mock(RecordEventProducer.class));
        context.registerBean(LikeEventProducer.class, () -> Mockito.mock(LikeEventProducer.class));
        context.registerBean(VerifyApi.class, () -> Mockito.mock(VerifyApi.class));
        context.registerBean(VerifyDegradeService.class, () -> Mockito.mock(VerifyDegradeService.class));
        context.registerBean(UserApi.class, () -> Mockito.mock(UserApi.class));
        context.registerBean(LeaderboardApi.class, () -> Mockito.mock(LeaderboardApi.class));
        context.registerBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class));
        context.registerBean(RedissonClient.class, () -> Mockito.mock(RedissonClient.class));
        // 显式 lambda 而非方法引用：registerBean 的 varargs 重载下方法引用歧义（无法推断 T）
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(PlatformTransactionManager.class,
                () -> Mockito.mock(PlatformTransactionManager.class));
        context.registerBean(MeterRegistry.class, () -> new SimpleMeterRegistry());
    }
}
