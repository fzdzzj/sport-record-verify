package com.sportverify.user.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置（好友列表分页，规范差异「好友列表」分页场景）。
 *
 * <p>注册分页插件并设单页上限（500），防深翻页拖垮数据库；
 * 与 record-service 的 MybatisPlusConfig 保持一致的口径。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件（DbType 显式 MYSQL，避免从连接元数据探测出错）。
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
