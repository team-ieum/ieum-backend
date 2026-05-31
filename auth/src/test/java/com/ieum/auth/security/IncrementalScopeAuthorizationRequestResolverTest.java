package com.ieum.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ieum.auth.config.OAuthScopeConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@ExtendWith(MockitoExtension.class)
class IncrementalScopeAuthorizationRequestResolverTest {

    private static final String BASE_URI = "/api/v1/oauth2/authorize";
    private static final String GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.modify";

    @Mock
    private OAuthScopeConfig oAuthScopeConfig;

    private IncrementalScopeAuthorizationRequestResolver resolver;

    @BeforeEach
    void setUp() {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
            .clientId("test")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/callback")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://oauth2.googleapis.com/token")
            .scope("email", "profile")
            .build();
        ClientRegistrationRepository repository = new InMemoryClientRegistrationRepository(google);
        resolver = new IncrementalScopeAuthorizationRequestResolver(repository, oAuthScopeConfig, BASE_URI);
    }

    private MockHttpServletRequest authorizeRequest(String scopeGroups) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE_URI + "/google");
        request.setServletPath(BASE_URI + "/google");
        if (scopeGroups != null) {
            request.setParameter("scope_groups", scopeGroups);
        }
        return request;
    }

    @Test
    @DisplayName("scope_groups 파라미터가 있으면 해당 그룹 scope를 baseline에 추가하고 증분 파라미터를 주입한다")
    void addsGroupScopesAndIncrementalParamsWhenScopeGroupsPresent() {
        given(oAuthScopeConfig.getScopesByGroup("gmail")).willReturn(List.of(GMAIL_SCOPE));

        OAuth2AuthorizationRequest result = resolver.resolve(authorizeRequest("gmail"));

        assertThat(result.getScopes()).containsExactlyInAnyOrder("email", "profile", GMAIL_SCOPE);
        assertThat(result.getAdditionalParameters())
            .containsEntry("access_type", "offline")
            .containsEntry("include_granted_scopes", "true")
            .containsEntry("prompt", "consent");
    }

    @Test
    @DisplayName("scope_groups 파라미터가 없으면 baseline scope만 요청하고 증분 파라미터를 추가하지 않는다")
    void keepsBaselineWhenNoScopeGroups() {
        OAuth2AuthorizationRequest result = resolver.resolve(authorizeRequest(null));

        assertThat(result.getScopes()).containsExactlyInAnyOrder("email", "profile");
        assertThat(result.getAdditionalParameters()).doesNotContainKey("access_type");
    }

    @Test
    @DisplayName("알 수 없는 scope 그룹은 무시하고 baseline scope만 유지한다")
    void ignoresUnknownScopeGroup() {
        given(oAuthScopeConfig.getScopesByGroup("unknown")).willReturn(List.of());

        OAuth2AuthorizationRequest result = resolver.resolve(authorizeRequest("unknown"));

        assertThat(result.getScopes()).containsExactlyInAnyOrder("email", "profile");
    }
}
