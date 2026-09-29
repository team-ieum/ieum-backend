package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ieum.api.workflow.queue.ExecutionJobQueue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultExecutionJobEnqueuerTest {

    @Test
    @DisplayName("enqueue는 ExecutionJobQueue에 위임하고 발행 결과(true/false)를 그대로 돌려준다")
    void enqueue_delegatesToQueue() {
        ExecutionJobQueue queue = mock(ExecutionJobQueue.class);
        DefaultExecutionJobEnqueuer enqueuer = new DefaultExecutionJobEnqueuer(queue);
        UUID queued = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        when(queue.enqueue(queued)).thenReturn(true);
        when(queue.enqueue(rejected)).thenReturn(false);

        assertThat(enqueuer.enqueue(queued)).isTrue();
        assertThat(enqueuer.enqueue(rejected)).isFalse();
    }
}
