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
 *   "changeDescription": "...",   // WORKFLOW_MODIFIED 시에만 채워짐
 *   "usage": {                    // 모델이 토큰을 보고하지 않으면 null
 *     "promptTokens": 1200, "completionTokens": 340, "totalTokens": 1540
 *   }
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
     * 사용자가 고를 수 있는 선택지 목록 (예: GitHub repo, 웹훅).
     * type이 CLARIFICATION_NEEDED일 때 채워진다.
     */
    private List<AgentOption> options;

    /**
     * 워크플로우 수정 내용 한 줄 요약.
     * type이 WORKFLOW_MODIFIED일 때만 사용된다.
     */
    private String changeDescription;

    /**
     * AI가 제안하는 워크플로우 이름.
     * type이 WORKFLOW_GENERATED일 때만 채워지며, 수정 시에는 null이다.
     */
    private String workflowName;

    /**
     * LLM 토큰 사용량. agent의 designer·reviewer·재생성 루프를 모두 합산한 값이다(IEUM-AI-48).
     * 모델이 토큰을 보고하지 않으면 null이며, 그 경우 베타 토큰 차감은 건너뛴다.
     */
    private Usage usage;

    /** agent 응답의 usage 필드. {@code /v1/execute}의 동명 구조와 같은 형태다. */
    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }

    /** 편의 메서드 — message 필드를 반환한다 */
    public String getContent() {
        return message;
    }

    /** 입력 토큰 수. usage가 없으면 null (차감 스킵 신호) */
    public Integer getInputTokens() {
        return usage != null ? usage.getPromptTokens() : null;
    }

    /** 출력 토큰 수. usage가 없으면 null (차감 스킵 신호) */
    public Integer getOutputTokens() {
        return usage != null ? usage.getCompletionTokens() : null;
    }

    /** 워크플로우 생성 또는 수정 응답인지 확인 */
    public boolean isWorkflowResult() {
        return type == AgentResponseType.WORKFLOW_GENERATED
            || type == AgentResponseType.WORKFLOW_MODIFIED;
    }
}
