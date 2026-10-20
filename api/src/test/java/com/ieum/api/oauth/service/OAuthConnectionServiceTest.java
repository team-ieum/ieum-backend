package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OAuthConnectionServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private OAuthTokenRevoker tokenRevoker;

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
    @DisplayName("연동 해제 — 행을 삭제하고, 커밋 후에 복호화한 토큰으로 외부 폐기를 요청한다")
    void disconnect_DeletesThenRevokesAfterCommit() {
        UUID userId = UUID.randomUUID();
        ConnectedAccount account = ConnectedAccount.builder()
            .userId(userId).provider(AuthProvider.GOOGLE).providerAccountId("sub")
            .accessToken("enc-access").refreshToken("enc-refresh").build();
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .thenReturn(Optional.of(account));
        when(aesEncryptor.decrypt("enc-access")).thenReturn("access");
        when(aesEncryptor.decrypt("enc-refresh")).thenReturn("refresh");

        runInTransaction(() -> {
            oauthConnectionService.disconnect(userId, AuthProvider.GOOGLE);
            // 커밋 전에는 외부에 아무것도 보내지 않는다 — 롤백되면 연동이 남는데 외부 권한만 사라지는 일을 막는다
            verify(tokenRevoker, never()).revoke(any(), any(), any());
        });

        InOrder order = inOrder(connectedAccountRepository, tokenRevoker);
        order.verify(connectedAccountRepository).delete(account);
        order.verify(tokenRevoker).revoke(AuthProvider.GOOGLE, "access", "refresh");
    }

    @Test
    @DisplayName("연동 해제 — 토큰 복호화에 실패해도 해제는 진행하고 외부 폐기만 건너뛴다")
    void disconnect_DecryptFails_StillDeletes() {
        UUID userId = UUID.randomUUID();
        ConnectedAccount account = ConnectedAccount.builder()
            .userId(userId).provider(AuthProvider.NOTION).providerAccountId("bot-id")
            .accessToken("broken").build();
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.of(account));
        when(aesEncryptor.decrypt("broken")).thenThrow(new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED));

        runInTransaction(() -> oauthConnectionService.disconnect(userId, AuthProvider.NOTION));

        verify(connectedAccountRepository).delete(account);
        verify(tokenRevoker).revoke(AuthProvider.NOTION, null, null);
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

    /** afterCommit 콜백 등록에 트랜잭션 동기화가 필요하다 — WorkflowCrudServiceDeleteTest와 같은 방식. */
    private void runInTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
