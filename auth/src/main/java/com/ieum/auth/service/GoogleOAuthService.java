package com.ieum.auth.service;

import com.ieum.auth.config.OAuthScopeConfig;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google OAuth 증분 승인(Incremental Authorization) 및 Scope 관리 서비스.
 *
 * <h3>증분 승인 흐름</h3>
 * <ol>
 *   <li>클라이언트가 {@code POST /api/v1/oauth/request-scope}로 필요한 scope 그룹을 요청</li>
 *   <li>{@link #getMissingScopes}로 이미 보유한 scope와 비교, 누락된 scope만 추출</li>
 *   <li>누락된 scope가 없으면 빈 리스트 반환 (재인증 불필요)</li>
 *   <li>누락된 scope가 있으면 {@link #getAuthorizationUrl}로 Google OAuth 재인증 URL 반환</li>
 *   <li>클라이언트가 해당 URL로 리다이렉트 → Google 동의 화면 → Spring Security 콜백 처리</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoogleOAuthService {

    /** Spring Security OAuth2 authorization endpoint base URI (SecurityConfig에서 설정). */
    private static final String OAUTH2_AUTHORIZE_BASE = "/api/v1/oauth2/authorize/google";

    private final ConnectedAccountRepository connectedAccountRepository;
    private final OAuthScopeConfig oAuthScopeConfig;

    /**
     * 사용자가 현재 보유한 Google scope 목록을 반환한다.
     *
     * @param userId 사용자 ID
     * @return scope URL 목록 (연동 안 된 경우 빈 리스트)
     */
    public List<String> getMyScopes(UUID userId) {
        return connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.GOOGLE)
            .map(account -> {
                if (account.getScopes() == null || account.getScopes().isBlank()) {
                    return Collections.<String>emptyList();
                }
                // 구분자를 공백/콤마 모두 허용 — OAuth 로그인 시 공백으로 저장되므로 유연하게 파싱
                return Arrays.stream(account.getScopes().split("[,\\s]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            })
            .orElse(Collections.emptyList());
    }

    /**
     * 요청된 scope 그룹 중 사용자가 아직 보유하지 않은 scope를 반환한다.
     *
     * <p>이미 모든 scope를 보유하면 빈 리스트를 반환한다 (재인증 불필요).
     *
     * @param userId       사용자 ID
     * @param scopeGroups  요청할 scope 그룹명 목록 (예: ["gmail", "sheets"])
     * @return 누락된 scope URL 목록
     */
    public List<String> getMissingScopes(UUID userId, List<String> scopeGroups) {
        Set<String> currentScopes = new LinkedHashSet<>(getMyScopes(userId));

        Set<String> missing = new LinkedHashSet<>();
        for (String group : scopeGroups) {
            List<String> groupScopes = oAuthScopeConfig.getScopesByGroup(group);
            if (groupScopes.isEmpty()) {
                log.warn("[GoogleOAuthService] 알 수 없는 scope 그룹 요청 — group: {}", group);
                throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 scope 그룹입니다: " + group);
            }
            groupScopes.stream()
                .filter(scope -> !currentScopes.contains(scope))
                .forEach(missing::add);
        }

        log.debug("[GoogleOAuthService] userId={} 누락 scope: {}", userId, missing);
        return List.copyOf(missing);
    }

    /**
     * Google OAuth 재인증 URL을 반환한다.
     *
     * <p>Spring Security의 authorization endpoint({@value #OAUTH2_AUTHORIZE_BASE})로
     * 리다이렉트하면 {@code CustomOAuth2UserService} → {@code OAuth2AuthenticationSuccessHandler}
     * 콜백이 처리되어 scope가 DB에 업데이트된다.
     *
     * <p>증분 승인({@code incremental-auth=true})이 활성화된 경우 이미 승인된 scope는
     * 사용자가 재동의하지 않아도 유지된다.
     *
     * @return Google OAuth 재인증 URL
     */
    public String getAuthorizationUrl() {
        log.debug("[GoogleOAuthService] authorization URL 생성 — incrementalAuth={}",
            oAuthScopeConfig.isIncrementalAuth());
        return OAUTH2_AUTHORIZE_BASE;
    }

    /**
     * 지원하는 모든 scope 그룹과 각 그룹의 scope URL 목록을 반환한다.
     *
     * @return 그룹명 → scope URL 목록 맵
     */
    public Map<String, List<String>> getAvailableScopes() {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        oAuthScopeConfig.getScopeGroups().forEach(group ->
            result.put(group, oAuthScopeConfig.getScopesByGroup(group))
        );
        return Collections.unmodifiableMap(result);
    }
}
