package com.ieum.api.chat.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * ieum-agent POST /v1/chat 요청 body.
 */
@Getter
@Builder
public class ChatAgentRequest {

    /** 대화 히스토리 (최신 사용자 메시지 포함) */
    private final List<AgentMessage> messages;

    /** 사용할 모델 이름 (nullable — agent 기본값 사용) */
    private final String model;

    /** 스트리밍 응답 여부 */
    @Builder.Default
    private final boolean stream = false;
}
