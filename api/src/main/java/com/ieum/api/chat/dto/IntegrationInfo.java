package com.ieum.api.chat.dto;

import lombok.Getter;

/**
 * ieum-agent에 전달하는 연동 서비스 정보.
 *
 * <p>{@code availableIntegrations} — 이미 연동된 서비스 (scopes 포함)
 * <p>{@code unavailableIntegrations} — 미연동 서비스 (provider + type만)
 *
 * <pre>
 * // 연동된 경우
 * { "provider": "GOOGLE", "type": "OAUTH", "scopes": "email profile" }
 *
 * // 미연동인 경우
 * { "provider": "SLACK", "type": "WEBHOOK" }
 * </pre>
 */
@Getter
public class IntegrationInfo {

    /** 서비스 프로바이더 (예: "GOOGLE", "NOTION", "SLACK", "DISCORD") */
    private final String provider;

    /** 연동 방식 — "OAUTH" 또는 "WEBHOOK" */
    private final String type;

    /**
     * 연동된 OAuth 스코프 목록 (공백 구분).
     * availableIntegrations 항목에만 포함되며, 미연동 항목은 null.
     */
    private final String scopes;

    private IntegrationInfo(String provider, String type, String scopes) {
        this.provider = provider;
        this.type = type;
        this.scopes = scopes;
    }

    /** 연동 완료된 OAuth 서비스를 생성한다. */
    public static IntegrationInfo oauthConnected(String provider, String scopes) {
        return new IntegrationInfo(provider, "OAUTH", scopes);
    }

    /** 미연동 OAuth 서비스를 생성한다. */
    public static IntegrationInfo oauthPending(String provider) {
        return new IntegrationInfo(provider, "OAUTH", null);
    }

    /** 미연동 Webhook 서비스를 생성한다. */
    public static IntegrationInfo webhookPending(String provider) {
        return new IntegrationInfo(provider, "WEBHOOK", null);
    }

    /** 연동 완료된 Webhook 서비스를 생성한다(웹훅 자격증명 1개 이상 보유). */
    public static IntegrationInfo webhookConnected(String provider) {
        return new IntegrationInfo(provider, "WEBHOOK", null);
    }
}
