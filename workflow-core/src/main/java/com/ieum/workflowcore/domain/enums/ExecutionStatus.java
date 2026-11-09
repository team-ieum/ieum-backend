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
    WAITING_APPROVAL,
    /**
     * 기다리기(DELAY)에서 멈춤 — {@code resume_at}이 지나면 sweeper가 이어진 실행으로 재개한다.
     * 승인 대기와 같이 런타임·워커 입장에선 종료 상태다.
     */
    WAITING_DELAY;

    /** 런타임·워커가 더 돌리지 않는 상태 — 종료(SUCCESS/FAILED)와 대기(WAITING_APPROVAL/WAITING_DELAY). */
    public boolean isTerminalOrWaiting() {
        return this == SUCCESS || this == FAILED || this == WAITING_APPROVAL || this == WAITING_DELAY;
    }
}
