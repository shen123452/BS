package com.shen.hospital.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 完整版阶段一的幂等增量迁移：工具中心、Prompt 模板与症状标签。
 * 只新增表与种子数据，不重建既有业务表。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CompletePhase1Schema implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CompletePhase1Schema.class);
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public CompletePhase1Schema(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(10);
    }

    @Override public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("db/complete-phase1.sql")).execute(dataSource);
        // 完整版预算放宽到 6 轮 / 12 次工具调用；对已存在的 agent_run 增量补列，保证运行自包含可回放。
        if (missing("agent_run", "prompt_revision_id"))
            jdbc.execute("ALTER TABLE agent_run ADD COLUMN prompt_revision_id BIGINT NULL AFTER prompt_hash");
        if (missing("agent_run", "prompt_content"))
            jdbc.execute("ALTER TABLE agent_run ADD COLUMN prompt_content MEDIUMTEXT NULL AFTER prompt_revision_id");
        log.info("Complete-phase1 schema ready (tool registry, prompt revisions, symptom tags)");
    }

    private boolean missing(String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, column);
        return count == null || count == 0;
    }
}
