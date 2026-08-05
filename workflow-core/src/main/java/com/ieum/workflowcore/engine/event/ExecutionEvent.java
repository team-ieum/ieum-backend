package com.ieum.workflowcore.engine.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.time.Instant;
import java.util.UUID;

/**
 * 워크플로우 실행 진행 상황을 프론트로 실시간 전달하기 위한 이벤트.
 *
 * <p>SSE data로 직렬화되며 {@code type}에 따라 사용되는 필드가 다르다.
 * null 필드는 직렬화에서 제외된다.
 *
 * <p>{@code type}은 프론트 실행 화면이 이미 읽고 있는 키다 — 이름을 바꾸면 깨진다.
 *
 * @param executionId 이 이벤트가 속한 실행 ID. 모든 이벤트에 실린다
 * @param workflowId  실행 대상 워크플로우 ID. 모든 이벤트에 실린다
 * @param occurredAt  이벤트 발생 시각. ISO-8601 문자열로 직렬화된다. 정적 팩토리는 호출 시각을
 *                    쓰므로, DB 로그에서 과거 이벤트를 복원하는 경우엔 {@link #withOccurredAt}로
 *                    실제 발생 시각을 덮어써야 한다
 * @param status      노드 이벤트의 상태. {@code EXECUTION_COMPLETED}에는 실리지 않는다
 * @param executionStatus 실행 전체의 종료 상태. {@code EXECUTION_COMPLETED}에만 실린다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionEvent(
    ExecutionEventType type,
    UUID executionId,
    UUID workflowId,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    String nodeId,
    NodeType nodeType,
    NodeEventStatus status,
    Long durationMs,
    String errorMessage,
    ExecutionStatus executionStatus
) {

    public static ExecutionEvent nodeStarted(
        UUID executionId, UUID workflowId, String nodeId, NodeType nodeType) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_STARTED, executionId, workflowId, Instant.now(),
            nodeId, nodeType, NodeEventStatus.RUNNING, null, null, null);
    }

    public static ExecutionEvent nodeCompleted(
        UUID executionId, UUID workflowId, String nodeId, NodeType nodeType, long durationMs) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_COMPLETED, executionId, workflowId, Instant.now(),
            nodeId, nodeType, NodeEventStatus.SUCCESS, durationMs, null, null);
    }

    /**
     * 실행하지 않고 건너뛴 노드. 죽은 조건 분기로 가지치기된 노드와, 재처리에서 원 실행의 출력을
     * 재사용해 executor를 부르지 않은 노드가 여기 해당한다({@code node_runs.status = SKIPPED}).
     *
     * <p><b>{@code type}은 {@code NODE_COMPLETED}이고 {@code status}만 {@code SKIPPED}다.</b>
     * 새 {@code type} 값을 만들지 않은 이유: 프론트는 {@code nodeId + type}으로 이벤트를 멱등
     * 처리하는데, 이미 배포된 프론트에 모르는 {@code type}을 주면 그 노드는 종료 표시를 받지 못한
     * 채 남는다(재처리 스킵 노드는 {@code NODE_STARTED}를 이미 받은 뒤라 "실행 중"으로 굳는다).
     * 아는 {@code type}을 주고 {@code status}로 구분시키면, {@code status}를 읽지 않는 프론트에서
     * 최악이라도 "스킵을 완료로 표시" — 즉 이 변경 이전 동작까지만 후퇴한다.
     */
    public static ExecutionEvent nodeSkipped(
        UUID executionId, UUID workflowId, String nodeId, NodeType nodeType, long durationMs) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_COMPLETED, executionId, workflowId, Instant.now(),
            nodeId, nodeType, NodeEventStatus.SKIPPED, durationMs, null, null);
    }

    public static ExecutionEvent nodeFailed(
        UUID executionId, UUID workflowId, String nodeId, NodeType nodeType,
        String errorMessage, long durationMs) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_FAILED, executionId, workflowId, Instant.now(),
            nodeId, nodeType, NodeEventStatus.FAILED, durationMs, errorMessage, null);
    }

    public static ExecutionEvent executionCompleted(
        UUID executionId, UUID workflowId, ExecutionStatus executionStatus) {
        return new ExecutionEvent(
            ExecutionEventType.EXECUTION_COMPLETED, executionId, workflowId, Instant.now(),
            null, null, null, null, null, executionStatus);
    }

    /**
     * 발생 시각만 교체한 복사본을 돌려준다.
     *
     * <p>DB 로그를 이벤트로 되돌리는 스냅샷 재생 경로용이다 — 그대로 두면 과거 이벤트가
     * 전부 "지금" 발생한 것으로 나간다.
     */
    public ExecutionEvent withOccurredAt(Instant occurredAt) {
        return new ExecutionEvent(type, executionId, workflowId, occurredAt,
            nodeId, nodeType, status, durationMs, errorMessage, executionStatus);
    }
}
