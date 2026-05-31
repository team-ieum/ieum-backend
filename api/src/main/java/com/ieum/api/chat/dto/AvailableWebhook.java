package com.ieum.api.chat.dto;

/**
 * ieum-agent {@code POST /v1/chat} 요청의 {@code availableWebhooks} 항목.
 *
 * <p>사용자가 보유한 Slack/Discord 송신 웹훅 자격증명 메타데이터로, 생성/수정 단계에서 Designer가
 * 적절한 AI 노드에 slack/discord 도구를 배정하고 webhookCredentialId를 노드 config에 넣는 데 사용한다.
 * webhook URL 등 민감 정보는 제외하고 매칭·식별에 필요한 정보(webhookCredentialId/provider/displayName)만 전달한다.
 */
public record AvailableWebhook(
    String webhookCredentialId,
    String provider,
    String displayName
) {}
