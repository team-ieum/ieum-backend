package com.ieum.workflowcore.domain.enums;

public enum ExecutionLogStatus {
    /** 노드 실행 성공 */
    SUCCESS,
    /** 노드 실행 실패 */
    FAILED,
    /** 노드 실행 스킵 (조건 분기에서 제외된 경로) */
    SKIPPED
}
