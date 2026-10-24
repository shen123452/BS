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

/** 完整版阶段三的幂等增量迁移：导诊运行持久事件表。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class CompletePhase3Schema implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CompletePhase3Schema.class);
    private final DataSource dataSource;

    public CompletePhase3Schema(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("db/complete-phase3.sql")).execute(dataSource);
        log.info("Complete-phase3 schema ready (agent run events)");
    }
}
