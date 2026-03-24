package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.ai.credential.domain.Credential;
import com.ieum.ai.credential.domain.CredentialType;
import com.ieum.ai.credential.repository.CredentialQueryRepository;
import com.ieum.ai.credential.repository.CredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CredentialServiceTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private CredentialQueryRepository credentialQueryRepository;
    @Mock private AesEncryptor aesEncryptor;
    @Mock private CredentialValidator credentialValidator;

    @InjectMocks
    private CredentialService credentialService;

    private final UUID userId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();

    // ===== create =====

    @Test
    void create_success() {
        given(credentialQueryRepository.existsByUserIdAndProviderAndDisplayName(userId, AiProvider.CLAUDE, "My Claude Key")).willReturn(false);
        given(credentialQueryRepository.countByUserId(userId)).willReturn(0L);
        given(aesEncryptor.encrypt(anyString())).willReturn("encrypted-key");
        Credential saved = buildCredential();
        given(credentialRepository.save(any())).willReturn(saved);

        Credential result = credentialService.create(userId, AiProvider.CLAUDE, CredentialType.API_KEY,
                "My Claude Key", "sk-ant-api03-testkey12345");

        assertThat(result).isEqualTo(saved);
        then(credentialRepository).should().save(any(Credential.class));
    }

    @Test
    void create_invalidKeyFormat_throwsInvalidApiKeyFormat() {
        assertThatThrownBy(() -> credentialService.create(userId, AiProvider.CLAUDE,
                CredentialType.API_KEY, "My Key", "invalid-format-key-here"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_API_KEY_FORMAT));

        then(credentialRepository).should(never()).save(any());
    }

    @Test
    void create_duplicateDisplayName_throwsCredentialDuplicateName() {
        given(credentialQueryRepository.existsByUserIdAndProviderAndDisplayName(
                userId, AiProvider.CLAUDE, "My Claude Key")).willReturn(true);

        assertThatThrownBy(() -> credentialService.create(userId, AiProvider.CLAUDE,
                CredentialType.API_KEY, "My Claude Key", "sk-ant-api03-testkey12345"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_DUPLICATE_NAME));

        then(credentialRepository).should(never()).save(any());
    }

    @Test
    void create_limitExceeded_throwsCredentialLimitExceeded() {
        given(credentialQueryRepository.existsByUserIdAndProviderAndDisplayName(any(), any(), anyString())).willReturn(false);
        given(credentialQueryRepository.countByUserId(userId)).willReturn(10L);

        assertThatThrownBy(() -> credentialService.create(userId, AiProvider.CLAUDE,
                CredentialType.API_KEY, "My Key", "sk-ant-api03-testkey12345"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_LIMIT_EXCEEDED));

        then(credentialRepository).should(never()).save(any());
    }

    @Test
    void create_blankApiKey_throwsInvalidInput() {
        assertThatThrownBy(() -> credentialService.create(userId, AiProvider.CLAUDE,
                CredentialType.API_KEY, "My Key", "  "))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));

        then(credentialRepository).should(never()).save(any());
    }

    @Test
    void create_nullApiKey_throwsInvalidInput() {
        assertThatThrownBy(() -> credentialService.create(userId, AiProvider.CLAUDE,
                CredentialType.API_KEY, "My Key", null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    // ===== getByUserId =====

    @Test
    void getByUserId_returnsList() {
        List<Credential> credentials = List.of(buildCredential(), buildCredential());
        given(credentialQueryRepository.findByUserId(userId)).willReturn(credentials);

        List<Credential> result = credentialService.getByUserId(userId);

        assertThat(result).hasSize(2);
    }

    // ===== getByIdAndUserId =====

    @Test
    void getByIdAndUserId_success() {
        Credential credential = buildCredential();
        given(credentialRepository.findByIdAndUserId(credentialId, userId)).willReturn(Optional.of(credential));

        Credential result = credentialService.getByIdAndUserId(credentialId, userId);

        assertThat(result).isEqualTo(credential);
    }

    @Test
    void getByIdAndUserId_notFound_throwsNotFound() {
        given(credentialRepository.findByIdAndUserId(credentialId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> credentialService.getByIdAndUserId(credentialId, userId))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ===== delete =====

    @Test
    void delete_success() {
        Credential credential = buildCredential();
        given(credentialRepository.findByIdAndUserId(credentialId, userId)).willReturn(Optional.of(credential));

        credentialService.delete(credentialId, userId);

        then(credentialRepository).should().delete(credential);
    }

    @Test
    void delete_notOwner_throwsNotFound() {
        given(credentialRepository.findByIdAndUserId(credentialId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> credentialService.delete(credentialId, userId))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        then(credentialRepository).should(never()).delete(any());
    }

    // ===== validateCredential =====

    @Test
    void validateCredential_valid_returnsSuccess() {
        Credential credential = buildCredential();
        given(credentialRepository.findByIdAndUserId(credentialId, userId)).willReturn(Optional.of(credential));
        given(aesEncryptor.decrypt(anyString())).willReturn("sk-ant-api03-decrypted");
        given(credentialValidator.validate(AiProvider.CLAUDE, "sk-ant-api03-decrypted"))
                .willReturn(CredentialValidationResult.success("CLAUDE"));

        CredentialValidationResult result = credentialService.validateCredential(credentialId, userId);

        assertThat(result.valid()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void validateCredential_invalid_returnsFailed() {
        Credential credential = buildCredential();
        given(credentialRepository.findByIdAndUserId(credentialId, userId)).willReturn(Optional.of(credential));
        given(aesEncryptor.decrypt(anyString())).willReturn("invalid-key");
        given(credentialValidator.validate(AiProvider.CLAUDE, "invalid-key"))
                .willReturn(CredentialValidationResult.failed("CLAUDE", "API 키가 유효하지 않습니다."));

        CredentialValidationResult result = credentialService.validateCredential(credentialId, userId);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).isEqualTo("API 키가 유효하지 않습니다.");
    }

    // ===== 헬퍼 =====

    private Credential buildCredential() {
        return Credential.builder()
                .userId(userId)
                .provider(AiProvider.CLAUDE)
                .credentialType(CredentialType.API_KEY)
                .displayName("My Claude Key")
                .encryptedApiKey("encrypted-key")
                .keyHint("sk-ant...1234")
                .isValid(true)
                .build();
    }
}
