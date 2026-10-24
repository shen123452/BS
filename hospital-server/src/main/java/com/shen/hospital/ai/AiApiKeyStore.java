package com.shen.hospital.ai;

import com.shen.hospital.common.BusinessException;
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

/** The provider credential is stored only in MySQL and never returned by an API. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiApiKeyStore implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiApiKeyStore.class);
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final AiProperties config;

    public AiApiKeyStore(@Qualifier("mysqlDataSource") DataSource dataSource, AiProperties config) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(3);
        this.config = config;
    }

    @Override public void run(ApplicationArguments args) {
        // Additive, idempotent migration; never recreates existing business tables.
        new ResourceDatabasePopulator(new ClassPathResource("db/ai-provider-credential.sql")).execute(dataSource);
        importEnvironmentKey();
        log.info("AI credential source: {}", apiKey() == null ? "NONE" : "DATABASE");
    }

    void importEnvironmentKey() {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            String key = validate(config.apiKey());
            // Import an environment key once; a stored key always takes priority.
            jdbc.update("INSERT IGNORE INTO ai_provider_credential(provider,api_key) VALUES ('BAILIAN',?)", key);
        }
    }

    public String apiKey() {
        var keys = jdbc.query("SELECT api_key FROM ai_provider_credential WHERE provider='BAILIAN'",
                (row, index) -> row.getString(1));
        return keys.isEmpty() ? null : keys.getFirst();
    }

    public void save(String raw, Long updatedBy) {
        String key = validate(raw);
        jdbc.update("INSERT INTO ai_provider_credential(provider,api_key,updated_by) VALUES ('BAILIAN',?,?) "
                + "ON DUPLICATE KEY UPDATE api_key=VALUES(api_key),updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP(3)", key, updatedBy);
    }

    static String validate(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() < 8 || value.length() > 512 || value.chars().anyMatch(c -> c < 33 || c > 126))
            throw BusinessException.invalid("API Key 须为 8～512 位非空格英文字符，请检查粘贴内容");
        return value;
    }
}
