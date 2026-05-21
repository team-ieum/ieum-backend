package com.ieum.api.chat.dto;

/**
 * ieum-agent /v1/chat 응답 타입.
 *
 * <ul>
 *   <li>{@link #WORKFLOW_GENERATED} — 신규 워크플로우 생성 완료. nodes/edges 포함.</li>
 *   <li>{@link #WORKFLOW_MODIFIED} — 기존 워크플로우 수정 완료. nodes/edges + changeDescription 포함.</li>
 *   <li>{@link #INTEGRATION_REQUIRED} — 미연동 서비스 필요. actions 포함. nodes/edges null.</li>
 *   <li>{@link #CLARIFICATION_NEEDED} — 요청 불명확 또는 여러 credential 선택 필요. nodes/edges null.</li>
 * </ul>
 */
public enum AgentResponseType {
    WORKFLOW_GENERATED,
    WORKFLOW_MODIFIED,
    INTEGRATION_REQUIRED,
    CLARIFICATION_NEEDED
}
