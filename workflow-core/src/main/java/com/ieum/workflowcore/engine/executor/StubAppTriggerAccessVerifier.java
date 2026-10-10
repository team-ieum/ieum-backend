package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * AppTriggerAccessVerifier 임시 구현체 — 항상 통과. BE-81의 api 모듈 구현체(GitHub App 설치 범위 검사)가 등록되면
 * 자동으로 대체된다 (@ConditionalOnMissingBean).
 */
@Component
@ConditionalOnMissingBean(value = AppTriggerAccessVerifier.class, ignored = StubAppTriggerAccessVerifier.class)
public class StubAppTriggerAccessVerifier implements AppTriggerAccessVerifier {

    @Override
    public void verify(UUID userId, TriggerType triggerType, Map<String, Object> config) {
        // no-op
    }
}
