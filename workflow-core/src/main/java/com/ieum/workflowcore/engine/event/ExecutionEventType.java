package com.ieum.workflowcore.engine.event;

/** 워크플로우 실행 진행 이벤트의 종류. */
public enum ExecutionEventType {
    /** 노드 실행 시작 */
    NODE_STARTED,
    /** 노드 실행 성공 완료 */
    NODE_COMPLETED,
    /** 노드 실행 실패 */
    NODE_FAILED,
    /**
     * 승인 게이트 대기 — {@code nodeId}의 APPROVAL 노드에서 멈췄다. 뒤이어
     * {@code EXECUTION_COMPLETED(WAITING_APPROVAL)}가 스트림을 닫는다. 이 값을 모르는 기존
     * 프론트는 이벤트를 무시한다.
     */
    APPROVAL_REQUESTED,
    /** 워크플로우 전체 실행 종료 (성공/실패) */
    EXECUTION_COMPLETED
}
