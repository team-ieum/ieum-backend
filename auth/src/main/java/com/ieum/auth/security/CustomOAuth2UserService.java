package com.ieum.auth.security;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.OAuthLinkToken;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.OAuthLinkTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final OAuthLinkTokenRepository oAuthLinkTokenRepository;
    private final AesEncryptor aesEncryptor;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String providerId = oAuth2User.getAttribute("sub");

        // 계정 연동(account linking) 흐름이면 현재 유저에 attach, 아니면 로그인/회원가입
        UUID linkUserId = resolveLinkUserId();
        User user = (linkUserId != null)
            ? linkToExistingUser(linkUserId, providerId)
            : loginOrRegister(email, name, providerId);

        // 연동은 항상 scope_groups 흐름(offline·consent)이라 refresh_token이 와야 한다 — 없으면 전달 배선이 끊긴 것
        if (linkUserId != null
            && !userRequest.getAdditionalParameters().containsKey(OAuth2ParameterNames.REFRESH_TOKEN)) {
            log.warn("[CustomOAuth2UserService] userId={} Google 연동인데 refresh_token 없음 — 1시간 뒤 재인증 필요", linkUserId);
        }
        saveOrUpdateConnectedAccount(user, providerId, userRequest);

        return new CustomOAuth2User(oAuth2User, user);
    }

    /**
     * 일반 Google 로그인/회원가입: Google 신원으로 유저를 조회하거나 신규 생성한다.
     */
    private User loginOrRegister(String email, String name, String providerId) {
        return userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId)
            .orElseGet(() -> {
                if (userRepository.existsByEmail(email)) {
                    throw new OAuth2AuthenticationException(ErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT.name());
                }
                return userRepository.save(
                    User.builder()
                        .email(email)
                        .name(name)
                        .provider(AuthProvider.GOOGLE)
                        .providerId(providerId)
                        .role(UserRole.ROLE_USER)
                        .build()
                );
            });
    }

    /**
     * 계정 연동: link token으로 식별된 현재 유저에 Google 계정을 연결한다.
     *
     * <p>신규 유저 생성/이메일 충돌 검사를 거치지 않으며, 유저 식별자(provider)는 변경하지 않고
     * connected_accounts에만 Google 토큰/scope를 attach한다. 동일 Google 계정(sub)이 이미 다른
     * 유저의 로그인 계정으로 존재하면 거부한다.
     */
    private User linkToExistingUser(UUID linkUserId, String providerId) {
        User current = userRepository.findById(linkUserId)
            .orElseThrow(() -> new OAuth2AuthenticationException(ErrorCode.INVALID_LINK_TOKEN.name()));

        // 동일 Google 계정(sub)이 다른 유저의 로그인 계정으로 존재하면 거부
        userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId)
            .ifPresent(existing -> {
                if (!existing.getId().equals(linkUserId)) {
                    throw new OAuth2AuthenticationException(ErrorCode.ACCOUNT_ALREADY_LINKED.name());
                }
            });

        // 동일 Google 계정(sub)이 다른 유저의 연동 계정으로 이미 연결돼 있으면 거부
        connectedAccountRepository.findByProviderAndProviderAccountId(AuthProvider.GOOGLE, providerId)
            .ifPresent(existing -> {
                if (!existing.getUserId().equals(linkUserId)) {
                    throw new OAuth2AuthenticationException(ErrorCode.ACCOUNT_ALREADY_LINKED.name());
                }
            });

        return current;
    }

    /**
     * 현재 요청의 OAuth state에서 계정 연동 link token을 해석한다.
     *
     * <p>state가 {@code link:} 프리픽스를 가지면 연동 흐름으로 보고 Redis에서 토큰을 조회·소비한다.
     * 프리픽스가 없으면 일반 로그인 흐름이므로 null을 반환한다.
     *
     * @return 연동 대상 유저 ID. 일반 로그인이면 null.
     */
    private UUID resolveLinkUserId() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        String state = attributes.getRequest().getParameter("state");
        if (state == null
            || !state.startsWith(IncrementalScopeAuthorizationRequestResolver.STATE_LINK_PREFIX)) {
            return null;
        }

        String token = state.substring(
            IncrementalScopeAuthorizationRequestResolver.STATE_LINK_PREFIX.length());
        OAuthLinkToken linkToken = oAuthLinkTokenRepository.findById(token)
            .orElseThrow(() -> new OAuth2AuthenticationException(ErrorCode.INVALID_LINK_TOKEN.name()));
        oAuthLinkTokenRepository.deleteById(token);  // 1회용 소비
        return linkToken.getUserId();
    }

    private void saveOrUpdateConnectedAccount(User user, String providerId, OAuth2UserRequest userRequest) {
        String encryptedToken = aesEncryptor.encrypt(
            userRequest.getAccessToken().getTokenValue()
        );
        Set<String> grantedScopes = userRequest.getAccessToken().getScopes();

        // RefreshTokenForwardingTokenResponseClient가 실어 보낸 값. 기본 로그인(access_type=offline 아님)이면 없다
        Object refreshToken = userRequest.getAdditionalParameters().get(OAuth2ParameterNames.REFRESH_TOKEN);
        String encryptedRefreshToken = refreshToken != null ? aesEncryptor.encrypt(refreshToken.toString()) : null;

        Instant expiresAtInstant = userRequest.getAccessToken().getExpiresAt();
        LocalDateTime tokenExpiresAt = expiresAtInstant != null
            ? LocalDateTime.ofInstant(expiresAtInstant, ZoneId.systemDefault())
            : null;

        connectedAccountRepository.findByUserIdAndProvider(user.getId(), AuthProvider.GOOGLE)
            .ifPresentOrElse(
                account -> {
                    // 토큰은 동의 흐름(scope_groups — offline·include_granted_scopes)에서만 교체한다.
                    // 기본 로그인 토큰(email·profile)으로 덮으면 만료 전까지 Google 기능이 권한 부족으로 실패한다
                    if (encryptedRefreshToken != null) {
                        account.updateTokensAndScopes(
                            encryptedToken,
                            encryptedRefreshToken,
                            tokenExpiresAt,
                            null,  // GoogleTokenService rotation과 같게 — Google은 refresh 만료 시각을 주지 않는다
                            mergeScopes(account.getScopes(), grantedScopes)
                        );
                    }
                    account.assignProviderAccountId(providerId);  // 기존 행 백필
                },
                () -> connectedAccountRepository.save(
                    ConnectedAccount.builder()
                        .userId(user.getId())
                        .provider(AuthProvider.GOOGLE)
                        .providerAccountId(providerId)
                        .accessToken(encryptedToken)
                        .refreshToken(encryptedRefreshToken)
                        .tokenExpiresAt(tokenExpiresAt)
                        .scopes(String.join(" ", grantedScopes))
                        .build()
                )
            );
    }

    /**
     * 기존 저장 scope와 신규 grant scope를 병합한다 (합집합).
     *
     * <p>응답 scope가 이전 grant를 빠뜨려도 기록이 줄지 않게 한다 — 줄면 증분 승인이
     * 불필요한 재동의를 유발한다. 일반 로그인은 이 경로를 타지 않는다(토큰 미교체).
     */
    private String mergeScopes(String existingScopes, Set<String> grantedScopes) {
        Set<String> merged = new LinkedHashSet<>();
        if (existingScopes != null && !existingScopes.isBlank()) {
            Arrays.stream(existingScopes.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(merged::add);
        }
        merged.addAll(grantedScopes);
        return String.join(" ", merged);
    }
}