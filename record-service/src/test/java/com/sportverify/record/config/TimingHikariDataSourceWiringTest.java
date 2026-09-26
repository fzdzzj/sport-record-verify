package com.sportverify.record.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolMetaData;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TimingHikariDataSource 与 ShardingSphere 池元数据 SPI / 关闭路径 / 属性转发的接线判别式
 * （TASK-140 及其收口后修订）。
 *
 * <p>ShardingSphere 5.4.1 按 dataSourceClassName 精确匹配池元数据 TypedSPI；若
 * META-INF/services 注册缺失，YAML 的 jdbcUrl 无法归一化（启动 NPE）——此失败只在
 * 真实建池时暴露，纯单测无法覆盖运行时行为，故用 ServiceLoader 直接验证注册存在且
 * 指向包装类（元数据内容委托 Hikari，由同义词表逐项断言）。关闭路径：池销毁
 * {@code DataSourcePoolDestroyer} 仅按 {@code instanceof AutoCloseable} 调用
 * {@code close()}，本组判别式锁定外层关闭必须关闭内层池；且 close 无论是否已建池
 * 都标记关闭，此后取连接拒绝、不得经懒初始化重建（对齐 HikariDataSource 语义，
 * 防优雅关闭后重开连接池）。</p>
 *
 * <p>TASK-141 并发交错：close 与首次懒建池必须在同一生命周期边界协调——首次取连接
 * 已通过入口检查并等待进入建池临界区时另一线程完成 close，首次取连接不得在关闭后
 * 创建无人关闭的内层池。判别不靠睡眠碰运气：主线程先占住包装类的建池管程
 * （{@code innerPool} 的 {@code synchronized(this)}），首次取连接线程阻塞在该管程入口
 * 即静态证明其已通过入口检查（closed 当时仍为 false）；等待以条件为准（线程状态轮询），
 * 不以固定时长为准。另一判别锁定：既有线程在空池内等待物理连接期间 close 必须完成
 * （不得持包装类生命周期锁等待数据库连接）且重复 close 幂等。</p>
 */
class TimingHikariDataSourceWiringTest {

    /** SPI 必须把池元数据注册在包装类名下（注册缺失 = ShardingSphere 建池 NPE） */
    @Test
    void poolMetaDataSpi_registeredUnderWrapperClassName() {
        ServiceLoader<DataSourcePoolMetaData> loader = ServiceLoader.load(DataSourcePoolMetaData.class);
        assertTrue(StreamSupport.stream(loader.spliterator(), false)
                        .anyMatch(meta -> TimingHikariDataSource.class.getName().equals(String.valueOf(meta.getType()))),
                "META-INF/services 必须注册 getType()=TimingHikariDataSource 的池元数据");
    }

    /** 元数据必须完整继承 Hikari 同义词（url→jdbcUrl 等），否则连接属性归一化仍失败 */
    @Test
    void poolMetaDataSynonyms_matchHikariContract() {
        TimingHikariDataSourcePoolMetaData meta = new TimingHikariDataSourcePoolMetaData();
        assertEquals("jdbcUrl", meta.getPropertySynonyms().get("url"));
        assertEquals("maximumPoolSize", meta.getPropertySynonyms().get("maxPoolSize"));
        assertEquals("connectionTimeout", meta.getPropertySynonyms().get("connectionTimeoutMilliseconds"));
        assertNotNull(meta.getFieldMetaData(), "字段元数据（jdbcUrl 字段名）不得为空");
        assertFalse(meta.isDefault(), "不得声明为默认池元数据（Hikari 已是默认，避免歧义）");
    }

    /** 关闭路径判别：包装类必须可被 ShardingSphere 池销毁（instanceof AutoCloseable）识别 */
    @Test
    void wrapper_recognizedAsAutoCloseable_onShardingSphereClosePath() {
        assertTrue(new TimingHikariDataSource() instanceof AutoCloseable,
                "ShardingSphere DataSourcePoolDestroyer 仅按 instanceof AutoCloseable 关闭池，"
                        + "包装类必须实现该接口，否则优雅关闭时内层 Hikari 池泄漏");
    }

