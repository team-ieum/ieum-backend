package com.ieum.workflowcore.engine.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;

/**
 * 워크플로우 실행 진행 상황을 프론트로 실시간 전달하기 위한 이벤트.
 *
 * <p>SSE data로 직렬화되며 {@code type}에 따라 사용되는 필드가 다르다.
 * null 필드는 직렬화에서 제외된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionEvent(
    ExecutionEventType type,
    String nodeId,
    NodeType nodeType,
    ExecutionLogStatus status,
    Long durationMs,
    String errorMessage,
    ExecutionStatus executionStatus
) {

    public static ExecutionEvent nodeStarted(String nodeId, NodeType nodeType) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_STARTED, nodeId, nodeType, null, null, null, null);
    }

    public static ExecutionEvent nodeCompleted(String nodeId, NodeType nodeType, long durationMs) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_COMPLETED, nodeId, nodeType,
            ExecutionLogStatus.SUCCESS, durationMs, null, null);
    }

    public static ExecutionEvent nodeFailed(
        String nodeId, NodeType nodeType, String errorMessage, long durationMs) {
        return new ExecutionEvent(
            ExecutionEventType.NODE_FAILED, nodeId, nodeType,
            ExecutionLogStatus.FAILED, durationMs, errorMessage, null);
    }

    public static ExecutionEvent executionCompleted(ExecutionStatus executionStatus) {
        return new ExecutionEvent(
            ExecutionEventType.EXECUTION_COMPLETED, null, null, null, null, null, executionStatus);
    }
}
