package com.ieum.api.chat.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * ieum-agent POST /v1/chat/stream 요청 body.
 *
 * <p>ieum-backend가 FE 요청의 currentNodes를 NodeDerivation으로 파생한 결과(type·brand를 덮고 옛 키를 지움)와
 * currentEdges를 전달한다(DB에서 다시 읽지 않는다). 연동·MCP·웹훅은 agent가 위임 토큰으로 BE에 직접 묻는다.
 */
@Getter
@Builder
public class ChatAgentRequest {

    /**
     * 대상 워크플로우 ID.
     * agent는 이 값으로 멀티턴 대화 세션을 키링(user_id + workflow_id)하여 맥락을 유지한다.
     */
    private final String workflowId;

    /** 사용자 자연어 입력 */
    private final String prompt;

    /**
     * 현재 워크플로우 노드 목록.
     * null이면 신규 생성, non-null이면 수정 컨텍스트.
     */
    private final List<Object> currentNodes;

    /** 현재 워크플로우 엣지 목록 */
    private final List<Object> currentEdges;
}
