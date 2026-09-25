package com.sportverify.record.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolMetaData;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
