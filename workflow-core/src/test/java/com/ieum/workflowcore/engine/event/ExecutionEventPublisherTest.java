package com.ieum.workflowcore.engine.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutionEventPublisherTest {

    private final ExecutionEventPublisher publisher = new ExecutionEventPublisher();

    @Test
    @DisplayName("구독자는 발행된 이벤트를 순서대로 수신하고 complete 시 스트림이 종료된다")
    void 발행_구독_완료_흐름() {
        UUID executionId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        List<ExecutionEvent> received = new CopyOnWriteArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        publisher.subscribe(executionId)
            .subscribe(received::add, err -> { }, () -> completed.set(true));

        publisher.publish(executionId,
            ExecutionEvent.nodeStarted(executionId, workflowId, "node-1", NodeType.TRIGGER));
        publisher.publish(executionId,
            ExecutionEvent.nodeCompleted(executionId, workflowId, "node-1", NodeType.TRIGGER, 12L));
        publisher.publish(executionId,
            ExecutionEvent.executionCompleted(executionId, workflowId, ExecutionStatus.SUCCESS));
        publisher.complete(executionId);

        assertThat(received).hasSize(3);
        assertThat(received.get(0).type()).isEqualTo(ExecutionEventType.NODE_STARTED);
        assertThat(received.get(1).type()).isEqualTo(ExecutionEventType.NODE_COMPLETED);
        assertThat(received.get(1).durationMs()).isEqualTo(12L);
        assertThat(received.get(2).type()).isEqualTo(ExecutionEventType.EXECUTION_COMPLETED);
        assertThat(received.get(2).executionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(completed).isTrue();
    }

    @Test
    @DisplayName("서로 다른 executionId의 스트림은 격리된다")
    void executionId별_격리() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<ExecutionEvent> receivedA = new CopyOnWriteArrayList<>();

        publisher.subscribe(a).subscribe(receivedA::add);

        publisher.publish(b,
            ExecutionEvent.nodeStarted(b, UUID.randomUUID(), "node-1", NodeType.AI));

        assertThat(receivedA).isEmpty();
    }
}
