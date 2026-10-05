package com.ieum.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.OAuthLinkTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.util.AesEncryptor;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** refresh_token 저장 (IEUM-BE-74). 일반 로그인 흐름 — RequestContextHolder가 비어 있어 link 경로를 타지 않는다. */
@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    private static final String USERINFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;
    @Mock private ConnectedAccountRepository connectedAccountRepository;
    @Mock private OAuthLinkTokenRepository oAuthLinkTokenRepository;
    @Mock private AesEncryptor aesEncryptor;

    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CustomOAuth2UserService(
            userRepository, connectedAccountRepository, oAuthLinkTokenRepository, aesEncryptor);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer.bindTo(restTemplate).build()
            .expect(requestTo(USERINFO_URI))
            .andRespond(withSuccess(
                "{\"sub\":\"g-sub\",\"email\":\"a@b.c\",\"name\":\"A\"}", MediaType.APPLICATION_JSON));
        service.setRestOperations(restTemplate);

        User user = User.builder().id(USER_ID).email("a@b.c").name("A")
            .provider(AuthProvider.GOOGLE).providerId("g-sub").role(UserRole.ROLE_USER).build();
        given(userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "g-sub"))
            .willReturn(Optional.of(user));
        given(aesEncryptor.encrypt(anyString())).willAnswer(inv -> "enc:" + inv.getArgument(0));
    }

    private OAuth2UserRequest request(Map<String, Object> additionalParameters) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
            .clientId("id").clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/callback")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://oauth2.googleapis.com/token")
            .userInfoUri(USERINFO_URI)
            .userNameAttributeName("sub")
            .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
            "at", Instant.now(), Instant.now().plusSeconds(3599));
        return new OAuth2UserRequest(google, accessToken, additionalParameters);
    }

    private ConnectedAccount existingAccount(String refreshToken) {
        return ConnectedAccount.builder().userId(USER_ID).provider(AuthProvider.GOOGLE)
            .providerAccountId("g-sub").accessToken("enc:old").refreshToken(refreshToken).scopes("email").build();
    }

    @Test
    @DisplayName("신규 계정 — 토큰 응답의 refresh_token을 암호화해 저장한다")
    void savesRefreshTokenOnNewAccount() {
        given(connectedAccountRepository.findByUserIdAndProvider(USER_ID, AuthProvider.GOOGLE))
            .willReturn(Optional.empty());

        service.loadUser(request(Map.of(OAuth2ParameterNames.REFRESH_TOKEN, "rt")));

        ArgumentCaptor<ConnectedAccount> saved = ArgumentCaptor.forClass(ConnectedAccount.class);
        verify(connectedAccountRepository).save(saved.capture());
        assertThat(saved.getValue().getRefreshToken()).isEqualTo("enc:rt");
        assertThat(saved.getValue().getAccessToken()).isEqualTo("enc:at");
    }

    @Test
    @DisplayName("기존 계정 — 새 refresh_token으로 교체한다")
    void replacesRefreshTokenOnExistingAccount() {
        ConnectedAccount account = existingAccount("enc:old-rt");
        given(connectedAccountRepository.findByUserIdAndProvider(USER_ID, AuthProvider.GOOGLE))
            .willReturn(Optional.of(account));

        service.loadUser(request(Map.of(OAuth2ParameterNames.REFRESH_TOKEN, "rt")));

        assertThat(account.getRefreshToken()).isEqualTo("enc:rt");
        assertThat(account.getAccessToken()).isEqualTo("enc:at");
    }

    @Test
    @DisplayName("기존 계정 — 새 refresh_token이 없으면(기본 로그인) 기존 값을 유지한다")
    void keepsRefreshTokenWhenNoneIssued() {
        ConnectedAccount account = existingAccount("enc:old-rt");
        given(connectedAccountRepository.findByUserIdAndProvider(USER_ID, AuthProvider.GOOGLE))
            .willReturn(Optional.of(account));

        service.loadUser(request(Map.of()));

        assertThat(account.getRefreshToken()).isEqualTo("enc:old-rt");
        assertThat(account.getAccessToken()).isEqualTo("enc:at");
    }
}
