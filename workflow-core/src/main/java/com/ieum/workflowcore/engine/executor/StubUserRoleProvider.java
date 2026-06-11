package com.ieum.workflowcore.engine.executor;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * UserRoleProvider 임시 구현체.
 * api 모듈의 {@code DefaultUserRoleProvider}가 등록되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 *
 * <p>role을 알 수 없으므로 null을 반환하며, 이 경우 agent는 일반 유저(등록 API 키 경로)로 처리한다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = UserRoleProvider.class, ignored = StubUserRoleProvider.class)
public class StubUserRoleProvider implements UserRoleProvider {

    @Override
    public String findRoleByUserId(UUID userId) {
        log.debug("[StubUserRoleProvider] UserRoleProvider 미구현 — userId: {}", userId);
        return null;
    }
}
