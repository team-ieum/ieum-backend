package com.ieum.api.deployment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentConfiguration {
    @Bean
    ThreadPoolTaskExecutor executionEventRelayExecutor(RedisExecutionEventRelay relay,
            @Value("${ieum.deployment.event-relay.queue-capacity:10000}") int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("Event relay queue capacity must be positive");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("execution-relay-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Lettuce can submit from its I/O thread: never block, run inline, or grow this queue.
        // Drop the newest delivery while accepted tasks keep FIFO order. Pub/Sub progress is best
        // effort; DB terminal reconciliation closes streams even if completion was dropped.
        executor.setRejectedExecutionHandler((task, pool) -> relay.recordDroppedDelivery());
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
