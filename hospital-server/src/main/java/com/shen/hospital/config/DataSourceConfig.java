package com.shen.hospital.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@MapperScan(basePackages = {"com.shen.hospital.auth.mapper", "com.shen.hospital.masterdata.mapper",
        "com.shen.hospital.consultation.mapper", "com.shen.hospital.knowledge.mapper", "com.shen.hospital.ai.agent",
        "com.shen.hospital.schedule.mapper", "com.shen.hospital.registration.mapper", "com.shen.hospital.record.mapper", "com.shen.hospital.ai.mapper",
        "com.shen.hospital.ai.tool", "com.shen.hospital.ai.prompt"},
        sqlSessionFactoryRef = "mysqlSqlSessionFactory")
public class DataSourceConfig {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.mysql")
    public DataSourceProperties mysqlProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public HikariDataSource mysqlDataSource(@Qualifier("mysqlProperties") DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("hospital-mysql");
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5000);
        ds.setConnectionInitSql("SET time_zone = '+08:00', SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'");
        return ds;
    }

    @Bean
    @ConfigurationProperties("spring.datasource.vector")
    public DataSourceProperties vectorProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public HikariDataSource vectorDataSource(@Qualifier("vectorProperties") DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("hospital-vector");
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(3000);
        ds.setInitializationFailTimeout(-1);
        ds.addDataSourceProperty("connectTimeout", "3");
        ds.addDataSourceProperty("socketTimeout", "5");
        return ds;
    }

    @Bean
    public SqlSessionFactory mysqlSqlSessionFactory(@Qualifier("mysqlDataSource") DataSource ds) throws Exception {
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        var pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        factory.setPlugins(interceptor);
        return factory.getObject();
    }

    @Bean
    @Primary
    public PlatformTransactionManager mysqlTransactionManager(@Qualifier("mysqlDataSource") DataSource ds) {
        return new JdbcTransactionManager(ds);
    }

    @Bean
    public PlatformTransactionManager vectorTransactionManager(@Qualifier("vectorDataSource") DataSource ds) {
        return new JdbcTransactionManager(ds);
    }

    @Bean
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource ds) {
        var template = new JdbcTemplate(ds);
        template.setQueryTimeout(5);
        return template;
    }
}
