package com.ieum.api.credential.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.repository.CredentialQueryRepository;
import com.ieum.api.credential.repository.CredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLM 크레덴셜 복호화가 소유자에게만 열려 있는지 본다 (IEUM-BE-64).
 *
 * <p>노드 {@code config}는 검증 없는 {@code Map}이라 다른 사용자의 credential UUID를 담아 저장할 수
 * 있었고, 복호화가 {@code findById}였기 때문에 그대로 남의 API 키가 실행에 실렸다.
 */
class CredentialDecryptOwnershipTest {

    private final CredentialRepository credentialRepository = mock(CredentialRepository.class);
    private final AesEncryptor aesEncryptor = mock(AesEncryptor.class);
    private final CredentialService credentialService = new CredentialService(
        credentialRepository,
        mock(CredentialQueryRepository.class),
        aesEncryptor,
        mock(CredentialValidator.class));

    private final UUID ownerId = UUID.randomUUID();
    private final UUID attackerId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();

    @Test
    @DisplayName("소유자가 요청하면 복호화된 키를 돌려준다")
    void decryptsForOwner() {
        Credential credential = Credential.builder()
            .userId(ownerId)
            .provider(AiProvider.GEMINI)
            .encryptedApiKey("encrypted")
            .build();
        given(credentialRepository.findByIdAndUserId(credentialId, ownerId))
            .willReturn(Optional.of(credential));
        given(aesEncryptor.decrypt("encrypted")).willReturn("plain-key");

        assertThat(credentialService.decrypt(credentialId, ownerId)).isEqualTo("plain-key");
    }

    @Test
    @DisplayName("남의 크레덴셜이면 NOT_FOUND로 막고 복호화 자체를 하지 않는다")
    void rejectsOtherUsersCredential() {
        given(credentialRepository.findByIdAndUserId(credentialId, attackerId))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> credentialService.decrypt(credentialId, attackerId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("userId가 없으면 조회 없이 거부한다 — 소유자를 확인할 방법이 없다")
    void rejectsNullUserId() {
        assertThatThrownBy(() -> credentialService.decrypt(credentialId, null))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
