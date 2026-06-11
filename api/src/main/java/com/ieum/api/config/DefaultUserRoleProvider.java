package com.ieum.api.config;

import com.ieum.auth.repository.UserRepository;
import com.ieum.workflowcore.engine.executor.UserRoleProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * UserRoleProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 auth 모두에 의존하므로, workflow-core의 포트(UserRoleProvider)를
 * auth의 {@code UserRepository}로 연결한다. 조회된 role은 agent의 X-User-Role 헤더로 전달되어
 * 개발/테스트 계정의 자체 호스팅 LLM 라우팅 판단에 사용된다.
 *
 * <p>이 빈이 등록되면 workflow-core의 {@code StubUserRoleProvider}는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Component
@RequiredArgsConstructor
public class DefaultUserRoleProvider implements UserRoleProvider {

    private final UserRepository userRepository;

    @Override
    public String findRoleByUserId(UUID userId) {
        return userRepository.findById(userId)
            .map(user -> user.getRole().name())
            .orElse(null);
    }
}
