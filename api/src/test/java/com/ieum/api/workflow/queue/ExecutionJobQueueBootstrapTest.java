package com.ieum.api.workflow.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;

class ExecutionJobQueueBootstrapTest {

    private static final Duration MIN_IDLE = Duration.ofMinutes(10);

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOperations;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private ExecutionJobWorker worker;
    private ExecutionJobQueue queue;
    private ExecutionJobQueueBootstrap bootstrap;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);

        container = Mockito.mock(StreamMessageListenerContainer.class);
        worker = Mockito.mock(ExecutionJobWorker.class);
        queue = new ExecutionJobQueue(redisTemplate, container);

        bootstrap = new ExecutionJobQueueBootstrap(
            redisTemplate, container, worker, queue, MIN_IDLE, Duration.ZERO);
    }

    @Test
    @DisplayName("기동 시 컨슈머 그룹을 만들고 소비를 시작한다")
    void run_createsGroupAndStartsConsuming() {
        bootstrap.run(null);

        verify(streamOperations).createGroup(
            ExecutionJobQueue.STREAM_KEY, ReadOffset.from("0"), ExecutionJobQueue.GROUP);
        verify(container).register(any(StreamReadRequest.class), eq(worker));
        verify(container).start();
    }

    @Test
    @DisplayName("그룹이 이미 있으면(BUSYGROUP) 그대로 진행한다")
    void run_groupAlreadyExists_continues() {
        when(streamOperations.createGroup(anyString(), any(ReadOffset.class), anyString()))
            .thenThrow(new RedisSystemException("BUSYGROUP Consumer Group name already exists",
                new RuntimeException()));

        bootstrap.run(null);

        verify(container).start();
    }

    @Test
    @DisplayName("Redis 장애로 기동에 실패하면 소비를 시작하지 않고, 컨테이너 상태와 무관하게 enqueue가 폴백한다")
    void run_redisDown_doesNotStartConsuming() {
        when(streamOperations.createGroup(anyString(), any(ReadOffset.class), anyString()))
            .thenThrow(new RedisSystemException("redis down", new RuntimeException()));

        bootstrap.run(null);

        verify(container, never()).start();
        when(container.isRunning()).thenReturn(true);
        assertThat(queue.enqueue(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("프로그래밍 오류는 삼키지 않는다 — '내구성 0'이 정상 기동으로 위장되면 안 된다")
    void run_programmingError_propagates() {
        when(streamOperations.createGroup(anyString(), any(ReadOffset.class), anyString()))
            .thenThrow(new IllegalStateException("설정 오류"));

        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("고아 잡 회수는 minIdle을 지켜 pending id만 claim한다 — 살아 있는 소비자의 in-flight를 뺏지 않는다")
    void reclaim_claimsPendingIdsWithMinIdle() {
        when(container.isRunning()).thenReturn(true);
        RecordId orphanId = RecordId.of("5-0");
        givenPending(orphanId);
        MapRecord<String, String, String> claimed = jobRecord(orphanId);
        when(streamOperations.claim(anyString(), anyString(), anyString(), any(XClaimOptions.class)))
            .thenReturn(List.of(claimed));

        bootstrap.reclaimOrphanedJobs();

        ArgumentCaptor<XClaimOptions> captor = ArgumentCaptor.forClass(XClaimOptions.class);
        verify(streamOperations).claim(eq(ExecutionJobQueue.STREAM_KEY),
            eq(ExecutionJobQueue.GROUP), eq(ExecutionJobQueue.CONSUMER), captor.capture());
        assertThat(captor.getValue().getMinIdleTime()).isEqualTo(MIN_IDLE);
        assertThat(captor.getValue().getIds()).containsExactly(orphanId);

        verify(worker).onMessage(claimed);
    }

    @Test
    @DisplayName("minIdle에 걸려 claim이 비면 아무것도 실행하지 않는다")
    void reclaim_nothingClaimable_doesNotRun() {
        when(container.isRunning()).thenReturn(true);
        givenPending(RecordId.of("5-0"));
        when(streamOperations.claim(anyString(), anyString(), anyString(), any(XClaimOptions.class)))
            .thenReturn(List.of());

        bootstrap.reclaimOrphanedJobs();

        verify(worker, never()).onMessage(any());
    }

    @Test
    @DisplayName("소비가 안 뜬 상태에서는 회수를 시도하지 않는다")
    void reclaim_containerNotRunning_skips() {
        when(container.isRunning()).thenReturn(false);

        bootstrap.reclaimOrphanedJobs();

        verify(streamOperations, never())
            .pending(anyString(), anyString(), any(Range.class), anyLong());
    }

    @Test
    @DisplayName("폴링 오류는 소비를 unhealthy로 내려 enqueue를 폴백시키고, 그룹 재생성에 성공하면 복구한다")
    void onPollError_marksUnhealthyThenRecoversOnGroupRecreate() {
        bootstrap.run(null);
        when(container.isRunning()).thenReturn(true);
        assertThat(queue.enqueue(UUID.randomUUID())).isTrue();

        // 그룹이 사라졌고 재생성도 실패 — 폴백이 유지돼야 한다
        when(streamOperations.createGroup(anyString(), any(ReadOffset.class), anyString()))
            .thenThrow(new RedisSystemException("redis down", new RuntimeException()));
        bootstrap.onPollError(new RedisSystemException("NOGROUP", new RuntimeException()));
        assertThat(queue.enqueue(UUID.randomUUID())).isFalse();

        // 재생성이 되면(=Redis 정상, 그룹 복구) 다시 큐를 쓴다
        Mockito.reset(streamOperations);
        bootstrap.onPollError(new RedisSystemException("NOGROUP", new RuntimeException()));

        verify(streamOperations).createGroup(
            ExecutionJobQueue.STREAM_KEY, ReadOffset.from("0"), ExecutionJobQueue.GROUP);
        assertThat(queue.enqueue(UUID.randomUUID())).isTrue();
    }

    private void givenPending(RecordId id) {
        when(streamOperations.pending(anyString(), anyString(), any(Range.class), anyLong()))
            .thenReturn(new PendingMessages("runners", List.of(new PendingMessage(id,
                Consumer.from(ExecutionJobQueue.GROUP, ExecutionJobQueue.CONSUMER),
                Duration.ZERO, 1L))));
    }

    private MapRecord<String, String, String> jobRecord(RecordId id) {
        return StreamRecords
            .mapBacked(Map.of(ExecutionJobQueue.FIELD_EXECUTION_ID, "irrelevant"))
            .withId(id)
            .withStreamKey(ExecutionJobQueue.STREAM_KEY);
    }
}
