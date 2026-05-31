package com.ieum.auth.service;

import com.ieum.auth.config.OAuthScopeConfig;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.OAuthLinkToken;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.OAuthLinkTokenRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private OAuthScopeConfig oAuthScopeConfig;

    @Mock
    private OAuthLinkTokenRepository oAuthLinkTokenRepository;

    @InjectMocks
    private GoogleOAuthService googleOAuthService;

    @Captor
    private ArgumentCaptor<OAuthLinkToken> linkTokenCaptor;

    private final UUID userId = UUID.randomUUID();

    private ConnectedAccount buildAccount(String scopes) {
        return ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.GOOGLE)
            .accessToken("enc-access-token")
            .scopes(scopes)
            .build();
    }

    // ── getMyScopes ──────────────────────────────────────────────────────────

    @Test
    void getMyScopes_connectedWithScopes_returnsScopeList() {
        // given
        String scopeStr = "https://www.googleapis.com/auth/gmail.modify,"
            + "https://www.googleapis.com/auth/spreadsheets";
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(scopeStr)));

        // when
        List<String> result = googleOAuthService.getMyScopes(userId);

        // then
        assertThat(result).hasSize(2)
            .contains("https://www.googleapis.com/auth/gmail.modify",
                "https://www.googleapis.com/auth/spreadsheets");
    }

    @Test
    void getMyScopes_notConnected_returnsEmptyList() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.empty());

        // when
        List<String> result = googleOAuthService.getMyScopes(userId);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void getMyScopes_connectedButScopesNull_returnsEmptyList() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(null)));

        // when
        List<String> result = googleOAuthService.getMyScopes(userId);

        // then
        assertThat(result).isEmpty();
    }

    // ── getMissingScopes ─────────────────────────────────────────────────────

    @Test
    void getMissingScopes_allScopesPresent_returnsEmptyList() {
        // given
        String gmailScope = "https://www.googleapis.com/auth/gmail.modify";
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(gmailScope)));
        given(oAuthScopeConfig.getScopesByGroup("gmail"))
            .willReturn(List.of(gmailScope));

        // when
        List<String> missing = googleOAuthService.getMissingScopes(userId, List.of("gmail"));

        // then — 이미 보유하고 있으므로 빈 리스트
        assertThat(missing).isEmpty();
    }

    @Test
    void getMissingScopes_someScopesMissing_returnsMissingOnly() {
        // given — gmail은 있지만 sheets는 없음
        String gmailScope = "https://www.googleapis.com/auth/gmail.modify";
        String sheetsScope = "https://www.googleapis.com/auth/spreadsheets";
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(gmailScope)));
        given(oAuthScopeConfig.getScopesByGroup("gmail")).willReturn(List.of(gmailScope));
        given(oAuthScopeConfig.getScopesByGroup("sheets")).willReturn(List.of(sheetsScope));

        // when
        List<String> missing = googleOAuthService.getMissingScopes(userId, List.of("gmail", "sheets"));

        // then — sheets만 누락
        assertThat(missing).containsExactly(sheetsScope);
    }

    @Test
    void getMissingScopes_unknownScopeGroup_throwsInvalidInput() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount("some-scope")));
        given(oAuthScopeConfig.getScopesByGroup("unknown"))
            .willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> googleOAuthService.getMissingScopes(userId, List.of("unknown")))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    // ── getAuthorizationUrl ──────────────────────────────────────────────────

    @Test
    void getAuthorizationUrl_returnsSpringSecurityEndpoint() {
        // when
        String url = googleOAuthService.getAuthorizationUrl();

        // then
        assertThat(url).isEqualTo("/api/v1/oauth2/authorize/google");
    }

    // ── getAvailableScopes ───────────────────────────────────────────────────

    @Test
    void getAvailableScopes_returnsAllGroupsFromConfig() {
        // given
        given(oAuthScopeConfig.getScopeGroups())
            .willReturn(new java.util.LinkedHashSet<>(List.of("gmail", "sheets")));
        given(oAuthScopeConfig.getScopesByGroup("gmail"))
            .willReturn(List.of("https://www.googleapis.com/auth/gmail.modify"));
        given(oAuthScopeConfig.getScopesByGroup("sheets"))
            .willReturn(List.of("https://www.googleapis.com/auth/spreadsheets"));

        // when
        Map<String, List<String>> result = googleOAuthService.getAvailableScopes();

        // then
        assertThat(result).containsKeys("gmail", "sheets");
        assertThat(result.get("gmail")).containsExactly("https://www.googleapis.com/auth/gmail.modify");
    }

    // ── startAccountLinking ──────────────────────────────────────────────────

    @Test
    void startAccountLinking_validGroups_savesTokenAndReturnsUrl() {
        // given
        given(oAuthScopeConfig.getScopesByGroup("gmail"))
            .willReturn(List.of("https://www.googleapis.com/auth/gmail.modify"));

        // when
        String url = googleOAuthService.startAccountLinking(userId, List.of("gmail"));

        // then — Redis에 link token 저장 (userId, scopeGroups, TTL)
        verify(oAuthLinkTokenRepository).save(linkTokenCaptor.capture());
        OAuthLinkToken saved = linkTokenCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getScopeGroups()).isEqualTo("gmail");
        assertThat(saved.getTtl()).isEqualTo(300L);

        // then — URL에 scope_groups + 저장된 link_token 포함
        assertThat(url).startsWith("/api/v1/oauth2/authorize/google?scope_groups=gmail&link_token=");
        assertThat(url).endsWith(saved.getToken());
    }

    @Test
    void startAccountLinking_unknownGroup_throwsInvalidInput() {
        // given
        given(oAuthScopeConfig.getScopesByGroup("unknown")).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> googleOAuthService.startAccountLinking(userId, List.of("unknown")))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }
}
