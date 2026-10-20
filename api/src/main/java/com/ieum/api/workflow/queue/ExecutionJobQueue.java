package com.ieum.api.workflow.queue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 워크플로우 실행 잡을 Redis Stream에 발행한다(발행 전담 — 소비는 {@link ExecutionJobWorker},
 * 기동·복구는 {@link ExecutionJobQueueBootstrap}).
 *
 * <p><b>페이로드는 executionId 하나뿐이다.</b> 트리거 입력({@code workflow_runs.trigger_data})은
 * AES-256으로 암호화 저장되므로 평문을 Redis에 실으면 DB에서 닫은 노출을 큐로 다시 여는 셈이다.
 * 워커가 executionId로 DB를 읽고 거기서 복호한다 — 진실의 원본은 DB 하나로 유지된다.
 *
 * <p>단일 모드는 발행 실패에 {@code false}를 반환해 기존 직접 실행 폴백을 유지한다.
 * 분산 모드는 결과가 불명인 XADD를 직접 실행하지 않는다. DB의 PENDING 행을
 * {@link PendingExecutionRequeuer}가 재발행하므로 Redis 장애 동안 실행이 지연된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionJobQueue {

    /**
     * 소비자가 실제로 잡을 꺼내고 있는지. {@code container.isRunning()}만으로는 부족하다 —
     * Redis가 비영속 재기동하거나 키가 evict돼 스트림·그룹이 사라지면 폴링은 매번 {@code NOGROUP}으로
     * 실패하는데 컨테이너는 여전히 running이다. 그 상태로 발행하면 {@code XADD}가 스트림을 새로
     * 만들며 성공해서, 아무도 소비하지 않는 잡이 쌓이고 실행이 PENDING인 채 방치된다.
     * {@link ExecutionJobQueueBootstrap}이 갱신한다 — 폴링 실패 시 내리고, 기동·폴링 복구 성공 시와
     * 주기 회수의 {@code XPENDING} 성공 시 올린다(복구 프로브가 실패해도 최대 회수 주기 안에 되돌아온다).
     */
    private final AtomicBoolean consumerHealthy = new AtomicBoolean(false);
    @Value("${ieum.deployment.distributed.enabled:false}")
    private boolean distributed;

    // Keep the index while its stream entry exists: pending/poison/backlogged jobs must not gain
    // another entry every two minutes. Recovery also indexes unmarked older entries with a bounded
    // scan; an incomplete scan defers publication rather than risking another external execution.
    private static final DefaultRedisScript<Long> PUBLISH_ONCE = new DefaultRedisScript<>("""
        local prior = redis.call('GET', KEYS[2])
        if prior and #redis.call('XRANGE', KEYS[1], prior, prior, 'COUNT', 1) > 0 then
          redis.call('DEL', KEYS[4])
          return 0
        end
        local recoveryLimit = tonumber(ARGV[2])
        if recoveryLimit > 0 then
          local cursor = redis.call('HGET', KEYS[4], 'cursor') or '-'
          local scanEnd = redis.call('HGET', KEYS[4], 'end')
          if not scanEnd then
            local last = redis.call('XREVRANGE', KEYS[1], '+', '-', 'COUNT', 1)
            scanEnd = #last > 0 and last[1][1] or '0-0'
          end
          local complete = false
          for page = 1, 10 do
            local rows = redis.call('XRANGE', KEYS[1], cursor, scanEnd, 'COUNT', 100)
            for _, row in ipairs(rows) do
              for i = 1, #row[2], 2 do
                if row[2][i] == 'executionId' and row[2][i + 1] == ARGV[1] then
                  redis.call('SET', KEYS[2], row[1])
                  redis.call('DEL', KEYS[4])
                  return 0
                end
              end
            end
            if #rows < 100 or rows[#rows][1] == scanEnd then complete = true; break end
            cursor = '(' .. rows[#rows][1]
          end
          if not complete then
            redis.call('HSET', KEYS[4], 'cursor', cursor, 'end', scanEnd)
            redis.call('EXPIRE', KEYS[4], 3600)
            return -2
          end
          redis.call('DEL', KEYS[4])
          local attempts = tonumber(redis.call('GET', KEYS[3]) or '0')
          if attempts >= recoveryLimit then return -1 end
        end
        local id = redis.call('XADD', KEYS[1], '*', 'executionId', ARGV[1])
        redis.call('SET', KEYS[2], id)
        if recoveryLimit > 0 then redis.call('INCR', KEYS[3]) end
        return 1
        """, Long.class);

    static final DefaultRedisScript<Long> ACKNOWLEDGE_AND_DELETE = new DefaultRedisScript<>("""
        local published = redis.call('GET', KEYS[2])
        local acked = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
        redis.call('XDEL', KEYS[1], ARGV[2])
        if not published or published == ARGV[2] then redis.call('DEL', KEYS[2], KEYS[3], KEYS[4]) end
        return acked
        """, Long.class);

    public enum RecoveryResult { PUBLISHED, ALREADY_QUEUED, ATTEMPTS_EXHAUSTED, DEFERRED }

    public RecoveryResult requeuePending(UUID executionId, int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 100)
            throw new IllegalArgumentException("Pending recovery attempt limit must be between 1 and 100");
        try {
            Long result = publish(executionId, maxAttempts);
            if (Long.valueOf(1).equals(result)) return RecoveryResult.PUBLISHED;
            if (Long.valueOf(0).equals(result)) return RecoveryResult.ALREADY_QUEUED;
            if (Long.valueOf(-1).equals(result)) return RecoveryResult.ATTEMPTS_EXHAUSTED;
            log.warn("[ExecutionJobQueue] 기존 stream 검사 미완료 — 자동 재발행 보류: {}", executionId);
        } catch (Exception e) {
            log.warn("[ExecutionJobQueue] 자동 복구 발행 불명 — 다음 주기에 재확인: {}", executionId, e);
        }
        return RecoveryResult.DEFERRED;
    }

    private Long publish(UUID executionId, int maxRecoveryAttempts) {
        return redisTemplate.execute(PUBLISH_ONCE,
            java.util.List.of(STREAM_KEY, "ieum:exec:published:" + executionId,
                "ieum:exec:recovery-attempts:" + executionId, "ieum:exec:recovery-scan:" + executionId),
            executionId.toString(), Integer.toString(maxRecoveryAttempts));
    }

    public static final String STREAM_KEY = "ieum:exec:jobs";
    public static final String GROUP = "runners";
    /** 단일 인스턴스 전제(SSE 허브가 in-memory)라 컨슈머 이름을 고정한다. */
    public static final String CONSUMER = "runner";
    public static final String FIELD_EXECUTION_ID = "executionId";

    private final StringRedisTemplate redisTemplate;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>>
        executionJobListenerContainer;

    /** 소비가 정상 동작 중임을 표시한다(기동 성공 / 폴링 오류 복구 후 / 주기 회수 프로브 성공). */
    void markConsumerHealthy() {
        if (consumerHealthy.compareAndSet(false, true)) {
            log.info("[ExecutionJobQueue] 소비 정상 — 큐 경유 실행 재개");
        }
    }

    /** Local consumption health; distributed publishing is independent of local drain/health. */
    void markConsumerUnhealthy(Throwable cause) {
        if (consumerHealthy.compareAndSet(true, false)) {
            log.warn("[ExecutionJobQueue] 로컬 소비 중단 — 분산 모드는 발행/내구 복구를 유지한다: {}", cause.toString());
        }
    }

    /** @return 큐/내구 복구 경로가 실행을 맡으면 true. false는 단일 모드 직접 실행 전용이다. */
    public boolean enqueue(UUID executionId) {
        if (distributed) {
            try {
                publish(executionId, 0);
            } catch (Exception e) {
                log.warn("[ExecutionJobQueue] 발행 결과 불명 — DB PENDING 복구 주기에 재시도: {}, {}",
                    executionId, e.toString());
            }
            // The committed PENDING row is durable. True means accepted for queued recovery, not
            // necessarily that Redis confirmed XADD. Includes callers in workflow-core Quartz.
            return true;
        }
        // 소비자가 없거나 폴링이 죽어 있으면 넣어도 아무도 못 꺼낸다 — 넣지 않고 폴백시킨다.
        if (!executionJobListenerContainer.isRunning() || !consumerHealthy.get()) {
            log.warn("[ExecutionJobQueue] 소비 불가 — 큐 우회. executionId: {}", executionId);
            return false;
        }
        try {
            // MAXLEN 트리밍은 걸지 않는다 — 미배달 잡까지 잘려 실행이 PENDING으로 무로그 방치된다.
            // 처리 끝난 엔트리는 워커가 ack 직후 지우므로 스트림 길이가 곧 남은 작업량이다.
            redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(Map.of(FIELD_EXECUTION_ID, executionId.toString()))
                    .withStreamKey(STREAM_KEY));
            return true;
        } catch (Exception e) {
            log.warn("[ExecutionJobQueue] 발행 실패 — 큐 우회, 재시작 복구 불가. executionId: {}, error: {}",
                executionId, e.toString());
            return false;
        }
    }
}
