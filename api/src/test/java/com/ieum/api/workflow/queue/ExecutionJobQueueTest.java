package com.ieum.api.workflow.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

class ExecutionJobQueueTest {

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOperations;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private ExecutionJobQueue queue;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        streamOperations = Mockito.mock(StreamOperations.class);
        container = Mockito.mock(StreamMessageListenerContainer.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        when(container.isRunning()).thenReturn(true);

        queue = new ExecutionJobQueue(redisTemplate, container);
    }

    @Test
    @DisplayName("발행 성공 시 true를 반환하고 잡을 실행 스트림에 넣는다")
    void enqueue_success_returnsTrue() {
        UUID executionId = UUID.randomUUID();

        boolean result = queue.enqueue(executionId);

        assertThat(result).isTrue();
        assertThat(publishedRecord().getStream()).isEqualTo(ExecutionJobQueue.STREAM_KEY);
    }

    @Test
    @DisplayName("페이로드에는 executionId만 담긴다 — 평문 트리거 입력이 Redis로 새지 않는다")
    void enqueue_payloadCarriesOnlyExecutionId() {
        UUID executionId = UUID.randomUUID();

        queue.enqueue(executionId);

        assertThat(publishedRecord().getValue())
            .containsExactly(entry(ExecutionJobQueue.FIELD_EXECUTION_ID, executionId.toString()));
    }

    private MapRecord<String, String, String> publishedRecord() {
        ArgumentCaptor<MapRecord<String, String, String>> captor =
            ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(captor.capture(), any(XAddOptions.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("Redis 장애로 발행이 실패하면 예외 대신 false를 반환한다(호출자가 직접 실행하도록)")
    void enqueue_redisFailure_returnsFalse() {
        when(streamOperations.add(any(MapRecord.class), any(XAddOptions.class)))
            .thenThrow(new RuntimeException("redis down"));

        boolean result = queue.enqueue(UUID.randomUUID());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("소비 컨테이너가 안 떴으면 발행하지 않고 false를 반환한다(꺼낼 소비자가 없으므로)")
    void enqueue_consumerNotRunning_skipsPublish() {
        when(container.isRunning()).thenReturn(false);

        boolean result = queue.enqueue(UUID.randomUUID());

        assertThat(result).isFalse();
        verify(streamOperations, never()).add(any(MapRecord.class), any(XAddOptions.class));
    }
}
