package com.ieum.auth.security;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
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
    private final AesEncryptionService aesEncryptionService;

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
        String encryptedToken = aesEncryptionService.encrypt(
            userRequest.getAccessToken().getTokenValue()
        );
        String scopes = String.join(" ", userRequest.getAccessToken().getScopes());

        connectedAccountRepository.findByUserIdAndProvider(user.getId(), AuthProvider.GOOGLE)
            .ifPresentOrElse(
                account -> account.updateTokenAndScopes(encryptedToken, scopes),
                () -> connectedAccountRepository.save(
                    ConnectedAccount.builder()
                        .userId(user.getId())
                        .provider(AuthProvider.GOOGLE)
                        .accessToken(encryptedToken)
                        .scopes(scopes)
                        .build()
                )
            );
    }
}