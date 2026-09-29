package com.ieum.workflowcore.domain.enums;

public enum ExecutionStatus {
    /** 실행 대기 중 */
    PENDING,
    /** 실행 중 */
    RUNNING,
    /** 실행 성공 */
    SUCCESS,
    /** 실행 실패 */
    FAILED,
    /**
     * 승인 게이트(APPROVAL)에서 멈춤 — 소유자의 승인·거부나 기한 만료를 기다린다.
     * 런타임·워커 입장에선 종료 상태다: 승인되면 이 실행이 아니라 이어진 새 실행이 돈다.
     */
    WAITING_APPROVAL
}
