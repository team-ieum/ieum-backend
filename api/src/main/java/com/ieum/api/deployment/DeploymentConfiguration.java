package com.ieum.api.deployment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentConfiguration {
    @Bean
    ThreadPoolTaskExecutor executionEventRelayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("execution-relay-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Do not discard completion when the bounded queue fills, or run it out of order on
        // the Redis subscription thread. Backpressure preserves the single-consumer ordering.
        executor.setRejectedExecutionHandler((task, pool) -> {
            try {
                while (!pool.isShutdown()) {
                    if (pool.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RejectedExecutionException("Execution relay is stopping");
        });
        return executor;
    }

    @Bean
    RedisMessageListenerContainer executionEventListener(RedisConnectionFactory connectionFactory,
            RedisExecutionEventRelay relay,
            @Qualifier("executionEventRelayExecutor") ThreadPoolTaskExecutor executor) {
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.setTaskExecutor(executor);
        listener.addMessageListener(relay, new ChannelTopic(RedisExecutionEventRelay.CHANNEL));
        return listener;
    }
}
