package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.Map;
import java.util.UUID;

/**
 * 앱 트리거 저장·활성화 시점의 리소스 접근 검증 포트(IDOR 방지, spec §5). 구독 동기화가 행을 쓰기 직전에 부른다 —
 * 같은 트랜잭션이라 거부(예외)하면 워크플로우 저장·활성화 전체가 롤백된다.
 *
 * <p>BE-81에서 api가 구현한다(GitHub: 소유자의 App 설치 범위 — {@code github.installed_repos}와 같은 조회 — 에 repoId가
 * 없으면 400, Gmail은 통과). 구현 없으면 {@link StubAppTriggerAccessVerifier}(통과).
 */
public interface AppTriggerAccessVerifier {

    /**
     * @param config 구독 스냅샷 — GitHub {@code {repoId: Long|null}}, Gmail {@code {}}·{@code {query}}
     * @throws com.ieum.common.exception.CustomException 접근 불가(400 계열)
     */
    void verify(UUID userId, TriggerType triggerType, Map<String, Object> config);
}
