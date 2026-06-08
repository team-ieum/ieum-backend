package com.ieum.workflowcore.engine.event;

/** 워크플로우 실행 진행 이벤트의 종류. */
public enum ExecutionEventType {
    /** 노드 실행 시작 */
    NODE_STARTED,
    /** 노드 실행 성공 완료 */
    NODE_COMPLETED,
    /** 노드 실행 실패 */
    NODE_FAILED,
    /** 워크플로우 전체 실행 종료 (성공/실패) */
    EXECUTION_COMPLETED
}
