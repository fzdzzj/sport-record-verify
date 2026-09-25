package com.sportverify.record.config;

import org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolMetaData;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TimingHikariDataSource 与 ShardingSphere 池元数据 SPI 的接线判别式（TASK-140）。
 *
 * <p>ShardingSphere 5.4.1 按 dataSourceClassName 精确匹配池元数据 TypedSPI；若
 * META-INF/services 注册缺失，YAML 的 jdbcUrl 无法归一化（启动 NPE）——此失败只
 * 在真实建池时暴露，纯单测无法覆盖运行时行为，故用 ServiceLoader 直接验证注册
 * 存在且指向包装类（元数据内容委托 Hikari，由同义词表逐项断言）。</p>
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
}
