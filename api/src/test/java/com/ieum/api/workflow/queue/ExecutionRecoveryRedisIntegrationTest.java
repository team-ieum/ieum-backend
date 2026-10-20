package com.ieum.api.workflow.queue;

import static com.ieum.api.workflow.queue.ExecutionJobQueue.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ieum.api.deployment.DeploymentControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/** Run only against a dedicated disposable Redis: production stream names are intentionally used. */
@EnabledIfEnvironmentVariable(named = "IEUM_TEST_REDIS_HOST", matches = ".+")
class ExecutionRecoveryRedisIntegrationTest {
    @TempDir Path controlDirectory;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private ExecutionJobQueueBootstrap bootstrap;
    private DeploymentControl control;
    private boolean ownsStream;

    @BeforeEach
    void connect() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("IEUM_TEST_REDIS_PORT", "6379"));
        factory = new LettuceConnectionFactory(System.getenv("IEUM_TEST_REDIS_HOST"), port);
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        // Refuse an occupied queue rather than deleting an unrelated stream.
        assertThat(redis.hasKey(STREAM_KEY)).as("Use a dedicated empty test Redis").isFalse();
        ownsStream = true;
        Files.writeString(controlDirectory.resolve("phase"), "ready");
        Files.writeString(controlDirectory.resolve("active"), "blue");
        control = new DeploymentControl(redis, "blue", controlDirectory.toString());
    }

    @AfterEach
    void cleanup() {
        if (bootstrap != null) bootstrap.stopConsuming();
        if (container != null) container.stop();
        if (redis != null && ownsStream) {
            redis.delete(STREAM_KEY);
            if (control != null) redis.delete("ieum:deployment:alive:" + control.consumerName());
        }
        if (factory != null) factory.destroy();
    }

    @Test
    void liveCurrentProcessCanRecoverItsOwnNonInFlightPendingDelivery() {
        redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
        var record = redis.opsForStream().add(STREAM_KEY, Map.of(FIELD_EXECUTION_ID, UUID.randomUUID().toString()));
        redis.opsForStream().read(Consumer.from(GROUP, control.consumerName()),
            StreamReadOptions.empty().count(1), StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        control.heartbeat();
        var worker = mock(ExecutionJobWorker.class);
        createMockContainerBootstrap(worker);
        // Zero idle is limited to the isolated fixture; production still defaults to ten minutes.
        bootstrap.reclaimOrphanedJobs();
        verify(worker).onMessage(argThat(message -> message.getId().equals(record)));
    }

    @Test
    void paginationReachesOrphanAfterThousandLiveConsumerEntries() {
        redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
        String live = "green-" + UUID.randomUUID();
        String dead = "blue-" + UUID.randomUUID();
        redis.opsForValue().set("ieum:deployment:alive:" + live, "1", Duration.ofMinutes(1));
        try {
            for (int i = 0; i < 1000; i++)
                redis.opsForStream().add(STREAM_KEY, Map.of(FIELD_EXECUTION_ID, UUID.randomUUID().toString()));
            redis.opsForStream().read(Consumer.from(GROUP, live), StreamReadOptions.empty().count(1000),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
            var orphan = redis.opsForStream().add(STREAM_KEY, Map.of(FIELD_EXECUTION_ID, UUID.randomUUID().toString()));
            redis.opsForStream().read(Consumer.from(GROUP, dead), StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
            var worker = mock(ExecutionJobWorker.class);
            createMockContainerBootstrap(worker);
            bootstrap.reclaimOrphanedJobs();
            verify(worker, never()).onMessage(any());
            bootstrap.reclaimOrphanedJobs();
            verify(worker).onMessage(argThat(message -> message.getId().equals(orphan)));
        } finally { redis.delete("ieum:deployment:alive:" + live); }
    }

    @Test
    void realListenerResumesConsumptionAfterDrain() {
        container = StreamMessageListenerContainer.create(factory,
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(50)).build());
        var worker = mock(ExecutionJobWorker.class);
        AtomicInteger handled = new AtomicInteger();
        doAnswer(call -> {
            MapRecord<String, String, String> record = call.getArgument(0);
            redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
            redis.opsForStream().delete(STREAM_KEY, record.getId());
            handled.incrementAndGet();
            return null;
        }).when(worker).onMessage(any());
        var queue = new ExecutionJobQueue(redis, container);
        ReflectionTestUtils.setField(queue, "distributed", true);
        bootstrap = new ExecutionJobQueueBootstrap(redis, container, worker, queue, Duration.ZERO, Duration.ZERO);
        ReflectionTestUtils.setField(bootstrap, "deploymentControl", control);
        UUID before = UUID.randomUUID(), after = UUID.randomUUID();
        try {
            bootstrap.run(null);
            queue.enqueue(before);
            await().atMost(Duration.ofSeconds(5)).until(() -> handled.get() == 1);
            bootstrap.stopConsuming();
            queue.enqueue(after); // Local consumption is stopped; publishing must still work.
            bootstrap.resumeConsuming();
            await().atMost(Duration.ofSeconds(5)).until(() -> handled.get() == 2);
        } finally {
            redis.delete(List.of("ieum:exec:published:" + before, "ieum:exec:published:" + after));
        }
    }

    @Test
    void cleanupRemovesOnlyDeadEmptyBootConsumers() {
        redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
        String dead = "blue-" + UUID.randomUUID(), live = "green-" + UUID.randomUUID();
        for (String consumer : List.of(dead, live)) {
            var id = redis.opsForStream().add(STREAM_KEY, Map.of(FIELD_EXECUTION_ID, UUID.randomUUID().toString()));
            redis.opsForStream().read(Consumer.from(GROUP, consumer), StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
            redis.opsForStream().acknowledge(STREAM_KEY, GROUP, id);
            redis.opsForStream().delete(STREAM_KEY, id);
        }
        redis.opsForValue().set("ieum:deployment:alive:" + live, "1", Duration.ofMinutes(1));
        try {
            createMockContainerBootstrap(mock(ExecutionJobWorker.class));
            bootstrap.reclaimOrphanedJobs();
            assertThat(redis.opsForStream().consumers(STREAM_KEY, GROUP).stream()
                .map(StreamInfo.XInfoConsumer::consumerName)).contains(live).doesNotContain(dead);
        } finally { redis.delete("ieum:deployment:alive:" + live); }
    }

    @SuppressWarnings("unchecked")
    private void createMockContainerBootstrap(ExecutionJobWorker worker) {
        container = mock(StreamMessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        var queue = new ExecutionJobQueue(redis, container);
        bootstrap = new ExecutionJobQueueBootstrap(redis, container, worker, queue, Duration.ZERO, Duration.ZERO);
        ReflectionTestUtils.setField(bootstrap, "deploymentControl", control);
    }
}
