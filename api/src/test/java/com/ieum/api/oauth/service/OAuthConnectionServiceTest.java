package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthConnectionServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

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
    }
}
