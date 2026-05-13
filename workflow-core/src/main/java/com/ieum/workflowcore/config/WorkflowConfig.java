package com.ieum.workflowcore.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class WorkflowConfig {

    @Bean(name = "workflowExecutor")
    public Executor workflowExecutor(
            @Value("${ieum.workflow.executor.core-pool-size:4}") int corePoolSize,
            @Value("${ieum.workflow.executor.max-pool-size:8}") int maxPoolSize,
            @Value("${ieum.workflow.executor.queue-capacity:50}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("workflow-");
        executor.initialize();
        return executor;
    }

}
