package com.ieum.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ieum-agent POST /v1/chat 응답 body.
 *
 * <pre>
 * {
 *   "message": "LLM 자연어 응답 텍스트",
 *   "type": "WORKFLOW_GENERATED | WORKFLOW_MODIFIED | INTEGRATION_REQUIRED | CLARIFICATION_NEEDED",
 *   "nodes": [...],               // WORKFLOW_GENERATED/MODIFIED 시에만 채워짐
 *   "edges": [...],               // WORKFLOW_GENERATED/MODIFIED 시에만 채워짐
 *   "actions": [...],             // INTEGRATION_REQUIRED 시에만 채워짐
 *   "changeDescription": "..."    // WORKFLOW_MODIFIED 시에만 채워짐
 * }
 * </pre>
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatAgentResponse {

    /** AI 자연어 응답 텍스트 */
    private String message;

    /** 응답 타입 */
    private AgentResponseType type;

    /**
     * 생성/수정된 워크플로우 노드 목록.
     * type이 WORKFLOW_GENERATED 또는 WORKFLOW_MODIFIED일 때 채워진다.
     */
    private List<Object> nodes;

    /**
     * 생성/수정된 워크플로우 엣지 목록.
     * type이 WORKFLOW_GENERATED 또는 WORKFLOW_MODIFIED일 때 채워진다.
     */
    private List<Object> edges;

    /**
     * 프론트엔드 실행 가능한 액션 목록 (예: OAuth 연동 버튼).
     * type이 INTEGRATION_REQUIRED일 때 채워진다.
     * oauthUrl은 ieum-backend가 후처리 시 주입한다.
     */
    private List<AgentAction> actions;

    /**
     * 워크플로우 수정 내용 한 줄 요약.
     * type이 WORKFLOW_MODIFIED일 때만 사용된다.
     */
    private String changeDescription;

    /** 편의 메서드 — message 필드를 반환한다 */
    public String getContent() {
        return message;
    }

    /** /v1/chat 응답에 토큰 정보가 없으므로 null 반환 */
    public Integer getInputTokens() {
        return null;
    }

    /** /v1/chat 응답에 토큰 정보가 없으므로 null 반환 */
    public Integer getOutputTokens() {
        return null;
    }

    /** 워크플로우 생성 또는 수정 응답인지 확인 */
    public boolean isWorkflowResult() {
        return type == AgentResponseType.WORKFLOW_GENERATED
            || type == AgentResponseType.WORKFLOW_MODIFIED;
    }
}
