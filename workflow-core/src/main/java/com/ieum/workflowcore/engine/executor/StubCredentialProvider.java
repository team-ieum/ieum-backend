package com.ieum.workflowcore.engine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * CredentialProvider 임시 구현체.
 * ai 모듈의 CredentialService가 구현되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = CredentialProvider.class, ignored = StubCredentialProvider.class)
public class StubCredentialProvider implements CredentialProvider {

    @Override
    public String getDecryptedApiKey(String credentialId) {
        log.warn("[StubCredentialProvider] CredentialService 미구현 — credentialId: {}", credentialId);
        throw new UnsupportedOperationException("CredentialService가 아직 구현되지 않았습니다.");
    }
}
