package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.engine.event.*;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

class DistributedInfrastructureIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "IEUM_TEST_REDIS_HOST", matches = ".+")
    void realRedisDeliversEventsAndCompletionAcrossInstances() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("IEUM_TEST_REDIS_PORT", "6379"));
        var factory = new LettuceConnectionFactory(System.getenv("IEUM_TEST_REDIS_HOST"), port);
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        var green = new ExecutionEventPublisher();
        var blue = new ExecutionEventPublisher();
        ObjectProvider<ExecutionEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(blue);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var receiving = new RedisExecutionEventRelay(redis, mapper, provider, "integration-blue");
        var sending = new RedisExecutionEventRelay(redis, mapper, provider, "integration-green");
        ReflectionTestUtils.setField(green, "relay", sending);
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        // A single thread preserves event -> completion ordering, as in production configuration.
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        listener.setTaskExecutor(executor);
        listener.addMessageListener(receiving, new ChannelTopic(RedisExecutionEventRelay.CHANNEL));
        try {
            listener.afterPropertiesSet(); listener.start();
            UUID id = UUID.randomUUID();
            var event = ExecutionEvent.executionCompleted(id, UUID.randomUUID(), ExecutionStatus.SUCCESS);
            StepVerifier.create(blue.subscribe(id))
                .then(() -> { green.publish(id, event); green.complete(id); })
                .expectNext(event).expectComplete().verify(Duration.ofSeconds(10));
        } finally { listener.destroy(); executor.shutdownNow(); factory.destroy(); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IEUM_TEST_POSTGRES_URL", matches = ".+")
    void realPostgresPreventsTwoInstancesOwningOneExecution() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("IEUM_TEST_POSTGRES_URL"),
            System.getenv("IEUM_TEST_POSTGRES_USER"), System.getenv("IEUM_TEST_POSTGRES_PASSWORD"));
        var locks = new ExecutionAdvisoryLocks(source);
        UUID id = UUID.randomUUID();
        try (var first = locks.tryAcquire(id)) {
            assertThat(first).isNotNull();
            assertThat(locks.tryAcquire(id)).isNull();
        }
        try (var second = locks.tryAcquire(id)) { assertThat(second).isNotNull(); }
    }
}
