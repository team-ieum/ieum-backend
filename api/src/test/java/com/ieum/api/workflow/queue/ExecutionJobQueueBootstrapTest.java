package com.ieum.api.workflow.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOperations;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private ExecutionJobWorker worker;
    private ExecutionJobQueueBootstrap bootstrap;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        when(streamOperations.pending(anyString(), anyString(), any(Range.class), anyLong()))
            .thenReturn(new PendingMessages("runners", List.of()));

        container = Mockito.mock(StreamMessageListenerContainer.class);
        worker = Mockito.mock(ExecutionJobWorker.class);

        bootstrap = new ExecutionJobQueueBootstrap(redisTemplate, container, worker);
    }

    @Test
    @DisplayName("기동 시 컨슈머 그룹을 만들고 소비를 시작한다")
    void run_createsGroupAndStartsConsuming() {
        bootstrap.run(null);

        verify(streamOperations).createGroup(
            ExecutionJobQueue.STREAM_KEY, ReadOffset.from("0"), ExecutionJobQueue.GROUP);
        verify(container).register(any(StreamReadRequest.class), Mockito.eq(worker));
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
    @DisplayName("이전 프로세스가 남긴 pending 잡을 회수해 워커에 넘긴 뒤 소비를 시작한다")
    void run_reclaimsOrphanedJobsBeforeConsuming() {
        RecordId orphanId = RecordId.of("5-0");
        when(streamOperations.pending(anyString(), anyString(), any(Range.class), anyLong()))
            .thenReturn(new PendingMessages("runners",
                List.of(new PendingMessage(orphanId,
                    Consumer.from(ExecutionJobQueue.GROUP, ExecutionJobQueue.CONSUMER),
                    java.time.Duration.ZERO, 1L))));

        MapRecord<String, String, String> claimed = StreamRecords
            .mapBacked(Map.of(ExecutionJobQueue.FIELD_EXECUTION_ID, "irrelevant"))
            .withId(orphanId)
            .withStreamKey(ExecutionJobQueue.STREAM_KEY);
        when(streamOperations.claim(anyString(), anyString(), anyString(), any(XClaimOptions.class)))
            .thenReturn(List.of(claimed));

        bootstrap.run(null);

        // 회수가 소비 시작보다 먼저여야 신규 잡과 섞이지 않는다.
        InOrder inOrder = Mockito.inOrder(worker, container);
        inOrder.verify(worker).onMessage(claimed);
        inOrder.verify(container).start();
    }

    @Test
    @DisplayName("Redis 장애로 기동에 실패하면 소비를 시작하지 않는다 — enqueue가 폴백을 택하게 된다")
    void run_redisDown_doesNotStartConsuming() {
        when(streamOperations.createGroup(anyString(), any(ReadOffset.class), anyString()))
            .thenThrow(new RuntimeException("redis down"));

        bootstrap.run(null);

        verify(container, never()).start();
    }
}
