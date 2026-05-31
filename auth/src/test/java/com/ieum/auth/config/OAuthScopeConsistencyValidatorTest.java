package com.ieum.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@ExtendWith(MockitoExtension.class)
class OAuthScopeConsistencyValidatorTest {

    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;

    @Mock
    private OAuthScopeConfig oAuthScopeConfig;

    @InjectMocks
    private OAuthScopeConsistencyValidator validator;

    private ClientRegistration googleRegistration(String... scopes) {
        return ClientRegistration.withRegistrationId("google")
            .clientId("test")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/callback")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://oauth2.googleapis.com/token")
            .scope(scopes)
            .build();
    }

    @Test
    @DisplayName("registration scope가 google.oauth.scopes를 모두 포함하면 통과한다")
    void passesWhenRegistrationContainsAllScopes() {
        given(oAuthScopeConfig.getAllScopes()).willReturn(
            List.of("https://www.googleapis.com/auth/gmail.modify",
                "https://www.googleapis.com/auth/spreadsheets"));
        given(clientRegistrationRepository.findByRegistrationId("google")).willReturn(
            googleRegistration("email", "profile",
                "https://www.googleapis.com/auth/gmail.modify",
                "https://www.googleapis.com/auth/spreadsheets"));

        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("registration scope에 누락된 그룹 scope가 있으면 예외를 던진다")
    void throwsWhenRegistrationMissesGroupScope() {
        given(oAuthScopeConfig.getAllScopes()).willReturn(
            List.of("https://www.googleapis.com/auth/gmail.modify",
                "https://www.googleapis.com/auth/drive"));
        given(clientRegistrationRepository.findByRegistrationId("google")).willReturn(
            googleRegistration("email", "profile",
                "https://www.googleapis.com/auth/gmail.modify"));

        assertThatThrownBy(() -> validator.validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("https://www.googleapis.com/auth/drive");
    }

    @Test
    @DisplayName("google.oauth.scopes가 비어 있으면 검증을 생략한다")
    void skipsWhenNoScopesConfigured() {
        given(oAuthScopeConfig.getAllScopes()).willReturn(List.of());

        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }
}
