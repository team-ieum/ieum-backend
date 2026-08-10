package com.ieum.api.credential.service;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.domain.CredentialType;
import com.ieum.api.credential.repository.CredentialQueryRepository;
import com.ieum.api.credential.repository.CredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CredentialService {

    private static final int MAX_CREDENTIALS_PER_USER = 10;

    private final CredentialRepository credentialRepository;
    private final CredentialQueryRepository credentialQueryRepository;
    private final AesEncryptor aesEncryptor;
    private final CredentialValidator credentialValidator;

    @Transactional
    public Credential create(UUID userId, AiProvider provider, CredentialType credentialType,
                             String displayName, String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        if (credentialQueryRepository.existsByUserIdAndProviderAndDisplayName(userId, provider, displayName)) {
            throw new CustomException(ErrorCode.CREDENTIAL_DUPLICATE_NAME);
        }

        if (credentialQueryRepository.countByUserId(userId) >= MAX_CREDENTIALS_PER_USER) {
            throw new CustomException(ErrorCode.CREDENTIAL_LIMIT_EXCEEDED,
                    "크레덴셜은 최대 " + MAX_CREDENTIALS_PER_USER + "개까지 등록할 수 있습니다.");
        }

        String encryptedApiKey = aesEncryptor.encrypt(rawApiKey);
        String keyHint = generateKeyHint(rawApiKey);

        Credential credential = Credential.builder()
                .userId(userId)
                .provider(provider)
                .credentialType(credentialType)
                .displayName(displayName)
                .encryptedApiKey(encryptedApiKey)
                .keyHint(keyHint)
                .isValid(true)
                .build();

        return credentialRepository.save(credential);
    }

    public List<Credential> getByUserId(UUID userId) {
        return credentialQueryRepository.findByUserId(userId);
    }

    public Credential getByIdAndUserId(UUID credentialId, UUID userId) {
        return credentialRepository.findByIdAndUserId(credentialId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public void delete(UUID credentialId, UUID userId) {
        Credential credential = getByIdAndUserId(credentialId, userId);
        credentialRepository.delete(credential);
    }

    /**
     * 크레덴셜을 복호화한다. 소유자만 열 수 있다 (IEUM-BE-64).
     *
     * <p>이 조회 하나가 소유자 검증의 전부다 — {@code findByIdAndUserId}를 {@code findById}로 되돌리면
     * 워크플로우 노드 config에 남의 credential UUID를 적는 것만으로 그 사용자의 API 키가 실행에 실린다.
     * 웹훅 자격증명({@code DefaultWebhookCredentialProvider})이 같은 이유로 같은 조회를 쓴다.
     *
     * <p>{@code userId}가 없으면 소유자를 확인할 방법이 없으므로 조회 없이 거부한다(fail-closed).
     * 실행 컨텍스트의 userId는 항상 워크플로우 소유자로 채워지므로(SyncExecutionRuntime), null은
     * 배선이 빠진 경로라는 뜻이다.
     */
    public String decrypt(UUID credentialId, UUID userId) {
        if (userId == null) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }
        Credential credential = credentialRepository.findByIdAndUserId(credentialId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        return aesEncryptor.decrypt(credential.getEncryptedApiKey());
    }

    @Transactional
    public CredentialValidationResult validateCredential(UUID credentialId, UUID userId) {
        Credential credential = getByIdAndUserId(credentialId, userId);
        String decryptedKey = aesEncryptor.decrypt(credential.getEncryptedApiKey());
        CredentialValidationResult result = credentialValidator.validate(credential.getProvider(), decryptedKey);
        credential.updateValidation(result.valid());
        return result;
    }

    @Transactional
    public void updateValidation(UUID credentialId, boolean isValid) {
        Credential credential = credentialRepository.findById(credentialId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        credential.updateValidation(isValid);
    }

    private String generateKeyHint(String rawApiKey) {
        if (rawApiKey.length() < 10) {
            return "****";
        }
        return rawApiKey.substring(0, 6) + "..." + rawApiKey.substring(rawApiKey.length() - 4);
    }
}
