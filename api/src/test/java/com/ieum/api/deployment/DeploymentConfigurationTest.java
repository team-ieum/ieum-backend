package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class DeploymentConfigurationTest {
    private RedisExecutionEventRelay relay() {
        return new RedisExecutionEventRelay(mock(StringRedisTemplate.class), new ObjectMapper(),
            mock(ObjectProvider.class), "blue");
    }

    @Test
    void saturatedRelayDropsNewestWithoutBlockingSubmitterOrReorderingAcceptedTasks() throws Exception {
        var relay = relay();
        var executor = new DeploymentConfiguration().executionEventRelayExecutor(relay, 1);
        executor.initialize();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(2);
        var order = new CopyOnWriteArrayList<Integer>();
        try {
            executor.execute(() -> {
                entered.countDown();
                await(release);
                order.add(1);
                finished.countDown();
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { order.add(2); finished.countDown(); });
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> executor.execute(() -> order.add(3)));
            assertThat(relay.droppedDeliveryCount()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(1, 2);
        } finally { release.countDown(); executor.shutdown(); }
    }

    @Test
    void databaseTerminalReconciliationRepairsDroppedCompletionAtSaturation() throws Exception {
        var relay = relay();
        var publisher = new ExecutionEventPublisher();
        var executor = new DeploymentConfiguration().executionEventRelayExecutor(relay, 1);
        executor.initialize();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        var received = new CopyOnWriteArrayList<ExecutionEvent>();
        UUID id = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        publisher.subscribe(id).subscribe(received::add, error -> { throw new AssertionError(error); },
            () -> completed.set(true));
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var node = ExecutionEvent.nodeStarted(id, workflowId, "node", NodeType.AI);
            executor.execute(() -> { publisher.acceptRemote(id, node); delivered.countDown(); });
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> executor.execute(() -> publisher.completeLocal(id)));
            assertThat(relay.droppedDeliveryCount()).isEqualTo(1);
            release.countDown();
            assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completed).isFalse();

            var repository = mock(WorkflowExecutionRepository.class);
            var execution = mock(WorkflowExecution.class);
            var workflow = mock(Workflow.class);
            when(execution.getId()).thenReturn(id);
            when(execution.getWorkflow()).thenReturn(workflow);
            when(workflow.getId()).thenReturn(workflowId);
            when(execution.getStatus()).thenReturn(ExecutionStatus.SUCCESS);
            when(repository.findAllById(any())).thenReturn(List.of(execution));
            new ExecutionEventReconciler(publisher, repository).reconcile();
            assertThat(completed).isTrue();
            assertThat(received).hasSize(2);
            assertThat(received.getLast().executionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        } finally { release.countDown(); executor.shutdown(); }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Relay test timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