    /** 外层关闭必须关闭内层真实池（内层池存在时），且关闭后取连接被拒绝 */
    @Test
    void outerClose_closesInnerPool() throws Exception {
        TimingHikariDataSource wrapper = newWrapperForPoolCreation();
        try {
            HikariDataSource inner = ReflectionTestUtils.invokeMethod(wrapper, "buildPool");
            assertNotNull(inner, "内层池构建失败");
            ReflectionTestUtils.setField(wrapper, "pool", inner);
            assertFalse(inner.isClosed());
            wrapper.close();
            assertTrue(inner.isClosed(), "外层 close 必须关闭内层真实 Hikari 池");
            assertThrows(SQLException.class, wrapper::getConnection, "关闭后取连接必须拒绝");
        } finally {
            wrapper.close();
        }
    }

    /**
     * 关闭状态判别（收口后修订二）：未建池时 close 也必须标记关闭——此后取连接拒绝、
     * 不得经懒初始化重建池，否则优雅关闭后的请求会把连接池重新打开
     * （原 HikariDataSource.close() 未建池也标记关闭，本判别式对齐该语义）。
     */
    @Test
    void close_beforeInnerPoolCreated_marksClosed_andRejectsConnection() {
        TimingHikariDataSource wrapper = newWrapperForPoolCreation();
        wrapper.close();
        assertTrue(wrapper.isClosed(), "close 必须标记关闭状态（即使内层池尚未创建）");
        assertNull(ReflectionTestUtils.getField(wrapper, "pool"), "close 不得触发内层池懒创建");
        assertThrows(SQLException.class, wrapper::getConnection, "关闭后取连接必须拒绝");
        assertNull(ReflectionTestUtils.getField(wrapper, "pool"), "关闭后取连接不得经懒初始化重建内层池");
    }

    // ==================== TASK-141：close 与首次建池并发交错判别 ====================

    /** 入口检查/建池交错判别（无参重载）：close 在首次取连接等待建池期间完成，其后不得建出无人关闭的新池 */
    @Test
    void close_betweenEntryCheckAndFirstPoolCreation_rejectsAndLeavesNoPool() throws Exception {
        assertCloseWinsRaceOverFirstPoolCreation(false);
    }

    /** 同上，走 getConnection(username, password) 重载：两个重载共享同一建池边界，必须同等受关闭约束 */
    @Test
    void close_betweenEntryCheckAndFirstPoolCreation_credentialOverloadSameBoundary() throws Exception {
        assertCloseWinsRaceOverFirstPoolCreation(true);
    }

