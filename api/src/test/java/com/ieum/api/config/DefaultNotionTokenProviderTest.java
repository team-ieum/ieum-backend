package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.util.AesEncryptionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultNotionTokenProviderTest {

    @Mock private ConnectedAccountRepository connectedAccountRepository;
    @Mock private AesEncryptionService aesEncryptionService;
    @InjectMocks private DefaultNotionTokenProvider provider;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("연결된 계정이 있으면 복호화된 토큰을 반환한다")
    void getAccessToken_Success_ReturnsDecryptedToken() {
        // given
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.of(buildAccount("encrypted-token")));
        when(aesEncryptionService.decrypt("encrypted-token"))
            .thenReturn("plain-token");

        // when
        Optional<String> result = provider.getAccessToken(userId);

        // then
        assertThat(result).contains("plain-token");
    }

    @Test
    @DisplayName("연결된 계정이 없으면 빈 Optional을 반환한다")
    void getAccessToken_NoAccount_ReturnsEmpty() {
        // given
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.empty());

        // when
        Optional<String> result = provider.getAccessToken(userId);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("복호화 실패 시 예외를 전파하지 않고 빈 Optional을 반환한다")
    void getAccessToken_DecryptFails_ReturnsEmpty() {
        // given
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.of(buildAccount("corrupt-token")));
        when(aesEncryptionService.decrypt("corrupt-token"))
            .thenThrow(new RuntimeException("key mismatch"));

        // when & then
        assertThatCode(() -> provider.getAccessToken(userId)).doesNotThrowAnyException();
        assertThat(provider.getAccessToken(userId)).isEmpty();
    }

    private ConnectedAccount buildAccount(String encryptedToken) {
        return ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.NOTION)
            .accessToken(encryptedToken)
            .build();
    }
}
