package com.ieum.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ieum-agent 응답의 options 배열 항목.
 *
 * <p>{@code CLARIFICATION_NEEDED} 응답에서만 사용된다. 사용자가 고를 수 있는 선택지를 나타내며,
 * 프론트엔드는 {@code label} 버튼을 렌더하고 선택 시 {@code value}를 후속 채팅 메시지로 보낸다.
 * (예: GitHub repo 선택, Slack/Discord 웹훅 선택)
 *
 * <pre>
 * {
 *   "value": "ieum/ieum-backend",   // 선택 시 사용할 값
 *   "label": "ieum-backend",        // 사용자에게 보일 텍스트
 *   "description": null             // 부가 설명 (선택)
 * }
 * </pre>
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentOption {

    /** 선택 시 사용할 값 (예: "ieum/ieum-backend") */
    private String value;

    /** 사용자에게 보여줄 텍스트 (예: "ieum-backend") */
    private String label;

    /** 부가 설명 (선택, nullable) */
    private String description;
}
