package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.ai.credential.domain.Credential;
import com.ieum.ai.credential.domain.CredentialType;
import com.ieum.ai.credential.repository.CredentialQueryRepository;
import com.ieum.ai.credential.repository.CredentialRepository;
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

        ApiKeyFormatValidator.validate(provider, rawApiKey);

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

    public String decrypt(UUID credentialId) {
        Credential credential = credentialRepository.findById(credentialId)
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
