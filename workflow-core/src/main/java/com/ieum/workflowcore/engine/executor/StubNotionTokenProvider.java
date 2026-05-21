package com.ieum.workflowcore.engine.executor;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * NotionTokenProvider 임시 Stub 구현체.
 *
 * <p>api 모듈의 DefaultNotionTokenProvider가 등록되면 자동으로 대체된다.
 * {@code @ConditionalOnMissingBean}으로 실제 구현체가 없을 때만 활성화된다.
 *
 * <p>workflow-core 단독 실행(테스트, 모듈 빌드 등) 시에도 빈 주입 오류 없이 기동할 수 있도록
 * UnsupportedOperationException을 던져 명시적으로 실패를 알린다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = NotionTokenProvider.class, ignored = StubNotionTokenProvider.class)
public class StubNotionTokenProvider implements NotionTokenProvider {

    @Override
    public Optional<String> getAccessToken(UUID userId) {
        log.warn("[StubNotionTokenProvider] DefaultNotionTokenProvider 미구현 — userId: {}", userId);
        throw new UnsupportedOperationException("NotionTokenProvider가 아직 구현되지 않았습니다.");
    }
}
