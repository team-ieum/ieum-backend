package com.ieum.auth.config;

import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Google OAuth scope 설정 일관성 검증기.
 *
 * <p>두 설정은 역할이 다르지만 같은 scope 집합을 중복 관리하므로 drift가 발생할 수 있다.
 * <ul>
 *   <li>{@code spring.security.oauth2.client.registration.google.scope}
 *       — authorization endpoint가 Google에 실제 요청하는 동의 scope</li>
 *   <li>{@code google.oauth.scopes} ({@link OAuthScopeConfig})
 *       — 증분 승인의 missing/available scope 계산용 그룹 정의</li>
 * </ul>
 *
 * <p>{@code google.oauth.scopes}의 그룹 scope가 registration scope에 포함되지 않으면,
 * 증분 승인 흐름이 "누락 scope"로 계산하더라도 authorization 리다이렉트가 해당 scope를
 * 영영 요청하지 않아 무한 재인증 루프에 빠진다. 이를 기동 시점에 fail-fast로 차단한다.
 */
@Slf4j
@Component
public class OAuthScopeConsistencyValidator {

    private static final String GOOGLE_REGISTRATION_ID = "google";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuthScopeConfig oAuthScopeConfig;

    public OAuthScopeConsistencyValidator(
        @Nullable ClientRegistrationRepository clientRegistrationRepository,
        OAuthScopeConfig oAuthScopeConfig
    ) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.oAuthScopeConfig = oAuthScopeConfig;
    }

    @PostConstruct
    void validate() {
        List<String> requiredScopes = oAuthScopeConfig.getAllScopes();
        if (requiredScopes.isEmpty()) {
            return;
        }

        if (clientRegistrationRepository == null) {
            log.warn("[OAuthScopeConsistencyValidator] ClientRegistrationRepository 빈 없음 — 검증 생략");
            return;
        }

        ClientRegistration google =
            clientRegistrationRepository.findByRegistrationId(GOOGLE_REGISTRATION_ID);
        if (google == null) {
            log.warn("[OAuthScopeConsistencyValidator] '{}' ClientRegistration 없음 — 검증 생략",
                GOOGLE_REGISTRATION_ID);
            return;
        }

        Set<String> registrationScopes = google.getScopes();
        List<String> missing = requiredScopes.stream()
            .filter(scope -> !registrationScopes.contains(scope))
            .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "google.oauth.scopes에 정의됐으나 "
                    + "spring.security.oauth2.client.registration.google.scope에 누락된 scope: "
                    + missing
                    + ". 증분 승인 흐름이 깨지므로 두 설정을 동기화해야 합니다.");
        }

        log.info("[OAuthScopeConsistencyValidator] Google OAuth scope 일관성 검증 통과 — {}개 scope",
            requiredScopes.size());
    }
}
