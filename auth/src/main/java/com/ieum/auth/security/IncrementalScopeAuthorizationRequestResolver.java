package com.ieum.auth.security;

import com.ieum.auth.config.OAuthScopeConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.StringUtils;

/**
 * Google OAuth 증분 승인(Incremental Authorization)을 위한 AuthorizationRequestResolver.
 *
 * <p>{@code registration.google.scope}는 baseline(email, profile)만 보유하고,
 * 요청 파라미터 {@code scope_groups}(콤마 구분, 예: {@code ?scope_groups=gmail,sheets})로
 * 전달된 그룹의 scope만 동적으로 동의 화면에 추가 요청한다.
 *
 * <p>{@code scope_groups} 파라미터가 없으면 기본 동작(baseline scope만 요청)을 그대로 유지하므로
 * 일반 로그인 흐름과 호환된다.
 *
 * <h3>증분 파라미터</h3>
 * <ul>
 *   <li>{@code access_type=offline} — refresh_token 발급</li>
 *   <li>{@code include_granted_scopes=true} — 이미 승인된 scope 유지(증분)</li>
 *   <li>{@code prompt=consent} — refresh_token 재발급 보장</li>
 * </ul>
 */
@Slf4j
public class IncrementalScopeAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /** scope 그룹을 전달받는 요청 파라미터명. */
    static final String SCOPE_GROUPS_PARAM = "scope_groups";

    private final OAuth2AuthorizationRequestResolver delegate;
    private final OAuthScopeConfig oAuthScopeConfig;

    public IncrementalScopeAuthorizationRequestResolver(
        ClientRegistrationRepository clientRegistrationRepository,
        OAuthScopeConfig oAuthScopeConfig,
        String authorizationRequestBaseUri
    ) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, authorizationRequestBaseUri);
        this.oAuthScopeConfig = oAuthScopeConfig;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest customize(
        OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request) {
        if (authorizationRequest == null) {
            return null;
        }

        String scopeGroupsParam = request.getParameter(SCOPE_GROUPS_PARAM);
        if (!StringUtils.hasText(scopeGroupsParam)) {
            return authorizationRequest;
        }

        Set<String> scopes = new LinkedHashSet<>(authorizationRequest.getScopes());
        Arrays.stream(scopeGroupsParam.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .forEach(group -> {
                List<String> groupScopes = oAuthScopeConfig.getScopesByGroup(group);
                if (groupScopes.isEmpty()) {
                    log.warn("[IncrementalScopeResolver] 알 수 없는 scope 그룹 무시 — group: {}", group);
                }
                scopes.addAll(groupScopes);
            });

        Map<String, Object> additionalParameters =
            new LinkedHashMap<>(authorizationRequest.getAdditionalParameters());
        additionalParameters.put("access_type", "offline");
        additionalParameters.put("include_granted_scopes", "true");
        additionalParameters.put("prompt", "consent");

        log.debug("[IncrementalScopeResolver] 증분 scope 요청 — groups: {}, scopes: {}",
            scopeGroupsParam, scopes);

        return OAuth2AuthorizationRequest.from(authorizationRequest)
            .scopes(scopes)
            .additionalParameters(additionalParameters)
            .build();
    }
}
