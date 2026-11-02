package com.ieum.api.workflow.queue;

import static com.ieum.api.workflow.queue.ExecutionJobQueue.FIELD_EXECUTION_ID;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.GROUP;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.STREAM_KEY;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * 잡 큐에서 꺼낸 executionId로 워크플로우를 실행한다(소비 전담).
 *
 * <p>분산 모드에서는 Redis 이벤트 전달과 PostgreSQL 실행 잠금으로 여러 워커를 지원한다.
 * <p><b>분산 모드가 꺼져 있으면 워커는 API와 같은 프로세스에서 돈다.</b>
 * {@code ExecutionEventPublisher}가 executionId 키의 in-memory {@code Sinks.Many} 허브라,
 * SSE를 구독한 HTTP 스레드와 실행 스레드가 같은 JVM에 있어야만 진행 이벤트가 전달된다.
 * 이 큐의 목적은 수평 확장이 아니라 <b>내구성(프로세스 재시작 복구)</b>이다.
 *
 * <p>잡 페이로드에는 executionId만 있다. 트리거 입력은 DB에서 읽어
 * {@link WorkflowExecutionService#decryptTriggerData}로 복호한다 — Redis에 평문이 남지 않는다.
 *
 * <p><b>배달 보장은 at-least-once이지 exactly-once가 아니다.</b> 프로세스가 실행 도중 죽으면 run은
 * {@code RUNNING}으로 남고 잡은 pending에 남아 회수되어 <b>처음부터 다시</b> 실행된다 — 이미
 * 부작용을 낸 노드(Slack 발송·Notion 쓰기·LLM 과금)까지 되풀이된다. 중복 가드는 종료 상태
 * ({@code SUCCESS}/{@code FAILED})와 이 프로세스의 대기·진행 작업을 확인한다.
 * 분산 모드에서는 공유 실행 진입점의 PostgreSQL lease도 확인한다. 이미 전송된 외부 요청은
 * 취소할 수 없으므로 lease 확인이 외부 부수 효과의 exactly-once를 보장하지는 않는다.
 * 재처리 API 등 이 큐 위에 무언가를 얹을 때 exactly-once로 오해하지 말 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionJobWorker implements StreamListener<String, MapRecord<String, String, String>> {

    /** 실행 풀 포화 시 제출 재시도 횟수·간격. 다 소진하면 폴링 스레드에서 인라인 실행한다. */
    private static final int SUBMIT_ATTEMPTS = 3;
    private static final Duration SUBMIT_RETRY_DELAY = Duration.ofSeconds(1);

    /** 이 프로세스가 지금 돌리고 있는 실행. 회수분이 중복 실행되는 걸 막는다. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<RecordId> inFlightRecords = ConcurrentHashMap.newKeySet();
    private final Object admission = new Object();
    private boolean accepting = true;

    public void pauseConsuming() { synchronized (admission) { accepting = false; } }
    public void resumeConsuming() { synchronized (admission) { accepting = true; } }

    /** Includes tasks waiting for the executor and inline execution on the polling thread. */
    public int activeCount() { return inFlight.size(); }
    public boolean isInFlight(RecordId id) { return inFlightRecords.contains(id); }

    private final StringRedisTemplate redisTemplate;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    /** 기존 {@code @Async} 실행과 같은 풀 — 큐 도입 전후로 동시 실행 수가 달라지지 않는다. */
    private final Executor workflowExecutor;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        UUID executionId = parseExecutionId(record);
        if (executionId == null) {
            // 해석 불가 페이로드는 재배달해도 영원히 실패한다 — ack로 버린다.
            acknowledge(record);
            return;
        }
        // Reserve before submitting: queued tasks also protect pending delivery from self-reclaim.
        synchronized (admission) {
            // A cancelled StreamPollTask can still deliver its last XREADGROUP batch. Leaving it
            // pending lets the active peer reclaim it; drained heartbeat owners are reclaimable.
            if (!accepting || !inFlight.add(executionId)) return;
            inFlightRecords.add(record.getId());
        }

        // 폴링 스레드에서 직접 실행하면 이 스트림의 소비 전체가 그동안 멈춘다(StreamPollTask는
        // 구독당 스레드 하나로 순차 처리). 풀이 포화면 잠깐 기다렸다 다시 제출해 본다.
        for (int attempt = 1; attempt <= SUBMIT_ATTEMPTS; attempt++) {
            try {
                workflowExecutor.execute(() -> handle(executionId, record));
                return;
            } catch (RejectedExecutionException e) {
                sleepQuietly(SUBMIT_RETRY_DELAY);
            } catch (RuntimeException e) {
                inFlight.remove(executionId);
                inFlightRecords.remove(record.getId());
                throw e;
            }
        }

        // 최후수단. 잡을 잃지 않는 쪽을 택한 것이지 백프레셔가 아니다 — 생산자는 이 정체를 알지 못하고
        // 계속 발행에 성공한다. 이 실행이 끝날 때까지 잡 소비가 멈춘다.
        log.warn("[ExecutionJobWorker] 실행 풀이 계속 포화 — 폴링 스레드에서 직접 실행한다. "
            + "이 실행이 끝날 때까지 잡 소비가 정지된다. executionId: {}", executionId);
        handle(executionId, record);
    }

    private void handle(UUID executionId, MapRecord<String, String, String> record) {
        boolean completed = false;
        try {
            Optional<WorkflowExecution> found =
                workflowExecutionRepository.findWithVersionById(executionId);
            if (found.isEmpty()) {
                log.warn("[ExecutionJobWorker] 실행 레코드 없음 — 잡 폐기. executionId: {}", executionId);
                completed = true;
                return;
            }

            WorkflowExecution execution = found.get();
            if (isTerminal(execution.getStatus())) {
                // 재시작 회수분이 이미 끝난 실행일 수 있다. 재실행하면 중복 실행이 된다.
                log.info("[ExecutionJobWorker] 이미 종료된 실행 — 재실행 없이 ack. executionId: {}, status: {}",
                    executionId, execution.getStatus());
                completed = true;
                return;
            }

            completed = workflowExecutionRunner.executeQueued(execution, executionId);
        } catch (Exception e) {
            // Database/network lookup failures are retryable infrastructure failures.
            // Decryption/runtime failures are recorded by the runner while holding its lease.
            log.error("[ExecutionJobWorker] 잡 조회/처리 실패 — pending 유지: {}", executionId, e);
        } finally {
            inFlight.remove(executionId);
            inFlightRecords.remove(record.getId());
            // ack는 반드시 실행이 종료 상태로 확정된 뒤다. 앞당기면 프로세스가 죽을 때
            // pending에 남지 않아 재시작 복구 대상에서 빠진다.
            if (completed) acknowledge(record);
        }
    }

    /**
     * 실행하지 않고 ack할 상태 — 종료(SUCCESS/FAILED)와 승인 대기(WAITING_APPROVAL).
     * 대기 실행은 승인되면 원 실행이 아니라 이어진 새 실행이 돈다. 재배달된 대기 실행을 돌리면
     * 게이트 앞 노드(부작용 포함)가 처음부터 다시 실행된다.
     */
    private boolean isTerminal(ExecutionStatus status) {
        return status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED
            || status == ExecutionStatus.WAITING_APPROVAL;
    }

    private UUID parseExecutionId(MapRecord<String, String, String> record) {
        String raw = record.getValue().get(FIELD_EXECUTION_ID);
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            log.error("[ExecutionJobWorker] 잡 페이로드 해석 실패 — recordId: {}, value: {}",
                record.getId(), record.getValue());
            return null;
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        try {
            String identity;
            try { identity = UUID.fromString(record.getValue().get(FIELD_EXECUTION_ID)).toString(); }
            catch (Exception malformed) { identity = record.getId().getValue(); }
            // The server completes ACK, entry deletion and index cleanup as one operation, even if
            // the connection drops after accepting the script. Never delete a newer entry's index.
            redisTemplate.execute(ExecutionJobQueue.ACKNOWLEDGE_AND_DELETE,
                java.util.List.of(STREAM_KEY, "ieum:exec:published:" + identity,
                    "ieum:exec:recovery-attempts:" + identity, "ieum:exec:recovery-scan:" + identity),
                GROUP, record.getId().getValue());
        } catch (Exception e) {
            // An unknown response does not change a terminal execution. If Redis did not run the
            // script, pending recovery can run it again without repeating terminal workflow effects.
            log.warn("[ExecutionJobWorker] 원자적 ack/정리 결과 불명 — recordId: {}, error: {}",
                record.getId(), e.toString());
        }
    }
}
