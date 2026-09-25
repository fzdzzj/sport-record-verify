package com.sportverify.record.config;

import org.apache.shardingsphere.infra.datasource.pool.hikari.metadata.HikariDataSourcePoolMetaData;
import org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolFieldMetaData;
import org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolMetaData;

import java.util.Collection;
import java.util.Map;

/**
 * {@link TimingHikariDataSource} 的 ShardingSphere 池元数据注册（TASK-140）。
 *
 * <p>ShardingSphere 5.4.1 按 {@code dataSourceClassName} 精确匹配 TypedSPI
 * （{@code TypedSPILoader.findService}）选择池元数据——属性同义词表（标准键 url →
 * Hikari 的 jdbcUrl、maxPoolSize → maximumPoolSize 等）即来自该元数据。没有注册时
 * YAML 的 {@code jdbcUrl} 无法归一化为标准 {@code url}，建池在
 * {@code StorageResourceUtils} 直接 NPE（已实测复现）。本类把 Hikari 元数据全部
 * 原样委托、仅将 {@code getType()} 指向包装类，经 {@code META-INF/services} 注册
 * ——这是 ShardingSphere 的公共 SPI 机制（pool-hikari 模块自身即以此接入），非私有
 * API。{@code isDefault()} 显式 false，避免与 Hikari 元数据形成双默认歧义。</p>
 */
public final class TimingHikariDataSourcePoolMetaData implements DataSourcePoolMetaData {

    private final HikariDataSourcePoolMetaData hikari = new HikariDataSourcePoolMetaData();

    @Override
    public Object getType() {
        return TimingHikariDataSource.class.getName();
    }

    @Override
    public boolean isDefault() {
        return false;
    }

    @Override
    public Map<String, Object> getDefaultProperties() {
        return hikari.getDefaultProperties();
    }

    @Override
    public Map<String, Object> getSkippedProperties() {
        return hikari.getSkippedProperties();
    }

    @Override
    public Map<String, String> getPropertySynonyms() {
        return hikari.getPropertySynonyms();
    }

    @Override
    public Collection<String> getTransientFieldNames() {
        return hikari.getTransientFieldNames();
    }

    @Override
    public DataSourcePoolFieldMetaData getFieldMetaData() {
        return hikari.getFieldMetaData();
    }
}
