package com.ieum.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ieum-agent 응답의 actions 배열 항목.
 *
 * <p>{@code INTEGRATION_REQUIRED} 응답에서만 사용된다.
 *
 * <p>{@code oauthUrl}은 agent가 생성하지 않는다.
 * ieum-backend가 응답 후처리 시 직접 주입한다 (Step 5).
 *
 * <pre>
 * {
 *   "type": "OAUTH",
 *   "provider": "GOOGLE",
 *   "label": "Google 계정 연동하기",
 *   "oauthUrl": "/api/v1/oauth2/authorize/google"   ← backend 주입
 * }
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentAction {

    /** 액션 타입. 현재는 "OAUTH"만 사용. */
    private String type;

    /** 연동 대상 프로바이더 (예: "GOOGLE", "NOTION") */
    private String provider;

    /** 사용자에게 보여줄 버튼 텍스트 (예: "Google 계정 연동하기") */
    private String label;

    /**
     * OAuth 시작 URL. ieum-agent 응답에는 포함되지 않으며,
     * ieum-backend가 INTEGRATION_REQUIRED 응답 처리 시 주입한다.
     */
    private String oauthUrl;
}
