package com.ieum.workflowcore.domain.enums;

public enum NodeType {
    /** 워크플로우 시작 노드 */
    TRIGGER,
    /** AI 처리 노드 */
    AI,
    /** 조건 분기 노드 */
    CONDITION,
    /** 외부 HTTP 호출 노드 */
    HTTP,
    /** 데이터 변환 노드 */
    TRANSFORM,
    /**
     * 사람 승인 게이트. NodeExecutor가 없다 — 런타임이 이 노드에서 실행을 멈추고(WAITING_APPROVAL),
     * 승인되면 이어진 실행에서 사전 완료 출력으로 통과한다.
     */
    APPROVAL
}
