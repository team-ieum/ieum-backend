package com.ieum.api.chat.service;

import com.ieum.api.chat.dto.IntegrationInfo;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.repository.ConnectedAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자의 연동 서비스 상태를 조회하여
 * ieum-agent가 소비하는 availableIntegrations / unavailableIntegrations를 구성한다.
 *
 * <h3>현재 지원 범위 (MVP)</h3>
 * <ul>
 *   <li>Google OAuth — {@code connected_accounts} 테이블 조회</li>
 *   <li>Notion OAuth — 미구현 (항상 unavailable) TODO: AuthProvider 추가 후 구현</li>
 *   <li>Slack Webhook — 미구현 (항상 unavailable) TODO: webhook_credentials 테이블 구현 후</li>
 *   <li>Discord Webhook — 미구현 (항상 unavailable) TODO: webhook_credentials 테이블 구현 후</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationContextService {

    private final ConnectedAccountRepository connectedAccountRepository;

    /**
     * 사용자의 연동 서비스 상태를 조회한다.
     *
     * @param userId 현재 인증된 사용자 ID
     * @return available/unavailable 통합 컨텍스트
     */
    public IntegrationContext resolve(UUID userId) {
        List<IntegrationInfo> available = new ArrayList<>();
        List<IntegrationInfo> unavailable = new ArrayList<>();

        // ── Google OAuth ─────────────────────────────────────────────────────
        connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE)
            .ifPresentOrElse(
                account -> {
                    log.debug("[IntegrationContextService] Google 연동 확인 — userId: {}", userId);
                    available.add(IntegrationInfo.oauthConnected("GOOGLE", account.getScopes()));
                },
                () -> {
                    log.debug("[IntegrationContextService] Google 미연동 — userId: {}", userId);
                    unavailable.add(IntegrationInfo.oauthPending("GOOGLE"));
                }
            );

        // ── Notion OAuth (TODO: AuthProvider.NOTION 추가 후 구현) ────────────
        unavailable.add(IntegrationInfo.oauthPending("NOTION"));

        // ── Webhook 서비스 (TODO: webhook_credentials 테이블 구현 후) ─────────
        unavailable.add(IntegrationInfo.webhookPending("SLACK"));
        unavailable.add(IntegrationInfo.webhookPending("DISCORD"));

        log.debug("[IntegrationContextService] available: {}개, unavailable: {}개",
            available.size(), unavailable.size());

        return new IntegrationContext(available, unavailable);
    }

    /**
     * 연동 서비스 컨텍스트.
     *
     * @param available   연동 완료된 서비스 목록
     * @param unavailable 미연동 서비스 목록
     */
    public record IntegrationContext(
        List<IntegrationInfo> available,
        List<IntegrationInfo> unavailable
    ) {}
}
