package com.glucose.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private Cors cors = new Cors();
    private Wx wx = new Wx();
    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private ImportCfg importCfg = new ImportCfg();
    private Qa qa = new Qa();
    private Analysis analysis = new Analysis();

    // YAML 使用 app.import；Java 的 import 是保留字，通过 Bean 属性映射保留该配置约定。
    public ImportCfg getImport() {
        return importCfg;
    }

    public void setImport(ImportCfg value) {
        this.importCfg = value;
    }

    @Data
    public static class Security {
        private String jwtSecret;
        private int tokenTtlHours = 2;
    }

    @Data
    public static class Cors {
        private String allowedOrigins = "*";
    }

    @Data
    public static class Wx {
        private boolean allowMock = false;
        private String appid;
        private String secret;
        public boolean configured() {
            return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
        }
    }

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 8000;
        private int maxConcurrent = 4;
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int dim = 512;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 8000;
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }

    @Data
    public static class ImportCfg {
        private String tempDir;
        private int previewExpireMinutes = 30;
    }

    @Data
    public static class Qa {
        private int perUserHourlyLimit = 20;
        private int questionMaxLength = 500;
        private int retrievalTopK = 5;
        private double similarityThreshold = 0.60;
        private int totalBudgetSeconds = 15;
    }

    @Data
    public static class Analysis {
        private int cacheTtlSeconds = 60;
    }
}
