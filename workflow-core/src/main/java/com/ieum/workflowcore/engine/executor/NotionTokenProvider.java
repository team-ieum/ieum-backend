package com.ieum.workflowcore.engine.executor;

import java.util.Optional;
import java.util.UUID;

/**
 * 사용자의 Notion Access Token을 제공하는 인터페이스.
 *
 * <p>workflow-core는 auth/api 모듈에 의존하지 않으므로 인터페이스만 정의하고
 * 실제 구현체는 api 모듈의 DefaultNotionTokenProvider에 위치한다.
 */
public interface NotionTokenProvider {

    /**
     * userId에 해당하는 Notion Access Token(평문)을 반환한다.
     * OAuth 연동이 없으면 Optional.empty()를 반환한다.
     */
    Optional<String> getAccessToken(UUID userId);
}
