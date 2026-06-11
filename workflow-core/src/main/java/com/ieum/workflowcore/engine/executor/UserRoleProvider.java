package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * userId로 사용자 권한(role) 문자열을 조회하는 포트 인터페이스.
 * api 모듈의 어댑터(DefaultUserRoleProvider)가 UserRepository를 통해 구현하여 주입된다.
 *
 * <p>workflow-core는 auth 모듈(User/UserRole)에 직접 의존하지 않으므로,
 * 의존성 역전을 위해 이 포트로 role을 조회한다. role 문자열은 그대로 agent에 X-User-Role 헤더로 전달되어
 * 개발/테스트 계정(ROLE_ADMIN/ROLE_TESTER)의 자체 호스팅 LLM 라우팅 판단에 사용된다.
 */
public interface UserRoleProvider {

    /**
     * userId에 해당하는 사용자의 role 문자열(예: "ROLE_TESTER")을 반환한다.
     *
     * @param userId 사용자 UUID
     * @return role 문자열, 사용자를 찾을 수 없으면 null
     */
    String findRoleByUserId(UUID userId);
}
