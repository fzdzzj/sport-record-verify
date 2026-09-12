package com.sportverify.record.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置（分片分页查询，规范「分片分页查询」场景）。
 *
 * <p>MyBatis-Plus × ShardingSphere 经典坑与对策：</p>
 * <ol>
 *   <li>分页插件必须作用于 ShardingSphere 代理 DataSource——本工程唯一 DataSource 即
 *       {@link ShardingDataSourceConfig} 创建的代理，MyBatis-Plus 自动绑定，勿再引入 spring.datasource；</li>
 *   <li>COUNT / LIMIT SQL 由代理按分片重写并在内存合并：WHERE 含 user_id 时单分片路由；
 *       不含分片键时广播全分片，代理仍返回跨分片合并的完整分页结果；</li>
 *   <li>DbType 显式 MYSQL，避免从连接元数据探测出错。</li>
 * </ol>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册分页插件（含单页上限保护，防深翻页拖垮分片代理）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
