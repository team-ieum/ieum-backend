package com.ieum.workflowcore.engine.executor;

/**
 * 크레덴셜 ID로 복호화된 API Key를 조회하는 포트 인터페이스.
 * ai 모듈의 CredentialService가 이 인터페이스를 구현하여 주입된다.
 */
public interface CredentialProvider {

    /**
     * credentialId에 해당하는 API Key를 복호화하여 반환한다.
     *
     * @param credentialId 크레덴셜 UUID (문자열)
     * @return 복호화된 API Key 원문
     */
    String getDecryptedApiKey(String credentialId);
}
