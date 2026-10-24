package com.shen.hospital.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 完整版阶段二的幂等增量迁移：聊天与向量分离的模型配置修订表。
 * 只新增表，不重建既有业务表；模型配置初始为空。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CompletePhase2Schema implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CompletePhase2Schema.class);
    private final DataSource dataSource;

    public CompletePhase2Schema(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("db/complete-phase2.sql")).execute(dataSource);
        log.info("Complete-phase2 schema ready (ai model config revisions)");
    }
}
