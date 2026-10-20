package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.queue.ExecutionJobQueueBootstrap;
import com.ieum.api.workflow.queue.ExecutionJobWorker;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.Scheduler;
import org.quartz.SchedulerMetaData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class DeploymentEndpointTest {
    @TempDir Path directory;
    private final Scheduler scheduler = mock(Scheduler.class);
    private final ExecutionJobQueueBootstrap bootstrap = mock(ExecutionJobQueueBootstrap.class);
    private final ExecutionJobWorker worker = mock(ExecutionJobWorker.class);
    private final WorkflowExecutionRunner runner = mock(WorkflowExecutionRunner.class);
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final ExecutionEventPublisher events = new ExecutionEventPublisher();
    private DeploymentControl control;
    private DeploymentEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(directory.resolve("token"), "test-token");
        Files.writeString(directory.resolve("phase"), "ready");
        Files.writeString(directory.resolve("active"), "blue");
        control = new DeploymentControl(mock(StringRedisTemplate.class), "blue", directory.toString());
        var metadata = mock(SchedulerMetaData.class);
        when(scheduler.getMetaData()).thenReturn(metadata);
        when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
        var draining = new AtomicBoolean();
        doAnswer(call -> { draining.set(true); return null; }).when(bootstrap).stopConsuming();
        doAnswer(call -> { draining.set(false); return null; }).when(bootstrap).resumeConsuming();
        when(bootstrap.isDraining()).thenAnswer(call -> draining.get());
        executor.initialize();
        var connections = new DeploymentConnections(events);
        endpoint = new DeploymentEndpoint(control, bootstrap, scheduler, executor, events,
            mock(SimpUserRegistry.class), worker, runner, connections,
            new DeploymentRequests(connections), new DeploymentWorkTracker(connections),
            directory.toString(), "blue");
    }

    @AfterEach
    void shutDown() { executor.shutdown(); }

    @Test
    void drainRetireResumeSeparatesBackgroundJobsFromConnections() throws Exception {
        assertThatThrownBy(() -> endpoint.control("retire", "test-token"))
            .isInstanceOf(ResponseStatusException.class);
        endpoint.control("drain", "test-token");
        assertThat(control.isOwner()).isFalse();
        verify(scheduler).standby();
        StepVerifier.create(events.subscribe(UUID.randomUUID()))
            .then(() -> {
                try { endpoint.control("retire", "test-token"); }
                catch (Exception e) { throw new AssertionError(e); }
            }).verifyComplete();
        assertThat(endpoint.status()).containsEntry("retired", true).containsEntry("protocolVersion", 2);
        endpoint.control("resume", "test-token");
        verify(bootstrap).resumeConsuming();
        verify(scheduler).start();
        assertThat(control.isOwner()).isTrue();
        assertThat(endpoint.status()).containsEntry("retired", false).containsEntry("draining", false);
    }

    @Test
    void invalidTokenCannotDrain() {
        assertThatThrownBy(() -> endpoint.control("drain", "wrong-token"))
            .isInstanceOf(ResponseStatusException.class);
        verify(bootstrap, never()).stopConsuming();
    }

    @Test
    void inlineAndQueuedWorkerJobsAreVisibleEvenWhenExecutorReportsIdle() throws Exception {
        when(worker.activeCount()).thenReturn(3);
        when(runner.activeCount()).thenReturn(1);
        assertThat(endpoint.status()).containsEntry("activeJobs", 3);
    }
}
