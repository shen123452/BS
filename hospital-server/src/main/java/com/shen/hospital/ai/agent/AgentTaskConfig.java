package com.shen.hospital.ai.agent;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.concurrent.*;

@Configuration
@EnableScheduling
public class AgentTaskConfig {
    @Bean("agentTaskExecutor")
    ThreadPoolTaskExecutor agentTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("triage-agent-"); executor.setWaitForTasksToCompleteOnShutdown(false); executor.initialize();
        return executor;
    }
    @Bean(name="agentIoExecutor", destroyMethod="shutdownNow")
    ExecutorService agentIoExecutor() {
        return new ThreadPoolExecutor(0,4,60,TimeUnit.SECONDS,new SynchronousQueue<>(), task -> {
            var thread=new Thread(task,"triage-io"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean
    org.springframework.boot.ApplicationRunner recoverAgentTasks(TriageAgentService agent) {
        return args -> agent.recover();
    }
}
