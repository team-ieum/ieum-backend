package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * 크레덴셜 ID로 복호화된 API Key를 조회하는 포트 인터페이스.
 * api 모듈의 CredentialService가 이 인터페이스를 구현하여 주입된다.
 */
public interface CredentialProvider {

    /**
     * credentialId에 해당하는 API Key를 복호화하여 반환한다.
     *
     * <p>userId는 선택 인자가 아니다 — 소유자 검증의 유일한 근거이며, 구현체는 소유자가 아니면
     * 복호화하지 않는다 (IEUM-BE-64). 노드 config의 credentialId는 사용자가 임의로 적을 수 있는 값이다.
     *
     * @param credentialId 크레덴셜 UUID (문자열)
     * @param userId 실행 주체(워크플로우 소유자) ID
     * @return 복호화된 API Key 원문
     */
    String getDecryptedApiKey(String credentialId, UUID userId);
}
