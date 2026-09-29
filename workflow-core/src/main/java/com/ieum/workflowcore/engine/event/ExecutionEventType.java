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
     * 승인 게이트 대기 — {@code nodeId}의 APPROVAL 노드에서 멈췄다. 보통 뒤이어
     * {@code EXECUTION_COMPLETED(WAITING_APPROVAL)}가 스트림을 닫지만, 다른 분기가 실패하면
     * {@code EXECUTION_COMPLETED(FAILED)}로 닫힌다 — 게이트를 대기로 다룰 건 종료 이벤트가
     * {@code WAITING_APPROVAL}일 때뿐이다. 이 값을 모르는 기존 프론트는 이벤트를 무시한다.
     */
    APPROVAL_REQUESTED,
    /** 워크플로우 전체 실행 종료 (성공/실패/승인 대기) */
    EXECUTION_COMPLETED
}
