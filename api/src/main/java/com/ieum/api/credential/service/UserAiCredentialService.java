package com.ieum.api.credential.service;

import com.ieum.api.credential.domain.UserAiCredential;
import com.ieum.api.credential.repository.UserAiCredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesUtil;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAiCredentialService {

    private final UserAiCredentialRepository userAiCredentialRepository;
    private final AesUtil aesUtil;

    public UserAiCredential getByIdAndUserId(UUID credentialId, UUID userId) {
        return userAiCredentialRepository.findByIdAndUserId(credentialId, userId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_API_KEY));
    }

    public String decryptApiKey(UserAiCredential credential) {
        if (credential.getEncryptedApiKey() == null) {
            throw new CustomException(ErrorCode.INVALID_API_KEY);
        }
        return aesUtil.decrypt(credential.getEncryptedApiKey());
    }
}
