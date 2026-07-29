package com.ieum.api.workflow.queue;

import static com.ieum.api.workflow.queue.ExecutionJobQueue.FIELD_EXECUTION_ID;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.GROUP;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.STREAM_KEY;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * 잡 큐에서 꺼낸 executionId로 워크플로우를 실행한다(소비 전담).
 *
 * <p><b>워커는 API와 같은 프로세스에서 돈다 — 별도 프로세스로 빼지 말 것.</b>
 * {@code ExecutionEventPublisher}가 executionId 키의 in-memory {@code Sinks.Many} 허브라,
 * SSE를 구독한 HTTP 스레드와 실행 스레드가 같은 JVM에 있어야만 진행 이벤트가 전달된다.
 * 이 큐의 목적은 수평 확장이 아니라 <b>내구성(프로세스 재시작 복구)</b>이다.
 *
 * <p>잡 페이로드에는 executionId만 있다. 트리거 입력은 DB에서 읽어
 * {@link WorkflowExecutionService#decryptTriggerData}로 복호한다 — Redis에 평문이 남지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionJobWorker implements StreamListener<String, MapRecord<String, String, String>> {

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

        try {
            // 폴링 스레드에서 직접 실행하면 실행 하나가 끝날 때까지 다음 잡을 못 꺼낸다.
            workflowExecutor.execute(() -> handle(executionId, record));
        } catch (Exception e) {
            // 풀 포화(TaskRejected) — 폴링 스레드에서 직접 처리해 자연스러운 백프레셔를 만든다.
            log.warn("[ExecutionJobWorker] 실행 풀 포화 — 폴링 스레드에서 직접 실행. executionId: {}",
                executionId);
            handle(executionId, record);
        }
    }

    private void handle(UUID executionId, MapRecord<String, String, String> record) {
        try {
            Optional<WorkflowExecution> found =
                workflowExecutionRepository.findWithVersionById(executionId);
            if (found.isEmpty()) {
                log.warn("[ExecutionJobWorker] 실행 레코드 없음 — 잡 폐기. executionId: {}", executionId);
                return;
            }

            WorkflowExecution execution = found.get();
            if (isTerminal(execution.getStatus())) {
                // 재시작 회수분이 이미 끝난 실행일 수 있다. 재실행하면 중복 실행이 된다.
                log.info("[ExecutionJobWorker] 이미 종료된 실행 — 재실행 없이 ack. executionId: {}, status: {}",
                    executionId, execution.getStatus());
                return;
            }

            workflowExecutionRunner.executeNow(
                execution.getWorkflowVersion(), executionId,
                workflowExecutionService.decryptTriggerData(execution));
        } catch (Exception e) {
            // executeNow는 자체적으로 실패를 기록한다. 여기 오는 건 조회·복호 실패다.
            log.error("[ExecutionJobWorker] 잡 처리 실패 — executionId: {}", executionId, e);
            workflowExecutionService.markAsFailed(executionId);
        } finally {
            // ack는 반드시 실행이 종료 상태로 확정된 뒤다. 앞당기면 프로세스가 죽을 때
            // pending에 남지 않아 재시작 복구 대상에서 빠진다.
            acknowledge(record);
        }
    }

    private boolean isTerminal(ExecutionStatus status) {
        return status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED;
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

    private void acknowledge(MapRecord<String, String, String> record) {
        try {
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
        } catch (Exception e) {
            // ack 실패는 실행 결과를 뒤집지 않는다. 재배달돼도 종료 상태 검사에서 걸러진다.
            log.warn("[ExecutionJobWorker] ack 실패 — recordId: {}, error: {}",
                record.getId(), e.toString());
        }
    }
}
