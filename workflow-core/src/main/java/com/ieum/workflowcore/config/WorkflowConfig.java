package com.ieum.workflowcore.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableAsync
public class WorkflowConfig {

    @Bean(name = "workflowExecutor")
    public Executor workflowExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("workflow-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate(
        @Value("${ieum.http.connect-timeout-seconds:10}") int connectTimeoutSeconds,
        @Value("${ieum.http.read-timeout-seconds:30}") int readTimeoutSeconds
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutSeconds * 1000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        return new RestTemplate(factory);
    }
}
