package com.ieum.api.config;

import com.ieum.api.credential.service.CredentialService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CredentialProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 credential 서비스 모두에 의존하므로,
 * workflow-core의 포트(CredentialProvider)를 api의 서비스(CredentialService)로 연결한다.
 *
 * <p>이 빈이 등록되면 workflow-core의 {@code StubCredentialProvider}는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Component
@RequiredArgsConstructor
public class DefaultCredentialProvider implements CredentialProvider {

    private final CredentialService credentialService;

    @Override
    public String getDecryptedApiKey(String credentialId) {
        try {
            return credentialService.decrypt(UUID.fromString(credentialId));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "유효하지 않은 Credential ID 형식입니다.");
        }
    }
}
