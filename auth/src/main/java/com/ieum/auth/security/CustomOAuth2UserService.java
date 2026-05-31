package com.ieum.auth.security;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final AesEncryptor aesEncryptor;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String providerId = oAuth2User.getAttribute("sub");

        User user = userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId)
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

        saveOrUpdateConnectedAccount(user, userRequest);

        return new CustomOAuth2User(oAuth2User, user);
    }

    private void saveOrUpdateConnectedAccount(User user, OAuth2UserRequest userRequest) {
        String encryptedToken = aesEncryptor.encrypt(
            userRequest.getAccessToken().getTokenValue()
        );
        Set<String> grantedScopes = userRequest.getAccessToken().getScopes();

        Instant expiresAtInstant = userRequest.getAccessToken().getExpiresAt();
        LocalDateTime tokenExpiresAt = expiresAtInstant != null
            ? LocalDateTime.ofInstant(expiresAtInstant, ZoneId.systemDefault())
            : null;

        connectedAccountRepository.findByUserIdAndProvider(user.getId(), AuthProvider.GOOGLE)
            .ifPresentOrElse(
                // baseline 로그인 토큰은 기존 grant를 포함하지 않으므로 병합해 scope 축소 방지
                account -> account.updateTokensAndScopes(
                    encryptedToken,
                    account.getRefreshToken(),
                    tokenExpiresAt,
                    account.getRefreshTokenExpiresAt(),
                    mergeScopes(account.getScopes(), grantedScopes)
                ),
                () -> connectedAccountRepository.save(
                    ConnectedAccount.builder()
                        .userId(user.getId())
                        .provider(AuthProvider.GOOGLE)
                        .accessToken(encryptedToken)
                        .tokenExpiresAt(tokenExpiresAt)
                        .scopes(String.join(" ", grantedScopes))
                        .build()
                )
            );
    }

    /**
     * 기존 저장 scope와 신규 grant scope를 병합한다 (합집합).
     *
     * <p>일반 로그인은 baseline scope만 발급받으므로, 병합 없이 덮어쓰면 이전에 승인한
     * scope 기록이 사라져 증분 승인이 불필요한 재동의를 유발한다.
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