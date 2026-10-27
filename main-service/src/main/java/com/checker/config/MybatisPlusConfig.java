package com.checker.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为数据库访问启用分页，并注册画廊批量插入所需的扩展方法。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 启用 MySQL 分页拦截，把分页查询转换为数据库侧的分页语句。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 注册批量插入扩展，使画廊数据接口可以一次写入多条记录。
     */
    @Bean
    public ISqlInjector batchSqlInjector() {
        return new BatchSqlInjector();
    }
}