    /**
     * 既有取连接等待 + 重复 close 判别：内层池已建立、另一线程在空池内等待物理连接时，
     * close 必须及时完成（不持包装类生命周期锁等待数据库连接）、重复 close 幂等，等待线程以异常收场。
     */
    @Test
    void repeatedClose_withPendingConnectionWait_isIdempotent_andNotBlockedByPoolWait() throws Exception {
        TimingHikariDataSource wrapper = newWrapperForPoolCreation();
        // 等待线程的最大池内等待窗口：缺陷实现若把取连接挪进包装类锁，close 会被拖过 1s 判别线
        wrapper.setConnectionTimeout(2000);
        try {
            HikariDataSource inner = ReflectionTestUtils.invokeMethod(wrapper, "buildPool");
            assertNotNull(inner, "内层池构建失败");
            ReflectionTestUtils.setField(wrapper, "pool", inner);
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    wrapper.getConnection();
                } catch (Throwable t) {
                    caught.set(t);
                }
            }, "conn-waiter");
            waiter.start();
            // 条件等待：等待线程已进入空池的物理连接等待（HikariCP bag 内 parkNanos → TIMED_WAITING；
            // 该路径上没有其他限时等待点，且池为空、无可复用条目）
            awaitUntil(() -> waiter.getState() == Thread.State.TIMED_WAITING && caught.get() == null,
                    "等待线程应已在空池内等待物理连接");
            long t0 = System.nanoTime();
            wrapper.close();
            long firstCloseMs = (System.nanoTime() - t0) / 1_000_000;
            wrapper.close();
            long doubleCloseMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(firstCloseMs < 1000,
                    "close 被等待线程的池内等待拖住 " + firstCloseMs + "ms——不得持包装类生命周期锁等待数据库连接");
            assertTrue(doubleCloseMs < 1000, "重复 close 同样不得被池内等待阻塞（" + doubleCloseMs + "ms）");
            assertTrue(inner.isClosed(), "重复 close 后内层池必须已关闭");
            wrapper.close();
            waiter.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(waiter.isAlive(), "等待线程未在 5s 内随池关闭结束");
            assertNotNull(caught.get(), "池已关闭，等待中的取连接必须以异常结束");
            assertThrows(SQLException.class, wrapper::getConnection, "关闭后取连接必须拒绝");
        } finally {
            wrapper.close();
        }
    }

    /**
     * 确定性交错：主线程先占住包装类建池管程 → 首次取连接线程通过入口检查后阻塞在建池区入口
     * （{@code BLOCKED} 即证明入口检查时 closed 仍为 false）→ 主线程在其等待期间完成 close →
     * 放行首次取连接。修复后行为：首次取连接在建池区内被拒绝、内层池保持 null。
     */
    private void assertCloseWinsRaceOverFirstPoolCreation(boolean credentialOverload) throws Exception {
        TimingHikariDataSource wrapper = newWrapperForPoolCreation();
        // 若实现缺陷导致关闭后建池，让缺陷路径的取连接按 Hikari 最小允许值 250ms 快速失败，不拖慢用例
        wrapper.setConnectionTimeout(250);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                if (credentialOverload) {
                    wrapper.getConnection("u", "p");
                } else {
                    wrapper.getConnection();
                }
            } catch (Throwable t) {
                caught.set(t);
            }
        }, "first-getConnection");
        synchronized (wrapper) {
            first.start();
            awaitUntil(() -> first.getState() == Thread.State.BLOCKED,
                    "首次取连接应阻塞在包装类建池临界区入口（证明其已通过入口检查）");
            assertNull(caught.get(), "阻塞在建池区入口时不得已有异常");
            wrapper.close();
            assertTrue(wrapper.isClosed(), "close 必须标记关闭状态");
        }
        first.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(first.isAlive(), "首次取连接线程未在 10s 内结束");
        assertInstanceOf(SQLException.class, caught.get(), "关闭后进入建池区的首次取连接必须被拒绝");
        assertNull(ReflectionTestUtils.getField(wrapper, "pool"),
                "close 完成后包装类不得再创建内层池（该池由本包装类新建且无人关闭）");
        assertThrows(SQLException.class, wrapper::getConnection, "关闭后取连接必须拒绝");
        wrapper.close();
    }

    /** 条件等待（固定条件 + 截止时间，不依赖固定时长/睡眠碰运气） */
    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                fail("10s 内未等到条件：" + what);
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    /** YAML 属性转发核对：包装类 setter 必须逐项落入内层池配置（与 sharding.yaml 注释一致） */
    @Test
    void yamlProperties_forwardToInnerPoolConfig() {
        TimingHikariDataSource wrapper = newWrapperForPoolCreation();
        try {
            wrapper.setUsername("u1");
            wrapper.setPassword("p1");
            wrapper.setMaximumPoolSize(10);
            wrapper.setConnectionTimeout(250);
            wrapper.setIdleTimeout(60000);
            wrapper.setMaxLifetime(2100000);
            wrapper.setKeepaliveTime(0);
            wrapper.setMinimumIdle(1);
            HikariDataSource inner = ReflectionTestUtils.invokeMethod(wrapper, "buildPool");
            assertNotNull(inner);
            assertEquals("jdbc:mysql://127.0.0.1:1/none", inner.getJdbcUrl());
            assertEquals("u1", inner.getUsername());
            assertEquals("p1", inner.getPassword());
            assertEquals(10, inner.getMaximumPoolSize());
            assertEquals(250, inner.getConnectionTimeout());
            assertEquals(60000, inner.getIdleTimeout());
            assertEquals(2100000, inner.getMaxLifetime());
            assertEquals(0, inner.getKeepaliveTime());
            assertEquals(1, inner.getMinimumIdle());
        } finally {
            wrapper.close();
        }
    }

    /** 结构守卫：包装类不得有 setDataSourceClassName——该 YAML 键必须保持被 ShardingSphere 静默跳过 */
    @Test
    void noSetDataSourceClassName_keyIsSilentlySkippedByDesign() {
        assertThrows(NoSuchMethodException.class,
                () -> TimingHikariDataSource.class.getMethod("setDataSourceClassName", String.class),
                "出现 setDataSourceClassName 会让 HikariCP 走 dataSourceClassName 委托分支"
                        + "（对已移除属性套用 MySQL 默认查询属性即抛错），必须保持缺失");
    }

    /** 构造可安全建内层池的包装类：真实驱动形态的伪 URL（端口 1 无监听）+ initializationFailTimeout=-1（不起真实连接） */
    private TimingHikariDataSource newWrapperForPoolCreation() {
        TimingHikariDataSource wrapper = new TimingHikariDataSource();
        wrapper.setJdbcUrl("jdbc:mysql://127.0.0.1:1/none");
        wrapper.setInitializationFailTimeout(-1);
        return wrapper;
    }
}
