package com.shen.hospital.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class KnowledgeTaskConfig {
    @Bean
    public org.springframework.boot.ApplicationRunner recoverKnowledgeTasks(com.shen.hospital.knowledge.service.KnowledgeService knowledge) {
        return args -> knowledge.recoverInterrupted();
    }
    @Bean("knowledgeTaskExecutor")
    public ThreadPoolTaskExecutor knowledgeTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("knowledge-ingest-"); executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize(); return executor;
    }
}
