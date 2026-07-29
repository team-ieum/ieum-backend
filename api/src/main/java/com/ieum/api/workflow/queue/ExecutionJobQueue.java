package com.ieum.api.workflow.queue;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 워크플로우 실행 잡을 Redis Stream에 발행한다(발행 전담 — 소비는 {@link ExecutionJobWorker},
 * 기동·복구는 {@link ExecutionJobQueueBootstrap}).
 *
 * <p><b>페이로드는 executionId 하나뿐이다.</b> 트리거 입력({@code workflow_runs.trigger_data})은
 * AES-256으로 암호화 저장되므로 평문을 Redis에 실으면 DB에서 닫은 노출을 큐로 다시 여는 셈이다.
 * 워커가 executionId로 DB를 읽고 거기서 복호한다 — 진실의 원본은 DB 하나로 유지된다.
 *
 * <p><b>Redis는 SPOF가 아니다.</b> 발행 실패는 예외로 전파하지 않고 {@code false}를 돌려
 * 호출자({@code WorkflowExecutionRunner})가 기존 {@code @Async} 직접 실행으로 폴백하게 한다.
 * 내구성(재시작 복구)은 잃지만 실행 자체는 성공한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionJobQueue {

    public static final String STREAM_KEY = "ieum:exec:jobs";
    public static final String GROUP = "runners";
    /** 단일 인스턴스 전제(SSE 허브가 in-memory)라 컨슈머 이름을 고정한다. */
    public static final String CONSUMER = "runner";
    public static final String FIELD_EXECUTION_ID = "executionId";

    /** 소비되지 않은 잡이 쌓여도 스트림이 무한히 자라지 않도록 근사 트리밍한다. */
    private static final long MAX_STREAM_LENGTH = 10_000L;

    private final StringRedisTemplate redisTemplate;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>>
        executionJobListenerContainer;

    /** @return 발행 성공 여부. false면 호출자가 직접 실행해야 한다(잡이 유실되지 않게). */
    public boolean enqueue(UUID executionId) {
        // 컨테이너가 안 떴으면(부팅 시 Redis 장애 등) 넣어도 꺼낼 소비자가 없다 — 넣지 않고 폴백시킨다.
        if (!executionJobListenerContainer.isRunning()) {
            log.warn("[ExecutionJobQueue] 소비자 미기동 — 큐 우회. executionId: {}", executionId);
            return false;
        }
        try {
            redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(Map.of(FIELD_EXECUTION_ID, executionId.toString()))
                    .withStreamKey(STREAM_KEY),
                XAddOptions.maxlen(MAX_STREAM_LENGTH).approximateTrimming(true));
            return true;
        } catch (Exception e) {
            log.warn("[ExecutionJobQueue] 발행 실패 — 큐 우회, 재시작 복구 불가. executionId: {}, error: {}",
                executionId, e.toString());
            return false;
        }
    }
}
