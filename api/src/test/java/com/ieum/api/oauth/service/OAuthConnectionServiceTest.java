package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
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

    @Test
    @DisplayName("연동 해제 — 해당 provider 연동 행을 삭제한다")
    void disconnect_DeletesConnectedAccount() {
        UUID userId = UUID.randomUUID();
        ConnectedAccount account = ConnectedAccount.builder()
            .userId(userId).provider(AuthProvider.NOTION).providerAccountId("bot-id").build();
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.of(account));

        oauthConnectionService.disconnect(userId, AuthProvider.NOTION);

        verify(connectedAccountRepository).delete(account);
    }

    @Test
    @DisplayName("연동 해제 — 연동되지 않은 provider면 ACCOUNT_NOT_CONNECTED")
    void disconnect_NotConnected_Throws() {
        UUID userId = UUID.randomUUID();
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GITHUB))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> oauthConnectionService.disconnect(userId, AuthProvider.GITHUB))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.ACCOUNT_NOT_CONNECTED);
        verify(connectedAccountRepository, never()).delete(any());
    }

    @Test
    @DisplayName("연동 해제 — LOCAL은 연동 대상이 아니라 INVALID_INPUT")
    void disconnect_Local_Throws() {
        assertThatThrownBy(() -> oauthConnectionService.disconnect(UUID.randomUUID(), AuthProvider.LOCAL))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(connectedAccountRepository, never()).findByUserIdAndProvider(any(), any());
    }
}
