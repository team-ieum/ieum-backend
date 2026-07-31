package com.ieum.workflowcore.engine.executor;

import java.util.Optional;
import java.util.UUID;

/**
 * 사용자별 Slack/Discord 송신 웹훅 자격증명을 조회하는 인터페이스.
 *
 * <p>workflow-core는 auth/api 모듈에 의존하지 않으므로 포트만 정의하고,
 * 실제 구현체는 api 모듈의 DefaultWebhookCredentialProvider에 위치한다.
 */
public interface WebhookCredentialProvider {

    /**
     * webhookCredentialId에 해당하는 복호화된 Incoming Webhook URL을 조회한다.
     *
     * <p>해당 사용자 소유가 아니거나 존재하지 않거나 비활성인 경우 빈 Optional을 반환한다.
     *
     * @param credentialId 노드 config에서 수집한 웹훅 자격증명 ID
     * @param userId       실행 주체 사용자 ID
     * @return 복호화된 webhook URL, 또는 빈 Optional
     */
    Optional<String> resolveWebhookUrl(UUID credentialId, UUID userId);

    /**
     * 실행 실패 알림을 받을 웹훅 URL을 조회한다(복호화된 값).
     *
     * <p>해당 사용자가 알림 대상으로 지정한 활성 DISCORD 웹훅 하나를 돌려준다.
     * 지정이 없으면 빈 Optional — 소유자 알림을 생략한다는 뜻이다.
     *
     * @param userId 워크플로우 소유자 ID
     */
    Optional<String> resolveAlertWebhookUrl(UUID userId);
}
