package com.ieum.workflowcore.domain.enums;

public enum ExecutionStatus {
    /** 실행 대기 중 */
    PENDING,
    /** 실행 중 */
    RUNNING,
    /** 실행 성공 */
    SUCCESS,
    COMPLETED,
    /** 실행 실패 */
    FAILED
}
