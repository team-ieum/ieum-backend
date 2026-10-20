package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OAuthConnectionServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private SlackUserLinkRepository slackUserLinkRepository;

    @InjectMocks
    private OAuthConnectionService oauthConnectionService;

    @Test
    @DisplayName("사용자의 연동된 OAuth 목록을 정상적으로 조회한다")
    void getConnections_Success() {
        // given
        UUID userId = UUID.randomUUID();
        ConnectedAccount account = ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.GOOGLE)
            .providerAccountId("google-sub-id")
            .scopes("email profile")
            .build();

        when(connectedAccountRepository.findByUserId(userId)).thenReturn(List.of(account));

        // when
        List<OAuthConnectionResponse> responses = oauthConnectionService.getConnections(userId);

        // then
        assertThat(responses).hasSize(1);
        OAuthConnectionResponse response = responses.get(0);
        assertThat(response.getProvider()).isEqualTo("GOOGLE");
        assertThat(response.getProviderAccountId()).isEqualTo("google-sub-id");
        assertThat(response.getScopes()).containsExactly("email", "profile");
        assertThat(response.getDisplayName()).isNull();
    }

    @Test
    @DisplayName("Slack 링크가 있으면 SLACK 항목 — id=링크 id, providerAccountId=team_id, displayName=워크스페이스 이름, scopes=설치 scope")
    void getConnections_IncludesSlack() {
        UUID userId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        SlackInstallation installation = new SlackInstallation("T0123");
        installation.applyInstall("enc", null, null, "chat:write,channels:read", "이음 팀", "UBOT", "A1");
        SlackUserLink link = new SlackUserLink(userId, installation, "U1");
        ReflectionTestUtils.setField(link, "id", linkId);
        when(connectedAccountRepository.findByUserId(userId)).thenReturn(List.of());
        when(slackUserLinkRepository.findByUserId(userId)).thenReturn(Optional.of(link));

        List<OAuthConnectionResponse> responses = oauthConnectionService.getConnections(userId);

        assertThat(responses).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo(linkId);
            assertThat(r.getProvider()).isEqualTo("SLACK");
            assertThat(r.getProviderAccountId()).isEqualTo("T0123");
            assertThat(r.getDisplayName()).isEqualTo("이음 팀");
            assertThat(r.getScopes()).containsExactly("chat:write", "channels:read");
        });
    }
}
