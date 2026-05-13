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
    TRANSFORM
}
